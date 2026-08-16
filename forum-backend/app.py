import re
import math
import sqlite3
import secrets
from datetime import datetime, timedelta
from functools import wraps

import bleach
import markdown
from flask import Flask, request, jsonify, send_from_directory, g
from werkzeug.security import generate_password_hash, check_password_hash

app = Flask(__name__)

DATABASE = "forum.db"
TOKEN_EXPIRY_DAYS = 30
PER_PAGE_DEFAULT = 20
PER_PAGE_MAX = 50

MAX_USERNAME_LEN = 30
MAX_EMAIL_LEN = 254
MAX_PASSWORD_LEN = 128
MAX_TITLE_LEN = 200
MAX_CONTENT_LEN = 20000
MAX_BIO_LEN = 500
MAX_REASON_LEN = 500

USERNAME_RE = re.compile(r"^[A-Za-z0-9_]{3,30}$")
EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")

# Tags/attributes allowed in rendered markdown output (prevents stored XSS)
ALLOWED_TAGS = [
    "p", "br", "strong", "em", "u", "s", "code", "pre",
    "blockquote", "ul", "ol", "li", "a", "h1", "h2", "h3",
    "h4", "h5", "h6", "hr", "img"
]
ALLOWED_ATTRS = {
    "a": ["href", "title", "rel"],
    "img": ["src", "alt", "title"]
}


def db():
    connection = sqlite3.connect(DATABASE)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA foreign_keys = ON")
    return connection


def render_markdown(raw_text):
    """Convert raw markdown to sanitized HTML safe to render on the frontend."""
    html = markdown.markdown(
        raw_text,
        extensions=["extra", "nl2br", "sane_lists"]
    )
    return bleach.clean(
        html,
        tags=ALLOWED_TAGS,
        attributes=ALLOWED_ATTRS,
        strip=True
    )


def sanitize_plain(text):
    """Strip all HTML from freeform fields that are NOT rendered as markdown (e.g. bio)."""
    return bleach.clean(text or "", tags=[], attributes={}, strip=True)


def get_json_body():
    """Safely parse the JSON body. Returns a dict, or None if missing/malformed."""
    data = request.get_json(silent=True)
    return data if isinstance(data, dict) else None


# =========================================================
# DATABASE
# =========================================================

def init_db():
    connection = db()

    connection.executescript("""
        CREATE TABLE IF NOT EXISTS users (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            username TEXT UNIQUE NOT NULL,
            email TEXT UNIQUE NOT NULL,
            password TEXT NOT NULL,
            bio TEXT DEFAULT '',
            role TEXT DEFAULT 'user',
            joined_at DATETIME DEFAULT CURRENT_TIMESTAMP
        );

        CREATE TABLE IF NOT EXISTS sessions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id INTEGER NOT NULL,
            token TEXT UNIQUE NOT NULL,
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
            expires_at DATETIME NOT NULL
        );

        CREATE TABLE IF NOT EXISTS categories (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT UNIQUE NOT NULL
        );

        CREATE TABLE IF NOT EXISTS threads (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            title TEXT NOT NULL,
            content TEXT NOT NULL,
            user_id INTEGER NOT NULL,
            category_id INTEGER NOT NULL,
            pinned INTEGER DEFAULT 0,
            locked INTEGER DEFAULT 0,
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP
        );

        CREATE TABLE IF NOT EXISTS posts (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            thread_id INTEGER NOT NULL,
            content TEXT NOT NULL,
            user_id INTEGER NOT NULL,
            quote_post_id INTEGER,
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP
        );

        CREATE TABLE IF NOT EXISTS likes (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            post_id INTEGER NOT NULL,
            user_id INTEGER NOT NULL,
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
            UNIQUE(post_id, user_id)
        );

        CREATE TABLE IF NOT EXISTS reports (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            post_id INTEGER,
            thread_id INTEGER,
            reporter_id INTEGER NOT NULL,
            reason TEXT NOT NULL,
            status TEXT DEFAULT 'open',
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP
        );

        CREATE INDEX IF NOT EXISTS idx_threads_category ON threads(category_id);
        CREATE INDEX IF NOT EXISTS idx_threads_user ON threads(user_id);
        CREATE INDEX IF NOT EXISTS idx_posts_thread ON posts(thread_id);
        CREATE INDEX IF NOT EXISTS idx_posts_user ON posts(user_id);
        CREATE INDEX IF NOT EXISTS idx_likes_post ON likes(post_id);
        CREATE INDEX IF NOT EXISTS idx_sessions_token ON sessions(token);
        CREATE INDEX IF NOT EXISTS idx_sessions_expires ON sessions(expires_at);
        CREATE INDEX IF NOT EXISTS idx_reports_status ON reports(status);
    """)

    categories = [
        "General Operations",
        "Flight Operations",
        "Training & Standards",
        "Official Announcements",
        "Lounge",
        "Screenshots & Media",
        "Simulator & Hardware",
        "Help Desk"
    ]

    for category in categories:
        connection.execute(
            "INSERT OR IGNORE INTO categories (name) VALUES (?)",
            (category,)
        )

    connection.commit()
    connection.close()

    migrate_schema()


