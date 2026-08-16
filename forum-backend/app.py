from flask import Flask, request, jsonify, send_from_directory
import sqlite3
import markdown
import bleach
from werkzeug.security import generate_password_hash, check_password_hash

app = Flask(__name__)

DATABASE = "forum.db"

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
    return connection


def render_markdown(raw_text):
    """Convert raw markdown to sanitized HTML safe to render on the frontend."""
    html = markdown.markdown(
        raw_text,
        extensions=["extra", "nl2br", "sane_lists"]
    )
    clean_html = bleach.clean(
        html,
        tags=ALLOWED_TAGS,
        attributes=ALLOWED_ATTRS,
        strip=True
    )
    return clean_html


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
            joined_at DATETIME DEFAULT CURRENT_TIMESTAMP
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
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP
        );

        CREATE TABLE IF NOT EXISTS posts (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            thread_id INTEGER NOT NULL,
            content TEXT NOT NULL,
            user_id INTEGER NOT NULL,
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP
        );
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


# =========================================================
# REGISTER
# =========================================================

@app.post("/api/register")
def register():

    data = request.get_json()

    username = data.get("username")
    email = data.get("email")
    password = data.get("password")

    if not username or not email or not password:
        return jsonify({
            "error": "All fields are required"
        }), 400

    if len(password) < 8:
        return jsonify({
            "error": "Password must contain at least 8 characters"
        }), 400

    connection = db()

    try:
        connection.execute(
            """
            INSERT INTO users
            (username, email, password)
            VALUES (?, ?, ?)
            """,
            (
                username,
                email,
                generate_password_hash(password)
            )
        )

        connection.commit()

    except sqlite3.IntegrityError:
        connection.close()

        return jsonify({
            "error": "Username or email already exists"
        }), 409

    connection.close()

    return jsonify({
        "message": "Account created"
    }), 201


# =========================================================
# LOGIN
# =========================================================

@app.post("/api/login")
def login():

    data = request.get_json()

    username = data.get("username")
    password = data.get("password")

    connection = db()

    user = connection.execute(
        """
        SELECT *
        FROM users
        WHERE username = ?
        """,
        (username,)
    ).fetchone()

    connection.close()

    if not user or not check_password_hash(
        user["password"],
        password
    ):
        return jsonify({
            "error": "Invalid username or password"
        }), 401

    return jsonify({
        "message": "Login successful",
        "user_id": user["id"],
        "username": user["username"]
    })


# =========================================================
# CATEGORIES
# =========================================================

@app.get("/api/categories")
def categories():

    connection = db()

    rows = connection.execute(
        "SELECT * FROM categories ORDER BY name"
    ).fetchall()

    connection.close()

    return jsonify([
        dict(row)
        for row in rows
    ])


# =========================================================
# THREADS
# =========================================================

@app.get("/api/threads")
def threads():

    connection = db()

    rows = connection.execute("""
        SELECT
            threads.id,
            threads.title,
            threads.content,
            threads.created_at,
            threads.user_id,
            users.username,
            categories.name AS category
        FROM threads
        JOIN users
            ON users.id = threads.user_id
        JOIN categories
            ON categories.id = threads.category_id
        ORDER BY threads.created_at DESC
    """).fetchall()

    connection.close()

    results = []

    for row in rows:
        item = dict(row)
        item["content_html"] = render_markdown(item["content"])
        results.append(item)

    return jsonify(results)


# =========================================================
# CREATE THREAD
# =========================================================

@app.post("/api/threads")
def create_thread():

    data = request.get_json()

    title = data.get("title")
    content = data.get("content")
    user_id = data.get("user_id")
    category_id = data.get("category_id")

    if not title or not content or not user_id or not category_id:
        return jsonify({
            "error": "All fields are required"
        }), 400

    connection = db()

    cursor = connection.execute(
        """
        INSERT INTO threads
        (title, content, user_id, category_id)
        VALUES (?, ?, ?, ?)
        """,
        (
            title,
            content,
            user_id,
            category_id
        )
    )

    connection.commit()

    thread_id = cursor.lastrowid

    connection.close()

    return jsonify({
        "message": "Thread created",
        "thread_id": thread_id
    }), 201


# =========================================================
# GET THREAD
# =========================================================

@app.get("/api/threads/<int:thread_id>")
def get_thread(thread_id):

    connection = db()

    thread = connection.execute("""
        SELECT
            threads.*,
            users.username,
            categories.name AS category
        FROM threads
        JOIN users
            ON users.id = threads.user_id
        JOIN categories
            ON categories.id = threads.category_id
        WHERE threads.id = ?
    """, (thread_id,)).fetchone()

    if not thread:
        connection.close()

        return jsonify({
            "error": "Thread not found"
        }), 404

    posts = connection.execute("""
        SELECT
            posts.*,
            users.username
        FROM posts
        JOIN users
            ON users.id = posts.user_id
        WHERE thread_id = ?
        ORDER BY posts.created_at
    """, (thread_id,)).fetchall()

    connection.close()

    thread_dict = dict(thread)
    thread_dict["content_html"] = render_markdown(thread_dict["content"])

    posts_list = []
    for post in posts:
        post_dict = dict(post)
        post_dict["content_html"] = render_markdown(post_dict["content"])
        posts_list.append(post_dict)

    return jsonify({
        "thread": thread_dict,
        "posts": posts_list
    })


