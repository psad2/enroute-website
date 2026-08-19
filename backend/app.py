import os
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

from flask_limiter import Limiter
from flask_limiter.util import get_remote_address
from werkzeug.middleware.proxy_fix import ProxyFix


# =========================================================
# APP
# =========================================================

app = Flask(__name__)

# flask rate limiter

app.wsgi_app = ProxyFix(
    app.wsgi_app,
    x_for=1,
    x_proto=1,
    x_host=1,
)

limiter = Limiter(
    key_func = get_remote_address,
    app = app,
    storage_uri = os.getenv(
        "RATELIMIT_STORAGE_URI = redis://127.0.0.1:6379/0",
        "memory://",
    ),
    strategy = "fixed+window",
    default_limits = [],
)

def login_username_key():
    data = request.get_json(silent = True)
    """
    Returns normalized username for login rate limiting.
    Prevents bypassing the limit through changing IPs
    """

    if not isinstance(data, dict):
        return "invalid-login"

    username = str(
        data.get("username") or ""
    ).strip().lower()

    if not username:
        return "empty-login-username"

    return username


BASE_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.abspath(os.path.join(BASE_DIR, ".."))
DATABASE = os.path.join(BASE_DIR, "forum.db")

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

ALLOWED_REACTIONS = {
    "like",
    "love",
    "laugh",
    "wow",
    "sad",
}

ALLOWED_TAGS = [
    "p",
    "br",
    "strong",
    "em",
    "u",
    "s",
    "code",
    "pre",
    "blockquote",
    "ul",
    "ol",
    "li",
    "a",
    "h1",
    "h2",
    "h3",
    "h4",
    "h5",
    "h6",
    "hr",
    "img",
]

ALLOWED_ATTRS = {
    "a": ["href", "title", "rel"],
    "img": ["src", "alt", "title"],
}

ALLOWED_PROTOCOLS = [
    "http",
    "https",
    "mailto",
]


# =========================================================
# DATABASE
# =========================================================

def db():
    connection = sqlite3.connect(
        DATABASE,
        timeout=10,
    )

    connection.row_factory = sqlite3.Row

    connection.execute("PRAGMA foreign_keys = ON")
    connection.execute("PRAGMA journal_mode = WAL")

    return connection


def column_exists(connection, table, column):
    rows = connection.execute(
        f"PRAGMA table_info({table})"
    ).fetchall()

    return any(row["name"] == column for row in rows)


def table_exists(connection, table):
    row = connection.execute(
        """
        SELECT name
        FROM sqlite_master
        WHERE type = 'table'
          AND name = ?
        """,
        (table,),
    ).fetchone()

    return row is not None


def init_db():
    connection = db()

    connection.executescript(
        """
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
            expires_at DATETIME NOT NULL,

            FOREIGN KEY(user_id)
                REFERENCES users(id)
                ON DELETE CASCADE
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
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP,

            FOREIGN KEY(user_id)
                REFERENCES users(id)
                ON DELETE CASCADE,

            FOREIGN KEY(category_id)
                REFERENCES categories(id)
        );

        CREATE TABLE IF NOT EXISTS posts (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            thread_id INTEGER NOT NULL,
            content TEXT NOT NULL,
            user_id INTEGER NOT NULL,
            parent_post_id INTEGER,
            quote_post_id INTEGER,
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP,

            FOREIGN KEY(thread_id)
                REFERENCES threads(id)
                ON DELETE CASCADE,

            FOREIGN KEY(user_id)
                REFERENCES users(id)
                ON DELETE CASCADE,

            FOREIGN KEY(parent_post_id)
                REFERENCES posts(id)
                ON DELETE SET NULL,

            FOREIGN KEY(quote_post_id)
                REFERENCES posts(id)
                ON DELETE SET NULL
        );

        CREATE TABLE IF NOT EXISTS reactions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            post_id INTEGER NOT NULL,
            user_id INTEGER NOT NULL,
            reaction TEXT NOT NULL,
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP,

            UNIQUE(post_id, user_id),

            CHECK (
                reaction IN (
                    'like',
                    'love',
                    'laugh',
                    'wow',
                    'sad'
                )
            ),

            FOREIGN KEY(post_id)
                REFERENCES posts(id)
                ON DELETE CASCADE,

            FOREIGN KEY(user_id)
                REFERENCES users(id)
                ON DELETE CASCADE
        );

        CREATE TABLE IF NOT EXISTS reports (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            post_id INTEGER,
            thread_id INTEGER,
            reporter_id INTEGER NOT NULL,
            reason TEXT NOT NULL,
            status TEXT DEFAULT 'open',
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP,

            FOREIGN KEY(post_id)
                REFERENCES posts(id)
                ON DELETE CASCADE,

            FOREIGN KEY(thread_id)
                REFERENCES threads(id)
                ON DELETE CASCADE,

            FOREIGN KEY(reporter_id)
                REFERENCES users(id)
                ON DELETE CASCADE
        );

        CREATE INDEX IF NOT EXISTS idx_threads_category
            ON threads(category_id);

        CREATE INDEX IF NOT EXISTS idx_threads_user
            ON threads(user_id);

        CREATE INDEX IF NOT EXISTS idx_threads_created
            ON threads(created_at);

        CREATE INDEX IF NOT EXISTS idx_posts_thread
            ON posts(thread_id);

        CREATE INDEX IF NOT EXISTS idx_posts_user
            ON posts(user_id);

        CREATE INDEX IF NOT EXISTS idx_posts_parent
            ON posts(parent_post_id);

        CREATE INDEX IF NOT EXISTS idx_posts_quote
            ON posts(quote_post_id);

        CREATE INDEX IF NOT EXISTS idx_sessions_token
            ON sessions(token);

        CREATE INDEX IF NOT EXISTS idx_sessions_expires
            ON sessions(expires_at);

        CREATE INDEX IF NOT EXISTS idx_reports_status
            ON reports(status);

        CREATE INDEX IF NOT EXISTS idx_reactions_post
            ON reactions(post_id);
        """
    )

    migrate_schema(connection)

    categories = [
        "General Operations",
        "Flight Operations",
        "Training & Standards",
        "Official Announcements",
        "Lounge",
        "Screenshots & Media",
        "Simulator & Hardware",
        "Help Desk",
    ]

    for category in categories:
        connection.execute(
            """
            INSERT OR IGNORE INTO categories (name)
            VALUES (?)
            """,
            (category,),
        )

    connection.commit()
    connection.close()


