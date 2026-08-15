from flask import Flask, request, jsonify, send_from_directory
import sqlite3
from werkzeug.security import generate_password_hash, check_password_hash

app = Flask(__name__)

DATABASE = "forum.db"


def db():
    connection = sqlite3.connect(DATABASE)
    connection.row_factory = sqlite3.Row
    return connection


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
            password TEXT NOT NULL
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

    return jsonify([
        dict(row)
        for row in rows
    ])


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

    return jsonify({
        "thread": dict(thread),
        "posts": [
            dict(post)
            for post in posts
        ]
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

    connection.execute(
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
    connection.close()

    return jsonify({
        "message": "Reply posted"
    }), 201

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