def migrate_schema():
    """Adds columns to tables that may already exist from an older version of this app."""
    connection = db()
    cursor = connection.cursor()

    def has_column(table, column):
        cursor.execute(f"PRAGMA table_info({table})")
        return any(row[1] == column for row in cursor.fetchall())

    migrations = [
        ("users", "bio", "TEXT DEFAULT ''"),
        ("users", "role", "TEXT DEFAULT 'user'"),
        ("users", "joined_at", "DATETIME DEFAULT CURRENT_TIMESTAMP"),
        ("threads", "pinned", "INTEGER DEFAULT 0"),
        ("threads", "locked", "INTEGER DEFAULT 0"),
        ("posts", "quote_post_id", "INTEGER"),
    ]

    for table, column, definition in migrations:
        if not has_column(table, column):
            cursor.execute(f"ALTER TABLE {table} ADD COLUMN {column} {definition}")

    connection.commit()
    connection.close()


# =========================================================
# AUTH HELPERS
# =========================================================

def cleanup_expired_sessions(connection):
    connection.execute(
        "DELETE FROM sessions WHERE expires_at <= ?",
        (datetime.utcnow().isoformat(),)
    )


def create_session(user_id):
    token = secrets.token_hex(32)
    expires_at = (datetime.utcnow() + timedelta(days=TOKEN_EXPIRY_DAYS)).isoformat()

    connection = db()
    cleanup_expired_sessions(connection)
    connection.execute(
        "INSERT INTO sessions (user_id, token, expires_at) VALUES (?, ?, ?)",
        (user_id, token, expires_at)
    )
    connection.commit()
    connection.close()

    return token


def get_user_from_token(token):
    connection = db()
    user = connection.execute("""
        SELECT users.*
        FROM sessions
        JOIN users ON users.id = sessions.user_id
        WHERE sessions.token = ? AND sessions.expires_at > ?
    """, (token, datetime.utcnow().isoformat())).fetchone()
    connection.close()
    return user


def login_required(f):
    @wraps(f)
    def wrapper(*args, **kwargs):
        auth_header = request.headers.get("Authorization", "")

        if not auth_header.startswith("Bearer "):
            return jsonify({"error": "Authentication required"}), 401

        token = auth_header[7:].strip()
        user = get_user_from_token(token)

        if not user:
            return jsonify({"error": "Invalid or expired session"}), 401

        g.user = user
        return f(*args, **kwargs)

    return wrapper


def role_required(*roles):
    """Build a decorator that requires login AND one of the given roles."""
    def decorator(f):
        @wraps(f)
        @login_required
        def wrapper(*args, **kwargs):
            if g.user["role"] not in roles:
                return jsonify({"error": "Insufficient permissions"}), 403
            return f(*args, **kwargs)
        return wrapper
    return decorator