def migrate_schema(connection):
    """
    Adds missing columns to an existing forum.db.
    """

    migrations = [
        ("users", "bio", "TEXT DEFAULT ''"),
        ("users", "role", "TEXT DEFAULT 'user'"),
        ("users", "joined_at", "DATETIME"),

        ("threads", "pinned", "INTEGER DEFAULT 0"),
        ("threads", "locked", "INTEGER DEFAULT 0"),

        ("posts", "parent_post_id", "INTEGER"),
        ("posts", "quote_post_id", "INTEGER"),
    ]

    for table, column, definition in migrations:
        if table_exists(connection, table):
            if not column_exists(connection, table, column):
                connection.execute(
                    f"""
                    ALTER TABLE {table}
                    ADD COLUMN {column} {definition}
                    """
                )

    connection.execute(
        """
        UPDATE users
        SET joined_at = CURRENT_TIMESTAMP
        WHERE joined_at IS NULL
        """
    )

    connection.execute(
        """
        UPDATE users
        SET role = 'user'
        WHERE role IS NULL OR role = ''
        """
    )

    connection.execute(
        """
        UPDATE threads
        SET pinned = 0
        WHERE pinned IS NULL
        """
    )

    connection.execute(
        """
        UPDATE threads
        SET locked = 0
        WHERE locked IS NULL
        """
    )

    connection.commit()


# =========================================================
# MARKDOWN
# =========================================================

def render_markdown(raw_text):
    """
    Converts Markdown into safe HTML.

    Supports:
    - bold
    - italic
    - underline
    - strikethrough
    - headings
    - lists
    - links
    - code
    - blockquotes
    - images
    - line breaks
    """

    raw_text = raw_text or ""

    html = markdown.markdown(
        raw_text,
        extensions=[
            "extra",
            "nl2br",
            "sane_lists",
        ],
        output_format="html5",
    )

    return bleach.clean(
        html,
        tags=ALLOWED_TAGS,
        attributes=ALLOWED_ATTRS,
        protocols=ALLOWED_PROTOCOLS,
        strip=True,
    )


def sanitize_plain(text):
    return bleach.clean(
        text or "",
        tags=[],
        attributes={},
        strip=True,
    )


# =========================================================
# REQUEST HELPERS
# =========================================================

def get_json_body():
    data = request.get_json(silent=True)

    if not isinstance(data, dict):
        return None

    return data


def paginate_args():
    try:
        page = int(
            request.args.get(
                "page",
                1,
            )
        )
    except (TypeError, ValueError):
        page = 1

    page = max(1, page)

    raw_per_page = request.args.get(
        "per_page",
        request.args.get(
            "limit",
            PER_PAGE_DEFAULT,
        ),
    )

    try:
        per_page = int(raw_per_page)
    except (TypeError, ValueError):
        per_page = PER_PAGE_DEFAULT

    per_page = max(
        1,
        min(
            per_page,
            PER_PAGE_MAX,
        ),
    )

    offset = (page - 1) * per_page

    return page, per_page, offset


def public_user(user):
    data = dict(user)

    data.pop("password", None)

    return data

# =========================================================
# AUTH
# =========================================================

def cleanup_expired_sessions(connection):
    connection.execute(
        """
        DELETE FROM sessions
        WHERE expires_at <= ?
        """,
        (datetime.utcnow().isoformat(),),
    )


def create_session(user_id):
    token = secrets.token_urlsafe(48)

    expires_at = (
        datetime.utcnow()
        + timedelta(days=TOKEN_EXPIRY_DAYS)
    ).isoformat()

    connection = db()

    cleanup_expired_sessions(connection)

    connection.execute(
        """
        INSERT INTO sessions (
            user_id,
            token,
            expires_at
        )
        VALUES (?, ?, ?)
        """,
        (
            user_id,
            token,
            expires_at,
        ),
    )

    connection.commit()
    connection.close()

    return token


def get_user_from_token(token):
    connection = db()

    user = connection.execute(
        """
        SELECT users.*
        FROM sessions
        INNER JOIN users
            ON users.id = sessions.user_id
        WHERE sessions.token = ?
          AND sessions.expires_at > ?
        """,
        (
            token,
            datetime.utcnow().isoformat(),
        ),
    ).fetchone()

    connection.close()

    return user


def login_required(function):
    @wraps(function)
    def wrapper(*args, **kwargs):
        auth_header = request.headers.get(
            "Authorization",
            "",
        )

        if not auth_header.startswith("Bearer "):
            return jsonify({
                "error": "Authentication required",
            }), 401

        token = auth_header[7:].strip()

        if not token:
            return jsonify({
                "error": "Authentication required",
            }), 401

        user = get_user_from_token(token)

        if not user:
            return jsonify({
                "error": "Invalid or expired session",
            }), 401

        g.user = user
        g.token = token

        return function(*args, **kwargs)

    return wrapper


def role_required(*roles):
    def decorator(function):
        @wraps(function)
        @login_required
        def wrapper(*args, **kwargs):
            if g.user["role"] not in roles:
                return jsonify({
                    "error": "Insufficient permissions",
                }), 403

            return function(*args, **kwargs)

        return wrapper

    return decorator


moderator_required = role_required(
    "admin",
    "moderator",
)

admin_required = role_required(
    "admin",
)


# =========================================================
# REACTIONS
# =========================================================

def empty_reaction_counts():
    return {
        "like": 0,
        "love": 0,
        "laugh": 0,
        "wow": 0,
        "sad": 0,
    }


def get_reaction_counts(connection, post_id):
    counts = empty_reaction_counts()

    rows = connection.execute(
        """
        SELECT
            reaction,
            COUNT(*) AS count
        FROM reactions
        WHERE post_id = ?
        GROUP BY reaction
        """,
        (post_id,),
    ).fetchall()

    for row in rows:
        if row["reaction"] in counts:
            counts[row["reaction"]] = row["count"]

    return counts


def get_user_reaction(connection, post_id, user_id):
    if not user_id:
        return None

    row = connection.execute(
        """
        SELECT reaction
        FROM reactions
        WHERE post_id = ?
          AND user_id = ?
        """,
        (
            post_id,
            user_id,
        ),
    ).fetchone()

    if not row:
        return None

    return row["reaction"]


def serialize_post(connection, post, user_id=None):
    result = dict(post)

    result["content_html"] = render_markdown(
        result.get("content", ""),
    )

    result["reactions"] = get_reaction_counts(
        connection,
        result["id"],
    )

    result["user_reaction"] = get_user_reaction(
        connection,
        result["id"],
        user_id,
    )

    return result


# =========================================================
# REGISTER
# =========================================================