# =========================================================
# DELETE THREAD
# =========================================================

@app.delete("/api/threads/<int:thread_id>")
def delete_thread(thread_id):

    data = request.get_json(silent=True) or {}
    user_id = data.get("user_id")

    if not user_id:
        return jsonify({
            "error": "user_id is required"
        }), 400

    connection = db()

    thread = connection.execute(
        "SELECT * FROM threads WHERE id = ?",
        (thread_id,)
    ).fetchone()

    if not thread:
        connection.close()
        return jsonify({
            "error": "Thread not found"
        }), 404

    if thread["user_id"] != user_id:
        connection.close()
        return jsonify({
            "error": "You can only delete your own threads"
        }), 403

    connection.execute("DELETE FROM posts WHERE thread_id = ?", (thread_id,))
    connection.execute("DELETE FROM threads WHERE id = ?", (thread_id,))

    connection.commit()
    connection.close()

    return jsonify({
        "message": "Thread deleted"
    })


# =========================================================
# REPLY
# =========================================================

@app.post("/api/threads/<int:thread_id>/reply")
def reply(thread_id):

    data = request.get_json()

    content = data.get("content")
    user_id = data.get("user_id")

    if not content or not user_id:
        return jsonify({
            "error": "Content and user are required"
        }), 400

    connection = db()

    cursor = connection.execute(
        """
        INSERT INTO posts
        (thread_id, content, user_id)
        VALUES (?, ?, ?)
        """,
        (
            thread_id,
            content,
            user_id
        )
    )

    connection.commit()

    post_id = cursor.lastrowid

    connection.close()

    return jsonify({
        "message": "Reply posted",
        "post_id": post_id
    }), 201


# =========================================================
# EDIT POST
# =========================================================

@app.put("/api/posts/<int:post_id>")
def edit_post(post_id):

    data = request.get_json()

    user_id = data.get("user_id")
    content = data.get("content")

    if not user_id or not content:
        return jsonify({
            "error": "user_id and content are required"
        }), 400

    connection = db()

    post = connection.execute(
        "SELECT * FROM posts WHERE id = ?",
        (post_id,)
    ).fetchone()

    if not post:
        connection.close()
        return jsonify({
            "error": "Post not found"
        }), 404

    if post["user_id"] != user_id:
        connection.close()
        return jsonify({
            "error": "You can only edit your own posts"
        }), 403

    connection.execute(
        "UPDATE posts SET content = ? WHERE id = ?",
        (content, post_id)
    )

    connection.commit()
    connection.close()

    return jsonify({
        "message": "Post updated",
        "content_html": render_markdown(content)
    })


# =========================================================
# DELETE POST
# =========================================================

@app.delete("/api/posts/<int:post_id>")
def delete_post(post_id):

    data = request.get_json(silent=True) or {}
    user_id = data.get("user_id")

    if not user_id:
        return jsonify({
            "error": "user_id is required"
        }), 400

    connection = db()

    post = connection.execute(
        "SELECT * FROM posts WHERE id = ?",
        (post_id,)
    ).fetchone()

    if not post:
        connection.close()
        return jsonify({
            "error": "Post not found"
        }), 404

    if post["user_id"] != user_id:
        connection.close()
        return jsonify({
            "error": "You can only delete your own posts"
        }), 403

    connection.execute("DELETE FROM posts WHERE id = ?", (post_id,))

    connection.commit()
    connection.close()

    return jsonify({
        "message": "Post deleted"
    })


# =========================================================
# CREW
# =========================================================

@app.get("/api/crew")
def crew():

    connection = db()

    rows = connection.execute("""
        SELECT
            id,
            username
        FROM users
        ORDER BY id ASC
    """).fetchall()

    connection.close()

    crew_list = []

    for row in rows:
        username = row["username"]

        # Get first two letters for avatar
        initials = username[:2].upper()

        crew_list.append({
            "id": row["id"],
            "username": username,
            "initials": initials,
            "role": "Pilot"
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
        SELECT id, username, email, bio, joined_at
        FROM users
        WHERE id = ?
        """,
        (user_id,)
    ).fetchone()

    if not user:
        connection.close()
        return jsonify({
            "error": "User not found"
        }), 404

    threads = connection.execute(
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
    profile["recent_threads"] = [dict(t) for t in threads]

    return jsonify(profile)


# =========================================================
# EDIT PROFILE (bio)
# =========================================================

@app.put("/api/users/<int:user_id>")
def edit_profile(user_id):

    data = request.get_json()

    requester_id = data.get("user_id")
    bio = data.get("bio", "")

    if requester_id != user_id:
        return jsonify({
            "error": "You can only edit your own profile"
        }), 403

    connection = db()

    user = connection.execute(
        "SELECT id FROM users WHERE id = ?",
        (user_id,)
    ).fetchone()

    if not user:
        connection.close()
        return jsonify({
            "error": "User not found"
        }), 404

    connection.execute(
        "UPDATE users SET bio = ? WHERE id = ?",
        (bio, user_id)
    )

    connection.commit()
    connection.close()

    return jsonify({
        "message": "Profile updated"
    })


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