moderator_required = role_required("admin", "moderator")
admin_required = role_required("admin")


def paginate_args():
    try:
        page = max(1, int(request.args.get("page", 1)))
    except (TypeError, ValueError):
        page = 1

    try:
        per_page = int(request.args.get("per_page", PER_PAGE_DEFAULT))
    except (TypeError, ValueError):
        per_page = PER_PAGE_DEFAULT

    per_page = max(1, min(per_page, PER_PAGE_MAX))
    offset = (page - 1) * per_page

    return page, per_page, offset


def public_user(user_row):
    """Strips the password hash before returning a user to the client."""
    data = dict(user_row)
    data.pop("password", None)
    return data


# =========================================================
# REGISTER / LOGIN / LOGOUT / ME
# =========================================================

@app.post("/api/register")
def register():
    data = get_json_body()
    if data is None:
        return jsonify({"error": "Invalid JSON body"}), 400

    username = (data.get("username") or "").strip()
    email = (data.get("email") or "").strip().lower()
    password = data.get("password") or ""

    if not username or not email or not password:
        return jsonify({"error": "All fields are required"}), 400

    if not USERNAME_RE.match(username):
        return jsonify({
            "error": "Username must be 3-30 characters, letters/numbers/underscore only"
        }), 400

    if len(email) > MAX_EMAIL_LEN or not EMAIL_RE.match(email):
        return jsonify({"error": "Invalid email address"}), 400

    if len(password) < 8:
        return jsonify({"error": "Password must contain at least 8 characters"}), 400

    if len(password) > MAX_PASSWORD_LEN:
        return jsonify({"error": f"Password must be at most {MAX_PASSWORD_LEN} characters"}), 400

    connection = db()

    try:
        connection.execute(
            """
            INSERT INTO users (username, email, password)
            VALUES (?, ?, ?)
            """,
            (username, email, generate_password_hash(password))
        )
        connection.commit()

    except sqlite3.IntegrityError:
        connection.close()
        return jsonify({"error": "Username or email already exists"}), 409

    connection.close()

    return jsonify({"message": "Account created"}), 201


@app.post("/api/login")
def login():
    data = get_json_body()
    if data is None:
        return jsonify({"error": "Invalid JSON body"}), 400

    username = (data.get("username") or "").strip()
    password = data.get("password") or ""

    if not username or not password:
        return jsonify({"error": "Username and password are required"}), 400

    connection = db()

    user = connection.execute(
        "SELECT * FROM users WHERE username = ?",
        (username,)
    ).fetchone()

    connection.close()

    if not user or not check_password_hash(user["password"], password):
        return jsonify({"error": "Invalid username or password"}), 401

    token = create_session(user["id"])

    return jsonify({
        "message": "Login successful",
        "token": token,
        "user": public_user(user)
    })


@app.post("/api/logout")
@login_required
def logout():
    auth_header = request.headers.get("Authorization", "")
    token = auth_header[7:].strip()

    connection = db()
    connection.execute("DELETE FROM sessions WHERE token = ?", (token,))
    connection.commit()
    connection.close()

    return jsonify({"message": "Logged out"})


@app.get("/api/me")
@login_required
def me():
    return jsonify(public_user(g.user))


# =========================================================
# CATEGORIES
# =========================================================

@app.get("/api/categories")
def categories():
    connection = db()
    rows = connection.execute("SELECT * FROM categories ORDER BY name").fetchall()
    connection.close()
    return jsonify([dict(row) for row in rows])


# =========================================================
# THREADS
# =========================================================