@app.post("/api/register")
#login limiting
@limiter.limit(
    "5 per minute",
    error_message="Too many registration attempts. Please try again later.",
)
@limiter.limit(
    "20 per hour",
    error_message="Too many registration attempts. Please try again later.",
)
def register():
    data = get_json_body()

    if data is None:
        return jsonify({
            "error": "Invalid JSON body",
        }), 400

    username = str(
        data.get("username") or ""
    ).strip()

    email = str(
        data.get("email") or ""
    ).strip().lower()

    password = data.get("password") or ""

    if not username or not email or not password:
        return jsonify({
            "error": "All fields are required",
        }), 400

    if len(username) > MAX_USERNAME_LEN:
        return jsonify({
            "error": "Username is too long",
        }), 400

    if not USERNAME_RE.fullmatch(username):
        return jsonify({
            "error":
                "Username must be 3-30 characters, "
                "letters/numbers/underscore only",
        }), 400

    if len(email) > MAX_EMAIL_LEN:
        return jsonify({
            "error": "Email address is too long",
        }), 400

    if not EMAIL_RE.fullmatch(email):
        return jsonify({
            "error": "Invalid email address",
        }), 400

    if len(password) < 8:
        return jsonify({
            "error":
                "Password must contain at least 8 characters",
        }), 400

    if len(password) > MAX_PASSWORD_LEN:
        return jsonify({
            "error":
                f"Password must be at most "
                f"{MAX_PASSWORD_LEN} characters",
        }), 400

    connection = db()

    existing_username = connection.execute(
        """
        SELECT id
        FROM users
        WHERE LOWER(username) = LOWER(?)
        """,
        (username,),
    ).fetchone()

    if existing_username:
        connection.close()

        return jsonify({
            "error": "Username already exists",
        }), 409

    existing_email = connection.execute(
        """
        SELECT id
        FROM users
        WHERE LOWER(email) = LOWER(?)
        """,
        (email,),
    ).fetchone()

    if existing_email:
        connection.close()

        return jsonify({
            "error": "Email already exists",
        }), 409

    password_hash = generate_password_hash(password)

    connection.execute(
        """
        INSERT INTO users (
            username,
            email,
            password,
            bio,
            role
        )
        VALUES (?, ?, ?, '', 'user')
        """,
        (
            username,
            email,
            password_hash,
        ),
    )

    connection.commit()
    connection.close()

    return jsonify({
        "message": "Account created",
    }), 201

# adds error 429 too many requests
@app.errorhandler(429)
def rate_limit_exceeded(error):
    if request.path.startswith("/api/"):
        retry_after = getattr(
            error,
            "description",
            "Too many requests. Please try again later.",
        )

        return jsonify({
            "error": str(retry_after),
        }), 429

    return error


# =========================================================
# LOGIN
# =========================================================

@app.post("/api/login")
@limiter.limit(
    "10 per minute",
    error_message="Too many login attempts. Please try again later.",
)
@limiter.limit(
    "5 per minute",
    key_func=login_username_key,
    error_message="Too many login attempts for this account. Please try again later.",
)
def login():
    data = get_json_body()

    if data is None:
        return jsonify({
            "error": "Invalid JSON body",
        }), 400

    username = str(
        data.get("username") or ""
    ).strip()

    password = data.get("password") or ""

    if not username or not password:
        return jsonify({
            "error": "Username and password are required",
        }), 400

    connection = db()

    user = connection.execute(
        """
        SELECT *
        FROM users
        WHERE LOWER(username) = LOWER(?)
        """,
        (username,),
    ).fetchone()

    connection.close()

    if not user:
        return jsonify({
            "error": "Invalid username or password",
        }), 401

    try:
        valid_password = check_password_hash(
            user["password"],
            password,
        )
    except Exception:
        valid_password = False

    if not valid_password:
        return jsonify({
            "error": "Invalid username or password",
        }), 401

    token = create_session(user["id"])

    return jsonify({
        "message": "Login successful",
        "token": token,
        "user": public_user(user),
    })


# =========================================================
# LOGOUT
# =========================================================

@app.post("/api/logout")
@login_required
def logout():
    connection = db()

    connection.execute(
        """
        DELETE FROM sessions
        WHERE token = ?
        """,
        (g.token,),
    )

    connection.commit()
    connection.close()

    return jsonify({
        "message": "Logged out",
    })


# =========================================================
# ME
# =========================================================

@app.get("/api/me")
@login_required
def me():
    return jsonify(
        public_user(g.user)
    )


# =========================================================
# CATEGORIES
# =========================================================

@app.get("/api/categories")
def categories():
    connection = db()

    rows = connection.execute(
        """
        SELECT id, name
        FROM categories
        ORDER BY name ASC
        """
    ).fetchall()

    connection.close()

    return jsonify([
        dict(row)
        for row in rows
    ])


# =========================================================
# THREAD LIST
# =========================================================

@app.get("/api/threads")
def threads():
    page, per_page, offset = paginate_args()

    category_id = request.args.get(
        "category_id"
    )

    q = request.args.get(
        "q",
        "",
    ).strip()

    connection = db()

    where = ["1 = 1"]
    params = []

    if category_id:
        try:
            category_id = int(category_id)
        except ValueError:
            return jsonify({
                "error": "Invalid category_id",
            }), 400

        where.append(
            "threads.category_id = ?"
        )

        params.append(category_id)

    if q:
        where.append(
            """
            (
                threads.title LIKE ?
                OR threads.content LIKE ?
            )
            """
        )

        like = f"%{q}%"

        params.extend([
            like,
            like,
        ])

    where_clause = " AND ".join(where)

    total = connection.execute(
        f"""
        SELECT COUNT(*) AS count
        FROM threads
        WHERE {where_clause}
        """,
        params,
    ).fetchone()["count"]

    rows = connection.execute(
        f"""
        SELECT
            threads.id,
            threads.title,
            threads.content,
            threads.created_at,
            threads.user_id,
            threads.category_id,
            threads.pinned,
            threads.locked,

            users.username,

            categories.name AS category,

            (
                SELECT COUNT(*)
                FROM posts
                WHERE posts.thread_id = threads.id
                  AND posts.id != (
                      SELECT MIN(p2.id)
                      FROM posts p2
                      WHERE p2.thread_id = threads.id
                  )
            ) AS reply_count

        FROM threads

        INNER JOIN users
            ON users.id = threads.user_id

        INNER JOIN categories
            ON categories.id = threads.category_id

        WHERE {where_clause}

        ORDER BY
            threads.pinned DESC,
            threads.created_at DESC,
            threads.id DESC

        LIMIT ?
        OFFSET ?
        """,
        params + [
            per_page,
            offset,
        ],
    ).fetchall()

    result = []

    for row in rows:
        item = dict(row)

        item["content_html"] = render_markdown(
            item["content"]
        )

        result.append(item)

    connection.close()

    return jsonify({
        "page": page,
        "per_page": per_page,
        "limit": per_page,
        "total": total,
        "total_pages": max(
            1,
            math.ceil(total / per_page),
        ),
        "threads": result,
    })


# =========================================================
# CREATE THREAD
# =========================================================

@app.post("/api/threads")
@login_required
def create_thread():
    data = get_json_body()

    if data is None:
        return jsonify({
            "error": "Invalid JSON body",
        }), 400

    title = str(
        data.get("title") or ""
    ).strip()

    content = str(
        data.get("content") or ""
    ).strip()

    category_id = data.get(
        "category_id"
    )

    if not title or not content or category_id is None:
        return jsonify({
            "error":
                "Title, content and category_id are required",
        }), 400

    if len(title) > MAX_TITLE_LEN:
        return jsonify({
            "error":
                f"Title must be at most "
                f"{MAX_TITLE_LEN} characters",
        }), 400

    if len(content) > MAX_CONTENT_LEN:
        return jsonify({
            "error":
                f"Content must be at most "
                f"{MAX_CONTENT_LEN} characters",
        }), 400

    try:
        category_id = int(category_id)
    except (TypeError, ValueError):
        return jsonify({
            "error": "Invalid category_id",
        }), 400

    connection = db()

    category = connection.execute(
        """
        SELECT id
        FROM categories
        WHERE id = ?
        """,
        (category_id,),
    ).fetchone()

    if not category:
        connection.close()

        return jsonify({
            "error": "Category not found",
        }), 404

    cursor = connection.execute(
        """
        INSERT INTO threads (
            title,
            content,
            user_id,
            category_id,
            pinned,
            locked
        )
        VALUES (?, ?, ?, ?, 0, 0)
        """,
        (
            title,
            content,
            g.user["id"],
            category_id,
        ),
    )

    thread_id = cursor.lastrowid

    # The first post is the actual thread content.
    connection.execute(
        """
        INSERT INTO posts (
            thread_id,
            content,
            user_id,
            parent_post_id,
            quote_post_id
        )
        VALUES (?, ?, ?, NULL, NULL)
        """,
        (
            thread_id,
            content,
            g.user["id"],
        ),
    )

    connection.commit()
    connection.close()

    return jsonify({
        "message": "Thread created",
        "thread_id": thread_id,
    }), 201


# =========================================================
# GET THREAD
# =========================================================

@app.get("/api/threads/<int:thread_id>")
def get_thread(thread_id):
    page, per_page, offset = paginate_args()

    connection = db()

    thread = connection.execute(
        """
        SELECT
            threads.*,
            users.username,
            categories.name AS category

        FROM threads

        INNER JOIN users
            ON users.id = threads.user_id

        INNER JOIN categories
            ON categories.id = threads.category_id

        WHERE threads.id = ?
        """,
        (thread_id,),
    ).fetchone()

    if not thread:
        connection.close()

        return jsonify({
            "error": "Thread not found",
        }), 404

    first_post = connection.execute(
        """
        SELECT MIN(id) AS first_id
        FROM posts
        WHERE thread_id = ?
        """,
        (thread_id,),
    ).fetchone()

    first_post_id = (
        first_post["first_id"]
        if first_post and first_post["first_id"]
        else None
    )

    total_posts = connection.execute(
        """
        SELECT COUNT(*) AS count
        FROM posts
        WHERE thread_id = ?
          AND (? IS NULL OR id != ?)
        """,
        (
            thread_id,
            first_post_id,
            first_post_id,
        ),
    ).fetchone()["count"]

    rows = connection.execute(
        """
        SELECT
            posts.*,
            users.username

        FROM posts

        INNER JOIN users
            ON users.id = posts.user_id

        WHERE posts.thread_id = ?
          AND (? IS NULL OR posts.id != ?)

        ORDER BY
            posts.created_at ASC,
            posts.id ASC

        LIMIT ?
        OFFSET ?
        """,
        (
            thread_id,
            first_post_id,
            first_post_id,
            per_page,
            offset,
        ),
    ).fetchall()

    posts = []

    for row in rows:
        post = serialize_post(
            connection,
            row,
        )

        quote_id = post.get(
            "quote_post_id"
        )

        if quote_id:
            quoted = connection.execute(
                """
                SELECT
                    posts.id,
                    posts.thread_id,
                    posts.content,
                    posts.created_at,
                    posts.user_id,
                    users.username

                FROM posts

                INNER JOIN users
                    ON users.id = posts.user_id

                WHERE posts.id = ?
                  AND posts.thread_id = ?
                """,
                (
                    quote_id,
                    thread_id,
                ),
            ).fetchone()

            if quoted:
                quoted_data = dict(quoted)

                quoted_data["content_html"] = render_markdown(
                    quoted_data["content"]
                )

                post["quoted_post"] = quoted_data
            else:
                post["quoted_post"] = None

        parent_id = post.get(
            "parent_post_id"
        )

        if parent_id:
            parent = connection.execute(
                """
                SELECT
                    posts.id,
                    posts.content,
                    posts.user_id,
                    users.username

                FROM posts

                INNER JOIN users
                    ON users.id = posts.user_id

                WHERE posts.id = ?
                  AND posts.thread_id = ?
                """,
                (
                    parent_id,
                    thread_id,
                ),
            ).fetchone()

            if parent:
                parent_data = dict(parent)

                parent_data["content_html"] = render_markdown(
                    parent_data["content"]
                )

                post["parent_post"] = parent_data
            else:
                post["parent_post"] = None

        posts.append(post)

    thread_data = dict(thread)

    thread_data["content_html"] = render_markdown(
        thread_data["content"]
    )

    thread_data["first_post_id"] = first_post_id

    if first_post_id:
        thread_data["reactions"] = get_reaction_counts(
            connection,
            first_post_id,
        )
    else:
        thread_data["reactions"] = empty_reaction_counts()

    connection.close()

    return jsonify({
        "thread": thread_data,
        "posts": posts,
        "page": page,
        "per_page": per_page,
        "limit": per_page,
        "total_posts": total_posts,
        "total_pages": max(
            1,
            math.ceil(
                total_posts / per_page
            ),
        ),
    })


# =========================================================
# REPLY
# =========================================================