@app.get("/api/threads")
def threads():
    page, per_page, offset = paginate_args()
    category_id = request.args.get("category_id")
    q = request.args.get("q", "").strip()

    connection = db()

    where_clause = " WHERE 1 = 1"
    params = []

    if category_id:
        where_clause += " AND threads.category_id = ?"
        params.append(category_id)

    if q:
        where_clause += " AND (threads.title LIKE ? OR threads.content LIKE ?)"
        like = f"%{q}%"
        params.extend([like, like])

    total = connection.execute(
        f"SELECT COUNT(*) AS c FROM threads{where_clause}", params
    ).fetchone()["c"]

    query = f"""
        SELECT
            threads.id, threads.title, threads.content, threads.created_at,
            threads.user_id, threads.pinned, threads.locked,
            users.username, categories.name AS category,
            (SELECT COUNT(*) FROM posts WHERE posts.thread_id = threads.id) AS reply_count
        FROM threads
        JOIN users ON users.id = threads.user_id
        JOIN categories ON categories.id = threads.category_id
        {where_clause}
        ORDER BY threads.pinned DESC, threads.created_at DESC
        LIMIT ? OFFSET ?
    """
    rows = connection.execute(query, params + [per_page, offset]).fetchall()
    connection.close()

    results = []
    for row in rows:
        item = dict(row)
        item["content_html"] = render_markdown(item["content"])
        results.append(item)

    return jsonify({
        "page": page,
        "per_page": per_page,
        "total": total,
        "total_pages": max(1, math.ceil(total / per_page)),
        "threads": results
    })


@app.post("/api/threads")
@login_required
def create_thread():
    data = get_json_body()
    if data is None:
        return jsonify({"error": "Invalid JSON body"}), 400

    title = (data.get("title") or "").strip()
    content = (data.get("content") or "").strip()
    category_id = data.get("category_id")

    if not title or not content or not category_id:
        return jsonify({"error": "Title, content and category_id are required"}), 400

    if len(title) > MAX_TITLE_LEN:
        return jsonify({"error": f"Title must be at most {MAX_TITLE_LEN} characters"}), 400

    if len(content) > MAX_CONTENT_LEN:
        return jsonify({"error": f"Content must be at most {MAX_CONTENT_LEN} characters"}), 400

    connection = db()

    category = connection.execute(
        "SELECT id FROM categories WHERE id = ?", (category_id,)
    ).fetchone()

    if not category:
        connection.close()
        return jsonify({"error": "Invalid category_id"}), 400

    cursor = connection.execute(
        """
        INSERT INTO threads (title, content, user_id, category_id)
        VALUES (?, ?, ?, ?)
        """,
        (title, content, g.user["id"], category_id)
    )
    connection.commit()

    thread_id = cursor.lastrowid
    connection.close()

    return jsonify({"message": "Thread created", "thread_id": thread_id}), 201


@app.get("/api/threads/<int:thread_id>")
def get_thread(thread_id):
    page, per_page, offset = paginate_args()

    connection = db()

    thread = connection.execute("""
        SELECT
            threads.*, users.username, categories.name AS category
        FROM threads
        JOIN users ON users.id = threads.user_id
        JOIN categories ON categories.id = threads.category_id
        WHERE threads.id = ?
    """, (thread_id,)).fetchone()

    if not thread:
        connection.close()
        return jsonify({"error": "Thread not found"}), 404

    posts = connection.execute("""
        SELECT
            posts.*, users.username,
            (SELECT COUNT(*) FROM likes WHERE likes.post_id = posts.id) AS like_count
        FROM posts
        JOIN users ON users.id = posts.user_id
        WHERE thread_id = ?
        ORDER BY posts.created_at
        LIMIT ? OFFSET ?
    """, (thread_id, per_page, offset)).fetchall()

    total_posts = connection.execute(
        "SELECT COUNT(*) AS c FROM posts WHERE thread_id = ?",
        (thread_id,)
    ).fetchone()["c"]

    posts_list = []
    for post in posts:
        post_dict = dict(post)
        post_dict["content_html"] = render_markdown(post_dict["content"])

        if post_dict.get("quote_post_id"):
            quoted = connection.execute("""
                SELECT posts.id, posts.content, users.username
                FROM posts
                JOIN users ON users.id = posts.user_id
                WHERE posts.id = ?
            """, (post_dict["quote_post_id"],)).fetchone()

            if quoted:
                post_dict["quoted_post"] = dict(quoted)

        posts_list.append(post_dict)

    connection.close()

    thread_dict = dict(thread)
    thread_dict["content_html"] = render_markdown(thread_dict["content"])

    return jsonify({
        "thread": thread_dict,
        "posts": posts_list,
        "page": page,
        "per_page": per_page,
        "total_posts": total_posts,
        "total_pages": max(1, math.ceil(total_posts / per_page))
    })


@app.put("/api/threads/<int:thread_id>")
@login_required
def edit_thread(thread_id):
    data = get_json_body()
    if data is None:
        return jsonify({"error": "Invalid JSON body"}), 400

    connection = db()

    thread = connection.execute(
        "SELECT * FROM threads WHERE id = ?",
        (thread_id,)
    ).fetchone()

    if not thread:
        connection.close()
        return jsonify({"error": "Thread not found"}), 404

    is_staff = g.user["role"] in ("admin", "moderator")

    if thread["user_id"] != g.user["id"] and not is_staff:
        connection.close()
        return jsonify({"error": "You can only edit your own threads"}), 403

    if thread["locked"] and not is_staff:
        connection.close()
        return jsonify({"error": "This thread is locked"}), 403

    title = (data.get("title", thread["title"]) or "").strip()
    content = (data.get("content", thread["content"]) or "").strip()

    if not title or not content:
        connection.close()
        return jsonify({"error": "Title and content cannot be empty"}), 400

    if len(title) > MAX_TITLE_LEN:
        connection.close()
        return jsonify({"error": f"Title must be at most {MAX_TITLE_LEN} characters"}), 400

    if len(content) > MAX_CONTENT_LEN:
        connection.close()
        return jsonify({"error": f"Content must be at most {MAX_CONTENT_LEN} characters"}), 400

    connection.execute(
        "UPDATE threads SET title = ?, content = ? WHERE id = ?",
        (title, content, thread_id)
    )
    connection.commit()
    connection.close()

    return jsonify({"message": "Thread updated", "content_html": render_markdown(content)})


@app.delete("/api/threads/<int:thread_id>")
@login_required
def delete_thread(thread_id):
    connection = db()

    thread = connection.execute(
        "SELECT * FROM threads WHERE id = ?",
        (thread_id,)
    ).fetchone()

    if not thread:
        connection.close()
        return jsonify({"error": "Thread not found"}), 404

    if thread["user_id"] != g.user["id"] and g.user["role"] not in ("admin", "moderator"):
        connection.close()
        return jsonify({"error": "You can only delete your own threads"}), 403

    connection.execute(
        "DELETE FROM reports WHERE thread_id = ? OR post_id IN (SELECT id FROM posts WHERE thread_id = ?)",
        (thread_id, thread_id)
    )
    connection.execute(
        "DELETE FROM likes WHERE post_id IN (SELECT id FROM posts WHERE thread_id = ?)",
        (thread_id,)
    )
    connection.execute("DELETE FROM posts WHERE thread_id = ?", (thread_id,))
    connection.execute("DELETE FROM threads WHERE id = ?", (thread_id,))
    connection.commit()
    connection.close()

    return jsonify({"message": "Thread deleted"})


@app.post("/api/threads/<int:thread_id>/pin")
@moderator_required
def pin_thread(thread_id):
    connection = db()

    thread = connection.execute(
        "SELECT pinned FROM threads WHERE id = ?",
        (thread_id,)
    ).fetchone()

    if not thread:
        connection.close()
        return jsonify({"error": "Thread not found"}), 404

    new_state = 0 if thread["pinned"] else 1
    connection.execute("UPDATE threads SET pinned = ? WHERE id = ?", (new_state, thread_id))
    connection.commit()
    connection.close()

    return jsonify({"pinned": bool(new_state)})