@app.post("/api/threads/<int:thread_id>/reply")
@login_required
def reply(thread_id):
    data = get_json_body()

    if data is None:
        return jsonify({
            "error": "Invalid JSON body",
        }), 400

    content = str(
        data.get("content") or ""
    ).strip()

    quote_post_id = data.get(
        "quote_post_id"
    )

    parent_post_id = data.get(
        "parent_post_id"
    )

    if not content:
        return jsonify({
            "error": "Content is required",
        }), 400

    if len(content) > MAX_CONTENT_LEN:
        return jsonify({
            "error":
                f"Content must be at most "
                f"{MAX_CONTENT_LEN} characters",
        }), 400

    connection = db()

    thread = connection.execute(
        """
        SELECT id, locked
        FROM threads
        WHERE id = ?
        """,
        (thread_id,),
    ).fetchone()

    if not thread:
        connection.close()

        return jsonify({
            "error": "Thread not found",
        }), 404

    if (
        thread["locked"]
        and g.user["role"] not in (
            "admin",
            "moderator",
        )
    ):
        connection.close()

        return jsonify({
            "error": "This thread is locked",
        }), 403

    if parent_post_id is not None:
        try:
            parent_post_id = int(parent_post_id)
        except (TypeError, ValueError):
            connection.close()

            return jsonify({
                "error": "Invalid parent_post_id",
            }), 400

        parent = connection.execute(
            """
            SELECT id
            FROM posts
            WHERE id = ?
              AND thread_id = ?
            """,
            (
                parent_post_id,
                thread_id,
            ),
        ).fetchone()

        if not parent:
            connection.close()

            return jsonify({
                "error":
                    "Parent post not found in this thread",
            }), 400

    if quote_post_id is not None:
        try:
            quote_post_id = int(quote_post_id)
        except (TypeError, ValueError):
            connection.close()

            return jsonify({
                "error": "Invalid quote_post_id",
            }), 400

        quoted = connection.execute(
            """
            SELECT id
            FROM posts
            WHERE id = ?
              AND thread_id = ?
            """,
            (
                quote_post_id,
                thread_id,
            ),
        ).fetchone()

        if not quoted:
            connection.close()

            return jsonify({
                "error":
                    "Quoted post not found in this thread",
            }), 400

    cursor = connection.execute(
        """
        INSERT INTO posts (
            thread_id,
            content,
            user_id,
            parent_post_id,
            quote_post_id
        )
        VALUES (?, ?, ?, ?, ?)
        """,
        (
            thread_id,
            content,
            g.user["id"],
            parent_post_id,
            quote_post_id,
        ),
    )

    post_id = cursor.lastrowid

    connection.commit()

    post = connection.execute(
        """
        SELECT
            posts.*,
            users.username

        FROM posts

        INNER JOIN users
            ON users.id = posts.user_id

        WHERE posts.id = ?
        """,
        (post_id,),
    ).fetchone()

    post_data = serialize_post(
        connection,
        post,
        g.user["id"],
    )

    if quote_post_id:
        quoted = connection.execute(
            """
            SELECT
                posts.id,
                posts.thread_id,
                posts.content,
                posts.created_at,
                posts.user_id,
                users.username

            FROM posts

            INNER JOIN users
                ON users.id = posts.user_id

            WHERE posts.id = ?
            """,
            (quote_post_id,),
        ).fetchone()

        if quoted:
            quoted_data = dict(quoted)

            quoted_data["content_html"] = render_markdown(
                quoted_data["content"]
            )

            post_data["quoted_post"] = quoted_data

    connection.close()

    return jsonify({
        "message": "Reply posted",
        "post": post_data,
    }), 201


# =========================================================
# EDIT THREAD
# =========================================================

@app.put("/api/threads/<int:thread_id>")
@login_required
def edit_thread(thread_id):
    data = get_json_body()

    if data is None:
        return jsonify({
            "error": "Invalid JSON body",
        }), 400

    connection = db()

    thread = connection.execute(
        """
        SELECT *
        FROM threads
        WHERE id = ?
        """,
        (thread_id,),
    ).fetchone()

    if not thread:
        connection.close()

        return jsonify({
            "error": "Thread not found",
        }), 404

    is_staff = g.user["role"] in (
        "admin",
        "moderator",
    )

    if (
        thread["user_id"] != g.user["id"]
        and not is_staff
    ):
        connection.close()

        return jsonify({
            "error":
                "You can only edit your own threads",
        }), 403

    if thread["locked"] and not is_staff:
        connection.close()

        return jsonify({
            "error": "This thread is locked",
        }), 403

    title = str(
        data.get(
            "title",
            thread["title"],
        ) or ""
    ).strip()

    content = str(
        data.get(
            "content",
            thread["content"],
        ) or ""
    ).strip()

    if not title or not content:
        connection.close()

        return jsonify({
            "error":
                "Title and content cannot be empty",
        }), 400

    if len(title) > MAX_TITLE_LEN:
        connection.close()

        return jsonify({
            "error":
                f"Title must be at most "
                f"{MAX_TITLE_LEN} characters",
        }), 400

    if len(content) > MAX_CONTENT_LEN:
        connection.close()

        return jsonify({
            "error":
                f"Content must be at most "
                f"{MAX_CONTENT_LEN} characters",
        }), 400

    connection.execute(
        """
        UPDATE threads
        SET title = ?,
            content = ?
        WHERE id = ?
        """,
        (
            title,
            content,
            thread_id,
        ),
    )

    first_post = connection.execute(
        """
        SELECT MIN(id) AS first_id
        FROM posts
        WHERE thread_id = ?
        """,
        (thread_id,),
    ).fetchone()

    if first_post and first_post["first_id"]:
        connection.execute(
            """
            UPDATE posts
            SET content = ?
            WHERE id = ?
            """,
            (
                content,
                first_post["first_id"],
            ),
        )

    connection.commit()
    connection.close()

    return jsonify({
        "message": "Thread updated",
        "title": title,
        "content": content,
        "content_html": render_markdown(content),
    })


# =========================================================
# DELETE THREAD
# =========================================================

@app.delete("/api/threads/<int:thread_id>")
@login_required
def delete_thread(thread_id):
    connection = db()

    thread = connection.execute(
        """
        SELECT *
        FROM threads
        WHERE id = ?
        """,
        (thread_id,),
    ).fetchone()

    if not thread:
        connection.close()

        return jsonify({
            "error": "Thread not found",
        }), 404

    if (
        thread["user_id"] != g.user["id"]
        and g.user["role"] not in (
            "admin",
            "moderator",
        )
    ):
        connection.close()

        return jsonify({
            "error":
                "You can only delete your own threads",
        }), 403

    connection.execute(
        """
        DELETE FROM reactions
        WHERE post_id IN (
            SELECT id
            FROM posts
            WHERE thread_id = ?
        )
        """,
        (thread_id,),
    )

    connection.execute(
        """
        DELETE FROM reports
        WHERE thread_id = ?
           OR post_id IN (
                SELECT id
                FROM posts
                WHERE thread_id = ?
           )
        """,
        (
            thread_id,
            thread_id,
        ),
    )

    connection.execute(
        """
        DELETE FROM posts
        WHERE thread_id = ?
        """,
        (thread_id,),
    )

    connection.execute(
        """
        DELETE FROM threads
        WHERE id = ?
        """,
        (thread_id,),
    )

    connection.commit()
    connection.close()

    return jsonify({
        "message": "Thread deleted",
    })


# =========================================================
# PIN THREAD
# =========================================================

@app.post("/api/threads/<int:thread_id>/pin")
@moderator_required
def pin_thread(thread_id):
    connection = db()

    thread = connection.execute(
        """
        SELECT pinned
        FROM threads
        WHERE id = ?
        """,
        (thread_id,),
    ).fetchone()

    if not thread:
        connection.close()

        return jsonify({
            "error": "Thread not found",
        }), 404

    new_state = 0 if thread["pinned"] else 1

    connection.execute(
        """
        UPDATE threads
        SET pinned = ?
        WHERE id = ?
        """,
        (
            new_state,
            thread_id,
        ),
    )

    connection.commit()
    connection.close()

    return jsonify({
        "pinned": bool(new_state),
    })