@app.post("/api/threads/<int:thread_id>/lock")
@moderator_required
def lock_thread(thread_id):
    connection = db()

    thread = connection.execute(
        "SELECT locked FROM threads WHERE id = ?",
        (thread_id,)
    ).fetchone()

    if not thread:
        connection.close()
        return jsonify({"error": "Thread not found"}), 404

    new_state = 0 if thread["locked"] else 1
    connection.execute("UPDATE threads SET locked = ? WHERE id = ?", (new_state, thread_id))
    connection.commit()
    connection.close()

    return jsonify({"locked": bool(new_state)})


# =========================================================
# POSTS (REPLIES)
# =========================================================

@app.post("/api/threads/<int:thread_id>/reply")
@login_required
def reply(thread_id):
    data = get_json_body()
    if data is None:
        return jsonify({"error": "Invalid JSON body"}), 400

    content = (data.get("content") or "").strip()
    quote_post_id = data.get("quote_post_id")

    if not content:
        return jsonify({"error": "Content is required"}), 400

    if len(content) > MAX_CONTENT_LEN:
        return jsonify({"error": f"Content must be at most {MAX_CONTENT_LEN} characters"}), 400

    connection = db()

    thread = connection.execute(
        "SELECT locked FROM threads WHERE id = ?",
        (thread_id,)
    ).fetchone()

    if not thread:
        connection.close()
        return jsonify({"error": "Thread not found"}), 404

    if thread["locked"] and g.user["role"] not in ("admin", "moderator"):
        connection.close()
        return jsonify({"error": "This thread is locked"}), 403

    if quote_post_id:
        quoted = connection.execute(
            "SELECT id FROM posts WHERE id = ? AND thread_id = ?",
            (quote_post_id, thread_id)
        ).fetchone()

        if not quoted:
            connection.close()
            return jsonify({"error": "Quoted post not found in this thread"}), 400

    cursor = connection.execute(
        """
        INSERT INTO posts (thread_id, content, user_id, quote_post_id)
        VALUES (?, ?, ?, ?)
        """,
        (thread_id, content, g.user["id"], quote_post_id)
    )
    connection.commit()

    post_id = cursor.lastrowid
    connection.close()

    return jsonify({"message": "Reply posted", "post_id": post_id}), 201


@app.put("/api/posts/<int:post_id>")
@login_required
def edit_post(post_id):
    data = get_json_body()
    if data is None:
        return jsonify({"error": "Invalid JSON body"}), 400

    content = (data.get("content") or "").strip()

    if not content:
        return jsonify({"error": "Content is required"}), 400

    if len(content) > MAX_CONTENT_LEN:
        return jsonify({"error": f"Content must be at most {MAX_CONTENT_LEN} characters"}), 400

    connection = db()

    post = connection.execute("SELECT * FROM posts WHERE id = ?", (post_id,)).fetchone()

    if not post:
        connection.close()
        return jsonify({"error": "Post not found"}), 404

    if post["user_id"] != g.user["id"]:
        connection.close()
        return jsonify({"error": "You can only edit your own posts"}), 403

    connection.execute("UPDATE posts SET content = ? WHERE id = ?", (content, post_id))
    connection.commit()
    connection.close()

    return jsonify({"message": "Post updated", "content_html": render_markdown(content)})


@app.delete("/api/posts/<int:post_id>")
@login_required
def delete_post(post_id):
    connection = db()

    post = connection.execute("SELECT * FROM posts WHERE id = ?", (post_id,)).fetchone()

    if not post:
        connection.close()
        return jsonify({"error": "Post not found"}), 404

    if post["user_id"] != g.user["id"] and g.user["role"] not in ("admin", "moderator"):
        connection.close()
        return jsonify({"error": "You can only delete your own posts"}), 403

    # Detach any replies that quoted this post so they don't dangle
    connection.execute(
        "UPDATE posts SET quote_post_id = NULL WHERE quote_post_id = ?", (post_id,)
    )
    connection.execute("DELETE FROM reports WHERE post_id = ?", (post_id,))
    connection.execute("DELETE FROM likes WHERE post_id = ?", (post_id,))
    connection.execute("DELETE FROM posts WHERE id = ?", (post_id,))
    connection.commit()
    connection.close()

    return jsonify({"message": "Post deleted"})