# =========================================================
# LOCK THREAD
# =========================================================

@app.post("/api/threads/<int:thread_id>/lock")
@moderator_required
def lock_thread(thread_id):
    connection = db()

    thread = connection.execute(
        """
        SELECT locked
        FROM threads
        WHERE id = ?
        """,
        (thread_id,),
    ).fetchone()

    if not thread:
        connection.close()

        return jsonify({
            "error": "Thread not found",
        }), 404

    new_state = 0 if thread["locked"] else 1

    connection.execute(
        """
        UPDATE threads
        SET locked = ?
        WHERE id = ?
        """,
        (
            new_state,
            thread_id,
        ),
    )

    connection.commit()
    connection.close()

    return jsonify({
        "locked": bool(new_state),
    })


# =========================================================
# EDIT POST
# =========================================================

@app.put("/api/posts/<int:post_id>")
@login_required
def edit_post(post_id):
    data = get_json_body()

    if data is None:
        return jsonify({
            "error": "Invalid JSON body",
        }), 400

    content = str(
        data.get("content") or ""
    ).strip()

    if not content:
        return jsonify({
            "error": "Content is required",
        }), 400

    if len(content) > MAX_CONTENT_LEN:
        return jsonify({
            "error":
                f"Content must be at most "
                f"{MAX_CONTENT_LEN} characters",
        }), 400

    connection = db()

    post = connection.execute(
        """
        SELECT *
        FROM posts
        WHERE id = ?
        """,
        (post_id,),
    ).fetchone()

    if not post:
        connection.close()

        return jsonify({
            "error": "Post not found",
        }), 404

    if (
        post["user_id"] != g.user["id"]
        and g.user["role"] not in (
            "admin",
            "moderator",
        )
    ):
        connection.close()

        return jsonify({
            "error":
                "You can only edit your own posts",
        }), 403

    thread = connection.execute(
        """
        SELECT locked
        FROM threads
        WHERE id = ?
        """,
        (post["thread_id"],),
    ).fetchone()

    if (
        thread
        and thread["locked"]
        and g.user["role"] not in (
            "admin",
            "moderator",
        )
    ):
        connection.close()

        return jsonify({
            "error": "This thread is locked",
        }), 403

    connection.execute(
        """
        UPDATE posts
        SET content = ?
        WHERE id = ?
        """,
        (
            content,
            post_id,
        ),
    )

    # Keep the thread's original content synchronized.
    first_post = connection.execute(
        """
        SELECT MIN(id) AS first_id
        FROM posts
        WHERE thread_id = ?
        """,
        (post["thread_id"],),
    ).fetchone()

    if (
        first_post
        and first_post["first_id"] == post_id
    ):
        connection.execute(
            """
            UPDATE threads
            SET content = ?
            WHERE id = ?
            """,
            (
                content,
                post["thread_id"],
            ),
        )

    connection.commit()
    connection.close()

    return jsonify({
        "message": "Post updated",
        "content": content,
        "content_html": render_markdown(content),
    })


# =========================================================
# DELETE POST
# =========================================================

@app.delete("/api/posts/<int:post_id>")
@login_required
def delete_post(post_id):
    connection = db()

    post = connection.execute(
        """
        SELECT *
        FROM posts
        WHERE id = ?
        """,
        (post_id,),
    ).fetchone()

    if not post:
        connection.close()

        return jsonify({
            "error": "Post not found",
        }), 404

    if (
        post["user_id"] != g.user["id"]
        and g.user["role"] not in (
            "admin",
            "moderator",
        )
    ):
        connection.close()

        return jsonify({
            "error":
                "You can only delete your own posts",
        }), 403

    # Never allow deletion of the first post
    # because it represents the thread itself.
    first_post = connection.execute(
        """
        SELECT MIN(id) AS first_id
        FROM posts
        WHERE thread_id = ?
        """,
        (post["thread_id"],),
    ).fetchone()

    if (
        first_post
        and first_post["first_id"] == post_id
    ):
        connection.close()

        return jsonify({
            "error":
                "The original thread post cannot be deleted",
        }), 400

    connection.execute(
        """
        UPDATE posts
        SET quote_post_id = NULL,
            parent_post_id = NULL
        WHERE quote_post_id = ?
           OR parent_post_id = ?
        """,
        (
            post_id,
            post_id,
        ),
    )

    connection.execute(
        """
        DELETE FROM reactions
        WHERE post_id = ?
        """,
        (post_id,),
    )

    connection.execute(
        """
        DELETE FROM reports
        WHERE post_id = ?
        """,
        (post_id,),
    )

    connection.execute(
        """
        DELETE FROM posts
        WHERE id = ?
        """,
        (post_id,),
    )

    connection.commit()
    connection.close()

    return jsonify({
        "message": "Post deleted",
    })


# =========================================================
# REACTIONS
# =========================================================