@app.post("/api/posts/<int:post_id>/like")
@login_required
def toggle_like(post_id):
    connection = db()

    post = connection.execute("SELECT id FROM posts WHERE id = ?", (post_id,)).fetchone()

    if not post:
        connection.close()
        return jsonify({"error": "Post not found"}), 404

    existing = connection.execute(
        "SELECT id FROM likes WHERE post_id = ? AND user_id = ?",
        (post_id, g.user["id"])
    ).fetchone()

    if existing:
        connection.execute("DELETE FROM likes WHERE id = ?", (existing["id"],))
        liked = False
    else:
        connection.execute(
            "INSERT INTO likes (post_id, user_id) VALUES (?, ?)",
            (post_id, g.user["id"])
        )
        liked = True

    connection.commit()

    like_count = connection.execute(
        "SELECT COUNT(*) AS c FROM likes WHERE post_id = ?",
        (post_id,)
    ).fetchone()["c"]

    connection.close()

    return jsonify({"liked": liked, "like_count": like_count})


# =========================================================
# SEARCH
# =========================================================

@app.get("/api/search")
def search():
    q = request.args.get("q", "").strip()

    if len(q) < 2:
        return jsonify({"error": "Query must be at least 2 characters"}), 400

    like = f"%{q}%"
    connection = db()

    threads_result = connection.execute("""
        SELECT threads.id, threads.title, threads.created_at, users.username
        FROM threads
        JOIN users ON users.id = threads.user_id
        WHERE threads.title LIKE ? OR threads.content LIKE ?
        ORDER BY threads.created_at DESC
        LIMIT 20
    """, (like, like)).fetchall()

    posts_result = connection.execute("""
        SELECT posts.id, posts.thread_id, posts.content, posts.created_at,
               users.username, threads.title AS thread_title
        FROM posts
        JOIN users ON users.id = posts.user_id
        JOIN threads ON threads.id = posts.thread_id
        WHERE posts.content LIKE ?
        ORDER BY posts.created_at DESC
        LIMIT 20
    """, (like,)).fetchall()

    connection.close()

    return jsonify({
        "threads": [dict(t) for t in threads_result],
        "posts": [dict(p) for p in posts_result]
    })


# =========================================================
# MODERATION: REPORTS
# =========================================================

@app.post("/api/reports")
@login_required
def create_report():
    data = get_json_body()
    if data is None:
        return jsonify({"error": "Invalid JSON body"}), 400

    post_id = data.get("post_id")
    thread_id = data.get("thread_id")
    reason = (data.get("reason") or "").strip()

    if not reason or (not post_id and not thread_id):
        return jsonify({"error": "reason and either post_id or thread_id are required"}), 400

    if len(reason) > MAX_REASON_LEN:
        return jsonify({"error": f"Reason must be at most {MAX_REASON_LEN} characters"}), 400

    connection = db()

    if post_id:
        exists = connection.execute("SELECT id FROM posts WHERE id = ?", (post_id,)).fetchone()
        if not exists:
            connection.close()
            return jsonify({"error": "Post not found"}), 404

    if thread_id:
        exists = connection.execute("SELECT id FROM threads WHERE id = ?", (thread_id,)).fetchone()
        if not exists:
            connection.close()
            return jsonify({"error": "Thread not found"}), 404

    connection.execute(
        """
        INSERT INTO reports (post_id, thread_id, reporter_id, reason)
        VALUES (?, ?, ?, ?)
        """,
        (post_id, thread_id, g.user["id"], reason)
    )
    connection.commit()
    connection.close()

    return jsonify({"message": "Report submitted"}), 201


@app.get("/api/reports")
@moderator_required
def list_reports():
    connection = db()

    rows = connection.execute("""
        SELECT reports.*, users.username AS reporter
        FROM reports
        JOIN users ON users.id = reports.reporter_id
        WHERE reports.status = 'open'
        ORDER BY reports.created_at DESC
    """).fetchall()

    connection.close()

    return jsonify([dict(r) for r in rows])


@app.put("/api/reports/<int:report_id>")
@moderator_required
def resolve_report(report_id):
    connection = db()

    report = connection.execute("SELECT id FROM reports WHERE id = ?", (report_id,)).fetchone()

    if not report:
        connection.close()
        return jsonify({"error": "Report not found"}), 404

    connection.execute("UPDATE reports SET status = 'resolved' WHERE id = ?", (report_id,))
    connection.commit()
    connection.close()

    return jsonify({"message": "Report resolved"})


# =========================================================
# CREW
# =========================================================

@app.get("/api/crew")
def crew():
    connection = db()

    rows = connection.execute("""
        SELECT id, username, role
        FROM users
        ORDER BY id ASC
    """).fetchall()

    connection.close()

    crew_list = []

    for row in rows:
        username = row["username"]
        initials = username[:2].upper()

        crew_list.append({
            "id": row["id"],
            "username": username,
            "initials": initials,
            "role": row["role"]
        })

    return jsonify(crew_list)


# =========================================================
# USER PROFILE
# =========================================================

@app.get("/api/users/<int:user_id>")
def get_profile(user_id):
    connection = db()

    user = connection.execute(
        """
        SELECT id, username, bio, role, joined_at
        FROM users
        WHERE id = ?
        """,
        (user_id,)
    ).fetchone()

    if not user:
        connection.close()
        return jsonify({"error": "User not found"}), 404

    recent_threads = connection.execute(
        """
        SELECT id, title, created_at
        FROM threads
        WHERE user_id = ?
        ORDER BY created_at DESC
        LIMIT 10
        """,
        (user_id,)
    ).fetchall()

    thread_count = connection.execute(
        "SELECT COUNT(*) AS c FROM threads WHERE user_id = ?",
        (user_id,)
    ).fetchone()["c"]

    post_count = connection.execute(
        "SELECT COUNT(*) AS c FROM posts WHERE user_id = ?",
        (user_id,)
    ).fetchone()["c"]

    connection.close()

    profile = dict(user)
    profile["thread_count"] = thread_count
    profile["post_count"] = post_count
    profile["recent_threads"] = [dict(t) for t in recent_threads]

    return jsonify(profile)


@app.put("/api/users/<int:user_id>")
@login_required
def edit_profile(user_id):
    if g.user["id"] != user_id:
        return jsonify({"error": "You can only edit your own profile"}), 403

    data = get_json_body()
    if data is None:
        return jsonify({"error": "Invalid JSON body"}), 400

    bio = (data.get("bio") or "").strip()

    if len(bio) > MAX_BIO_LEN:
        return jsonify({"error": f"Bio must be at most {MAX_BIO_LEN} characters"}), 400

    bio = sanitize_plain(bio)

    connection = db()
    connection.execute("UPDATE users SET bio = ? WHERE id = ?", (bio, user_id))
    connection.commit()
    connection.close()

    return jsonify({"message": "Profile updated"})


@app.put("/api/users/<int:user_id>/role")
@admin_required
def set_role(user_id):
    data = get_json_body()
    if data is None:
        return jsonify({"error": "Invalid JSON body"}), 400

    role = data.get("role")

    if role not in ("user", "moderator", "admin"):
        return jsonify({"error": "role must be one of: user, moderator, admin"}), 400

    connection = db()

    user = connection.execute("SELECT id FROM users WHERE id = ?", (user_id,)).fetchone()

    if not user:
        connection.close()
        return jsonify({"error": "User not found"}), 404

    connection.execute("UPDATE users SET role = ? WHERE id = ?", (role, user_id))
    connection.commit()
    connection.close()

    return jsonify({"message": f"Role updated to {role}"})


# =========================================================
# FRONT PAGE
# =========================================================

@app.route("/")
def home():
    return send_from_directory("..", "frontpage.html")


# =========================================================
# START
# =========================================================

if __name__ == "__main__":
    init_db()

    app.run(
        host="127.0.0.1",
        port=5000,
        debug=True
    )