@app.post("/api/posts/<int:post_id>/reaction")
@login_required
def react_to_post(post_id):
    data = get_json_body()

    if data is None:
        return jsonify({
            "error": "Invalid JSON body",
        }), 400

    reaction = str(
        data.get("reaction") or ""
    ).strip().lower()

    if reaction not in ALLOWED_REACTIONS:
        return jsonify({
            "error": "Invalid reaction",
        }), 400

    connection = db()

    post = connection.execute(
        """
        SELECT id, thread_id
        FROM posts
        WHERE id = ?
        """,
        (post_id,),
    ).fetchone()

    if not post:
        connection.close()

        return jsonify({
            "error": "Post not found",
        }), 404

    existing = connection.execute(
        """
        SELECT id, reaction
        FROM reactions
        WHERE post_id = ?
          AND user_id = ?
        """,
        (
            post_id,
            g.user["id"],
        ),
    ).fetchone()

    if existing:
        if existing["reaction"] == reaction:
            # Clicking the same reaction again removes it.
            connection.execute(
                """
                DELETE FROM reactions
                WHERE id = ?
                """,
                (existing["id"],),
            )

            active_reaction = None

        else:
            # Clicking another reaction switches it.
            connection.execute(
                """
                UPDATE reactions
                SET reaction = ?,
                    created_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                (
                    reaction,
                    existing["id"],
                ),
            )

            active_reaction = reaction

    else:
        connection.execute(
            """
            INSERT INTO reactions (
                post_id,
                user_id,
                reaction
            )
            VALUES (?, ?, ?)
            """,
            (
                post_id,
                g.user["id"],
                reaction,
            ),
        )

        active_reaction = reaction

    connection.commit()

    counts = get_reaction_counts(
        connection,
        post_id,
    )

    connection.close()

    return jsonify({
        "success": True,
        "reaction": active_reaction,
        "user_reaction": active_reaction,
        "counts": counts,
    })


# =========================================================
# SEARCH
# =========================================================

@app.get("/api/search")
def search():
    q = request.args.get(
        "q",
        "",
    ).strip()

    if len(q) < 2:
        return jsonify({
            "error":
                "Query must be at least 2 characters",
        }), 400

    page, per_page, offset = paginate_args()

    like = f"%{q}%"

    connection = db()

    threads = connection.execute(
        """
        SELECT
            threads.id,
            threads.title,
            threads.content,
            threads.created_at,
            threads.user_id,
            users.username,
            categories.name AS category

        FROM threads

        INNER JOIN users
            ON users.id = threads.user_id

        INNER JOIN categories
            ON categories.id = threads.category_id

        WHERE
            threads.title LIKE ?
            OR threads.content LIKE ?

        ORDER BY
            threads.pinned DESC,
            threads.created_at DESC

        LIMIT ?
        OFFSET ?
        """,
        (
            like,
            like,
            per_page,
            offset,
        ),
    ).fetchall()

    posts = connection.execute(
        """
        SELECT
            posts.id,
            posts.thread_id,
            posts.content,
            posts.created_at,
            posts.user_id,
            users.username,
            threads.title AS thread_title

        FROM posts

        INNER JOIN users
            ON users.id = posts.user_id

        INNER JOIN threads
            ON threads.id = posts.thread_id

        WHERE posts.content LIKE ?

        ORDER BY
            posts.created_at DESC

        LIMIT ?
        OFFSET ?
        """,
        (
            like,
            per_page,
            offset,
        ),
    ).fetchall()

    thread_results = []

    for row in threads:
        item = dict(row)

        item["content_html"] = render_markdown(
            item["content"]
        )

        thread_results.append(item)

    post_results = []

    for row in posts:
        item = dict(row)

        item["content_html"] = render_markdown(
            item["content"]
        )

        post_results.append(item)

    connection.close()

    return jsonify({
        "query": q,
        "page": page,
        "per_page": per_page,
        "limit": per_page,
        "threads": thread_results,
        "posts": post_results,
    })


# =========================================================
# REPORT SINGLE POST
# =========================================================

@app.post("/api/posts/<int:post_id>/report")
@login_required
def report_post(post_id):
    data = get_json_body()

    if data is None:
        return jsonify({
            "error": "Invalid JSON body",
        }), 400

    reason = str(
        data.get("reason") or ""
    ).strip()

    if not reason:
        return jsonify({
            "error": "A report reason is required.",
        }), 400

    if len(reason) > MAX_REASON_LEN:
        return jsonify({
            "error":
                f"Reason must be at most "
                f"{MAX_REASON_LEN} characters",
        }), 400

    connection = db()

    post = connection.execute(
        """
        SELECT id, thread_id
        FROM posts
        WHERE id = ?
        """,
        (post_id,),
    ).fetchone()

    if not post:
        connection.close()

        return jsonify({
            "error": "Post not found.",
        }), 404

    existing = connection.execute(
        """
        SELECT id
        FROM reports
        WHERE post_id = ?
          AND reporter_id = ?
          AND status = 'open'
        """,
        (
            post_id,
            g.user["id"],
        ),
    ).fetchone()

    if existing:
        connection.close()

        return jsonify({
            "error":
                "You have already reported this post.",
        }), 409

    connection.execute(
        """
        INSERT INTO reports (
            post_id,
            thread_id,
            reporter_id,
            reason,
            status
        )
        VALUES (?, ?, ?, ?, 'open')
        """,
        (
            post_id,
            post["thread_id"],
            g.user["id"],
            reason,
        ),
    )

    connection.commit()
    connection.close()

    return jsonify({
        "success": True,
        "message": "Report submitted successfully.",
    }), 201


# =========================================================
# GENERIC REPORT
# =========================================================

@app.post("/api/reports")
@login_required
def create_report():
    data = get_json_body()

    if data is None:
        return jsonify({
            "error": "Invalid JSON body",
        }), 400

    post_id = data.get("post_id")
    thread_id = data.get("thread_id")

    reason = str(
        data.get("reason") or ""
    ).strip()

    if not reason:
        return jsonify({
            "error": "Reason is required",
        }), 400

    if not post_id and not thread_id:
        return jsonify({
            "error":
                "Either post_id or thread_id is required",
        }), 400

    if len(reason) > MAX_REASON_LEN:
        return jsonify({
            "error":
                f"Reason must be at most "
                f"{MAX_REASON_LEN} characters",
        }), 400

    connection = db()

    if post_id:
        post = connection.execute(
            """
            SELECT id, thread_id
            FROM posts
            WHERE id = ?
            """,
            (post_id,),
        ).fetchone()

        if not post:
            connection.close()

            return jsonify({
                "error": "Post not found",
            }), 404

        thread_id = thread_id or post["thread_id"]

    if thread_id:
        thread = connection.execute(
            """
            SELECT id
            FROM threads
            WHERE id = ?
            """,
            (thread_id,),
        ).fetchone()

        if not thread:
            connection.close()

            return jsonify({
                "error": "Thread not found",
            }), 404

    connection.execute(
        """
        INSERT INTO reports (
            post_id,
            thread_id,
            reporter_id,
            reason,
            status
        )
        VALUES (?, ?, ?, ?, 'open')
        """,
        (
            post_id,
            thread_id,
            g.user["id"],
            reason,
        ),
    )

    connection.commit()
    connection.close()

    return jsonify({
        "message": "Report submitted",
    }), 201


# =========================================================
# MODERATION - REPORTS
# =========================================================

@app.get("/api/reports")
@moderator_required
def list_reports():
    connection = db()

    rows = connection.execute(
        """
        SELECT
            reports.*,

            reporters.username AS reporter,

            posts.content AS post_content,

            threads.title AS thread_title

        FROM reports

        INNER JOIN users AS reporters
            ON reporters.id = reports.reporter_id

        LEFT JOIN posts
            ON posts.id = reports.post_id

        LEFT JOIN threads
            ON threads.id = reports.thread_id

        WHERE reports.status = 'open'

        ORDER BY
            reports.created_at DESC
        """
    ).fetchall()

    connection.close()

    return jsonify([
        dict(row)
        for row in rows
    ])


@app.put("/api/reports/<int:report_id>")
@moderator_required
def resolve_report(report_id):
    connection = db()

    report = connection.execute(
        """
        SELECT id
        FROM reports
        WHERE id = ?
        """,
        (report_id,),
    ).fetchone()

    if not report:
        connection.close()

        return jsonify({
            "error": "Report not found",
        }), 404

    connection.execute(
        """
        UPDATE reports
        SET status = 'resolved'
        WHERE id = ?
        """,
        (report_id,),
    )

    connection.commit()
    connection.close()

    return jsonify({
        "message": "Report resolved",
    })


# =========================================================
# MODERATION - DELETE POST
# =========================================================

@app.delete("/api/moderation/posts/<int:post_id>")
@moderator_required
def moderator_delete_post(post_id):
    connection = db()

    post = connection.execute(
        """
        SELECT id
        FROM posts
        WHERE id = ?
        """,
        (post_id,),
    ).fetchone()

    if not post:
        connection.close()

        return jsonify({
            "error": "Post not found",
        }), 404

    connection.execute(
        """
        UPDATE posts
        SET content = '[removed by moderation]'
        WHERE id = ?
        """,
        (post_id,),
    )

    connection.execute(
        """
        DELETE FROM reactions
        WHERE post_id = ?
        """,
        (post_id,),
    )

    connection.execute(
        """
        DELETE FROM reports
        WHERE post_id = ?
        """,
        (post_id,),
    )

    connection.commit()
    connection.close()

    return jsonify({
        "message": "Post removed by moderation",
    })


# =========================================================
# CREW
# =========================================================

@app.get("/api/crew")
def crew():
    connection = db()

    rows = connection.execute(
        """
        SELECT
            id,
            username,
            role
        FROM users
        ORDER BY id ASC
        """
    ).fetchall()

    connection.close()

    result = []

    for row in rows:
        username = row["username"]

        result.append({
            "id": row["id"],
            "username": username,
            "initials": username[:2].upper(),
            "role": row["role"],
        })

    return jsonify(result)


# =========================================================
# USER PROFILE
# =========================================================

@app.get("/api/users/<int:user_id>")
def get_profile(user_id):
    connection = db()

    user = connection.execute(
        """
        SELECT
            id,
            username,
            bio,
            role,
            joined_at
        FROM users
        WHERE id = ?
        """,
        (user_id,),
    ).fetchone()

    if not user:
        connection.close()

        return jsonify({
            "error": "User not found",
        }), 404

    recent_threads = connection.execute(
        """
        SELECT
            id,
            title,
            created_at
        FROM threads
        WHERE user_id = ?
        ORDER BY created_at DESC
        LIMIT 10
        """,
        (user_id,),
    ).fetchall()

    thread_count = connection.execute(
        """
        SELECT COUNT(*) AS count
        FROM threads
        WHERE user_id = ?
        """,
        (user_id,),
    ).fetchone()["count"]

    post_count = connection.execute(
        """
        SELECT COUNT(*) AS count
        FROM posts
        WHERE user_id = ?
        """,
        (user_id,),
    ).fetchone()["count"]

    reaction_count = connection.execute(
        """
        SELECT COUNT(*) AS count
        FROM reactions
        WHERE user_id = ?
        """,
        (user_id,),
    ).fetchone()["count"]

    connection.close()

    profile = dict(user)

    profile["thread_count"] = thread_count
    profile["post_count"] = post_count
    profile["reaction_count"] = reaction_count

    profile["recent_threads"] = [
        dict(thread)
        for thread in recent_threads
    ]

    return jsonify(profile)


# =========================================================
# EDIT PROFILE
# =========================================================

@app.put("/api/users/<int:user_id>")
@login_required
def edit_profile(user_id):
    if g.user["id"] != user_id:
        return jsonify({
            "error":
                "You can only edit your own profile",
        }), 403

    data = get_json_body()

    if data is None:
        return jsonify({
            "error": "Invalid JSON body",
        }), 400

    bio = str(
        data.get("bio") or ""
    ).strip()

    if len(bio) > MAX_BIO_LEN:
        return jsonify({
            "error":
                f"Bio must be at most "
                f"{MAX_BIO_LEN} characters",
        }), 400

    bio = sanitize_plain(bio)

    connection = db()

    connection.execute(
        """
        UPDATE users
        SET bio = ?
        WHERE id = ?
        """,
        (
            bio,
            user_id,
        ),
    )

    connection.commit()
    connection.close()

    return jsonify({
        "message": "Profile updated",
        "bio": bio,
    })


# =========================================================
# ADMIN - CHANGE ROLE
# =========================================================

@app.put("/api/users/<int:user_id>/role")
@admin_required
def set_role(user_id):
    data = get_json_body()

    if data is None:
        return jsonify({
            "error": "Invalid JSON body",
        }), 400

    role = data.get("role")

    if role not in (
        "user",
        "moderator",
        "admin",
    ):
        return jsonify({
            "error":
                "role must be one of: "
                "user, moderator, admin",
        }), 400

    # Prevent an admin from accidentally removing
    # their own admin role.
    if (
        user_id == g.user["id"]
        and role != "admin"
    ):
        return jsonify({
            "error":
                "You cannot remove your own admin role",
        }), 400

    connection = db()

    user = connection.execute(
        """
        SELECT id
        FROM users
        WHERE id = ?
        """,
        (user_id,),
    ).fetchone()

    if not user:
        connection.close()

        return jsonify({
            "error": "User not found",
        }), 404

    connection.execute(
        """
        UPDATE users
        SET role = ?
        WHERE id = ?
        """,
        (
            role,
            user_id,
        ),
    )

    connection.commit()
    connection.close()

    return jsonify({
        "message":
            f"Role updated to {role}",
        "role": role,
    })


# =========================================================
# HEALTH CHECK
# =========================================================

@app.get("/api/health")
def health():
    try:
        connection = db()

        connection.execute(
            "SELECT 1"
        ).fetchone()

        connection.close()

        return jsonify({
            "status": "ok",
            "database": "ok",
        })

    except Exception as error:
        return jsonify({
            "status": "error",
            "database": "error",
            "message": str(error),
        }), 500


# =========================================================
# STATIC FRONTEND
# =========================================================

@app.route("/")
def home():
    return send_from_directory(
        PROJECT_ROOT,
        "frontpage.html",
    )


@app.route("/<path:filename>")
def serve_page(filename):
    return send_from_directory(
        PROJECT_ROOT,
        filename,
    )


# =========================================================
# ERROR HANDLERS
# =========================================================

@app.errorhandler(404)
def not_found(error):
    if request.path.startswith("/api/"):
        return jsonify({
            "error": "Endpoint not found",
        }), 404

    return error


@app.errorhandler(500)
def internal_error(error):
    if request.path.startswith("/api/"):
        return jsonify({
            "error": "Internal server error",
        }), 500

    return error


# =========================================================
# START
# =========================================================

if __name__ == "__main__":
    init_db()

    print()
    print("=" * 60)
    print(" En Route Forum Backend")
    print("=" * 60)
    print(f" Database: {DATABASE}")
    print(f" Frontend: {PROJECT_ROOT}")
    print(" API: http://127.0.0.1:5000")
    print("=" * 60)
    print()

    app.run(
        host="127.0.0.1",
        port=5000,
        debug=True,
    )