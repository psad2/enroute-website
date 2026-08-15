require("dotenv").config();

const express = require("express");
const path = require("path");
const bcrypt = require("bcrypt");
const jwt = require("jsonwebtoken");
const cookieParser = require("cookie-parser");
const helmet = require("helmet");
const crypto = require("crypto");

const db = require("./src/db");

const app = express();

const PORT = Number(process.env.PORT || 3000);

const JWT_SECRET = process.env.JWT_SECRET;

if (!JWT_SECRET) {
    throw new Error(
        "JWT_SECRET is missing from .env"
    );
}


/* =========================================================
   EXPRESS CONFIG
   ========================================================= */

app.use(
    helmet({
        contentSecurityPolicy: false
    })
);

app.use(
    express.json({
        limit: "100kb"
    })
);

app.use(
    express.urlencoded({
        extended: true,
        limit: "100kb"
    })
);

app.use(cookieParser());


/* =========================================================
   STATIC FILES
   ========================================================= */

app.use(
    express.static(
        path.join(__dirname)
    )
);


/* =========================================================
   HELPERS
   ========================================================= */

function createToken(user) {

    return jwt.sign(
        {
            id: user.id,
            username: user.username,
            role: user.role
        },
        JWT_SECRET,
        {
            expiresIn: "7d"
        }
    );

}


function setAuthCookie(res, token) {

    res.cookie(
        "ert_session",
        token,
        {
            httpOnly: true,

            secure:
                process.env.COOKIE_SECURE === "true",

            sameSite: "lax",

            maxAge:
                7 * 24 * 60 * 60 * 1000,

            path: "/"
        }
    );

}


function clearAuthCookie(res) {

    res.clearCookie(
        "ert_session",
        {
            httpOnly: true,
            sameSite: "lax",
            path: "/"
        }
    );

}


function requireAuth(req, res, next) {

    const token =
        req.cookies.ert_session;

    if (!token) {

        return res.status(401).json({
            error: "Authentication required."
        });

    }

    try {

        const decoded =
            jwt.verify(
                token,
                JWT_SECRET
            );

        const user =
            db.prepare(`
        SELECT
          id,
          email,
          username,
          display_name,
          avatar_url,
          role,
          created_at,
          last_login,
          is_active
        FROM users
        WHERE id = ?
      `).get(decoded.id);

        if (!user || !user.is_active) {

            return res.status(401).json({
                error: "Account unavailable."
            });

        }

        req.user = user;

        next();

    } catch {

        return res.status(401).json({
            error: "Invalid or expired session."
        });

    }

}


function requireStaff(req, res, next) {

    if (
        !req.user ||
        !["admin", "moderator", "staff"].includes(
            req.user.role
        )
    ) {

        return res.status(403).json({
            error: "Staff access required."
        });

    }

    next();

}


function sanitizeUser(user) {

    if (!user) {
        return null;
    }

    return {
        id: user.id,
        username: user.username,
        display_name:
            user.display_name || user.username,
        avatar_url:
            user.avatar_url || null,
        role: user.role,
        created_at: user.created_at,
        last_login: user.last_login
    };

}


/* =========================================================
   HEALTH CHECK
   ========================================================= */

app.get(
    "/api/health",
    (req, res) => {

        res.json({
            status: "online",
            service: "En Route Forum API"
        });

    }
);


/* =========================================================
   AUTH
   ========================================================= */

/*
POST /api/auth/register
*/

app.post(
    "/api/auth/register",
    async (req, res) => {

        try {

            const email =
                String(req.body.email || "")
                    .trim()
                    .toLowerCase();

            const username =
                String(req.body.username || "")
                    .trim();

            const password =
                String(req.body.password || "");

            if (
                !email ||
                !username ||
                !password
            ) {

                return res.status(400).json({
                    error:
                        "Email, username and password are required."
                });

            }

            if (password.length < 10) {

                return res.status(400).json({
                    error:
                        "Password must contain at least 10 characters."
                });

            }

            if (!/^[a-zA-Z0-9_]{3,24}$/.test(username)) {

                return res.status(400).json({
                    error:
                        "Username must be 3-24 characters and contain only letters, numbers and underscores."
                });

            }

            if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {

                return res.status(400).json({
                    error:
                        "Please enter a valid email address."
                });

            }

            const existing =
                db.prepare(`
          SELECT id
          FROM users
          WHERE email = ?
             OR username = ?
        `).get(
                    email,
                    username
                );

            if (existing) {

                return res.status(409).json({
                    error:
                        "An account with that email or username already exists."
                });

            }

            const passwordHash =
                await bcrypt.hash(
                    password,
                    12
                );

            const result =
                db.prepare(`
          INSERT INTO users (
            email,
            username,
            display_name,
            password_hash
          )
          VALUES (?, ?, ?, ?)
        `).run(
                    email,
                    username,
                    username,
                    passwordHash
                );

            const user =
                db.prepare(`
          SELECT *
          FROM users
          WHERE id = ?
        `).get(result.lastInsertRowid);

            const token =
                createToken(user);

            setAuthCookie(
                res,
                token
            );

            res.status(201).json({
                user: sanitizeUser(user)
            });

        } catch (error) {

            console.error(error);

            res.status(500).json({
                error:
                    "Registration failed."
            });

        }

    }
);


/*
POST /api/auth/login
*/

app.post(
    "/api/auth/login",
    async (req, res) => {

        try {

            const email =
                String(req.body.email || "")
                    .trim()
                    .toLowerCase();

            const password =
                String(req.body.password || "");

            if (!email || !password) {

                return res.status(400).json({
                    error:
                        "Email and password are required."
                });

            }

            const user =
                db.prepare(`
          SELECT *
          FROM users
          WHERE email = ?
        `).get(email);

            if (!user) {

                return res.status(401).json({
                    error:
                        "Invalid email or password."
                });

            }

            if (!user.is_active) {

                return res.status(403).json({
                    error:
                        "This account has been disabled."
                });

            }

            const valid =
                await bcrypt.compare(
                    password,
                    user.password_hash
                );

            if (!valid) {

                return res.status(401).json({
                    error:
                        "Invalid email or password."
                });

            }

            db.prepare(`
        UPDATE users
        SET last_login = CURRENT_TIMESTAMP
        WHERE id = ?
      `).run(user.id);

            const token =
                createToken(user);

            setAuthCookie(
                res,
                token
            );

            res.json({
                user: sanitizeUser(user)
            });

        } catch (error) {

            console.error(error);

            res.status(500).json({
                error:
                    "Login failed."
            });

        }

    }
);


/*
POST /api/auth/logout
*/

app.post(
    "/api/auth/logout",
    (req, res) => {

        clearAuthCookie(res);

        res.json({
            success: true
        });

    }
);


/*
GET /api/auth/me
*/

app.get(
    "/api/auth/me",
    (req, res) => {

        const token =
            req.cookies.ert_session;

        if (!token) {

            return res.json({
                user: null
            });

        }

        try {

            const decoded =
                jwt.verify(
                    token,
                    JWT_SECRET
                );

            const user =
                db.prepare(`
          SELECT *
          FROM users
          WHERE id = ?
        `).get(decoded.id);

            if (!user || !user.is_active) {

                clearAuthCookie(res);

                return res.json({
                    user: null
                });

            }

            res.json({
                user: sanitizeUser(user)
            });

        } catch {

            clearAuthCookie(res);

            res.json({
                user: null
            });

        }

    }
);


/* =========================================================
   FORUM CATEGORIES
   ========================================================= */

app.get(
    "/api/forums",
    (req, res) => {

        const categories =
            db.prepare(`
        SELECT *
        FROM categories
        ORDER BY sort_order ASC
      `).all();

        const forums =
            db.prepare(`
        SELECT
          f.*,
          COUNT(DISTINCT t.id) AS topic_count,
          COUNT(DISTINCT p.id) AS post_count
        FROM forums f
        LEFT JOIN threads t
          ON t.forum_id = f.id
        LEFT JOIN posts p
          ON p.thread_id = t.id
        GROUP BY f.id
        ORDER BY f.sort_order ASC
      `).all();

        const result =
            categories.map(category => ({
                ...category,

                forums:
                    forums.filter(
                        forum =>
                            forum.category_id ===
                            category.id
                    )
            }));

        res.json(result);

    }
);


/* =========================================================
   THREAD LIST
   ========================================================= */

app.get(
    "/api/forums/:forumId/threads",
    (req, res) => {

        const forumId =
            Number(req.params.forumId);

        if (!Number.isInteger(forumId)) {

            return res.status(400).json({
                error: "Invalid forum."
            });

        }

        const threads =
            db.prepare(`
        SELECT
          t.id,
          t.title,
          t.is_pinned,
          t.is_locked,
          t.views,
          t.created_at,
          t.updated_at,

          u.id AS user_id,
          u.username,
          u.display_name,

          COUNT(p.id) AS reply_count

        FROM threads t

        JOIN users u
          ON u.id = t.user_id

        LEFT JOIN posts p
          ON p.thread_id = t.id

        WHERE t.forum_id = ?

        GROUP BY t.id

        ORDER BY
          t.is_pinned DESC,
          t.updated_at DESC
      `).all(forumId);

        res.json(threads);

    }
);


/* =========================================================
   GET THREAD
   ========================================================= */

app.get(
    "/api/threads/:threadId",
    (req, res) => {

        const threadId =
            Number(req.params.threadId);

        if (!Number.isInteger(threadId)) {

            return res.status(400).json({
                error: "Invalid thread."
            });

        }

        const thread =
            db.prepare(`
        SELECT
          t.*,

          f.name AS forum_name,

          u.username AS author_username,
          u.display_name AS author_display_name,
          u.avatar_url AS author_avatar,
          u.role AS author_role

        FROM threads t

        JOIN forums f
          ON f.id = t.forum_id

        JOIN users u
          ON u.id = t.user_id

        WHERE t.id = ?
      `).get(threadId);

        if (!thread) {

            return res.status(404).json({
                error: "Thread not found."
            });

        }

        db.prepare(`
      UPDATE threads
      SET views = views + 1
      WHERE id = ?
    `).run(threadId);

        const posts =
            db.prepare(`
        SELECT
          p.id,
          p.content,
          p.created_at,
          p.updated_at,

          u.id AS user_id,
          u.username,
          u.display_name,
          u.avatar_url,
          u.role,
          u.created_at AS user_joined

        FROM posts p

        JOIN users u
          ON u.id = p.user_id

        WHERE p.thread_id = ?

        ORDER BY p.created_at ASC
      `).all(threadId);

        res.json({
            thread,
            posts
        });

    }
);


/* =========================================================
   CREATE THREAD
   ========================================================= */

app.post(
    "/api/forums/:forumId/threads",
    requireAuth,
    (req, res) => {

        const forumId =
            Number(req.params.forumId);

        const title =
            String(req.body.title || "")
                .trim();

        const content =
            String(req.body.content || "")
                .trim();

        if (
            !Number.isInteger(forumId) ||
            !title ||
            !content
        ) {

            return res.status(400).json({
                error:
                    "Forum, title and content are required."
            });

        }

        if (title.length > 150) {

            return res.status(400).json({
                error:
                    "Thread title is too long."
            });

        }

        if (content.length > 20000) {

            return res.status(400).json({
                error:
                    "Post is too long."
            });

        }

        const forum =
            db.prepare(`
        SELECT *
        FROM forums
        WHERE id = ?
      `).get(forumId);

        if (!forum) {

            return res.status(404).json({
                error:
                    "Forum not found."
            });

        }

        const category =
            db.prepare(`
        SELECT *
        FROM categories
        WHERE id = ?
      `).get(forum.category_id);

        if (
            category &&
            category.is_staff_only &&
            !["staff", "moderator", "admin"]
                .includes(req.user.role)
        ) {

            return res.status(403).json({
                error:
                    "This forum is restricted."
            });

        }

        const result =
            db.prepare(`
        INSERT INTO threads (
          forum_id,
          user_id,
          title,
          content
        )
        VALUES (?, ?, ?, ?)
      `).run(
                forumId,
                req.user.id,
                title,
                content
            );

        db.prepare(`
      INSERT INTO posts (
        thread_id,
        user_id,
        content
      )
      VALUES (?, ?, ?)
    `).run(
            result.lastInsertRowid,
            req.user.id,
            content
        );

        res.status(201).json({
            thread_id:
            result.lastInsertRowid
        });

    }
);


/* =========================================================
   REPLY
   ========================================================= */

app.post(
    "/api/threads/:threadId/posts",
    requireAuth,
    (req, res) => {

        const threadId =
            Number(req.params.threadId);

        const content =
            String(req.body.content || "")
                .trim();

        if (
            !Number.isInteger(threadId) ||
            !content
        ) {

            return res.status(400).json({
                error:
                    "Reply content is required."
            });

        }

        if (content.length > 20000) {

            return res.status(400).json({
                error:
                    "Reply is too long."
            });

        }

        const thread =
            db.prepare(`
        SELECT *
        FROM threads
        WHERE id = ?
      `).get(threadId);

        if (!thread) {

            return res.status(404).json({
                error:
                    "Thread not found."
            });

        }

        if (thread.is_locked) {

            return res.status(403).json({
                error:
                    "This thread is locked."
            });

        }

        const result =
            db.prepare(`
        INSERT INTO posts (
          thread_id,
          user_id,
          content
        )
        VALUES (?, ?, ?)
      `).run(
                threadId,
                req.user.id,
                content
            );

        db.prepare(`
      UPDATE threads
      SET updated_at = CURRENT_TIMESTAMP
      WHERE id = ?
    `).run(threadId);

        res.status(201).json({
            post_id:
            result.lastInsertRowid
        });

    }
);


/* =========================================================
   SEARCH
   ========================================================= */

app.get(
    "/api/search",
    (req, res) => {

        const q =
            String(req.query.q || "")
                .trim();

        if (!q) {

            return res.json([]);

        }

        if (q.length > 100) {

            return res.status(400).json({
                error:
                    "Search query is too long."
            });

        }

        const search =
            `%${q.replace(/[%_]/g, "")}%`;

        const threads =
            db.prepare(`
        SELECT
          t.id,
          t.title,
          t.created_at,
          t.updated_at,

          f.name AS forum_name,

          u.username,
          u.display_name

        FROM threads t

        JOIN forums f
          ON f.id = t.forum_id

        JOIN users u
          ON u.id = t.user_id

        WHERE
          t.title LIKE ?
          OR t.content LIKE ?

        ORDER BY t.updated_at DESC

        LIMIT 50
      `).all(
                search,
                search
            );

        res.json(threads);

    }
);


/* =========================================================
   STAFF: LOCK THREAD
   ========================================================= */

app.patch(
    "/api/threads/:threadId/lock",
    requireAuth,
    requireStaff,
    (req, res) => {

        const threadId =
            Number(req.params.threadId);

        const locked =
            Boolean(req.body.locked);

        db.prepare(`
      UPDATE threads
      SET is_locked = ?
      WHERE id = ?
    `).run(
            locked ? 1 : 0,
            threadId
        );

        res.json({
            success: true
        });

    }
);


/* =========================================================
   STAFF: PIN THREAD
   ========================================================= */

app.patch(
    "/api/threads/:threadId/pin",
    requireAuth,
    requireStaff,
    (req, res) => {

        const threadId =
            Number(req.params.threadId);

        const pinned =
            Boolean(req.body.pinned);

        db.prepare(`
      UPDATE threads
      SET is_pinned = ?
      WHERE id = ?
    `).run(
            pinned ? 1 : 0,
            threadId
        );

        res.json({
            success: true
        });

    }
);


/* =========================================================
   STAFF: DISABLE USER
   ========================================================= */

app.patch(
    "/api/users/:userId/disable",
    requireAuth,
    requireStaff,
    (req, res) => {

        const userId =
            Number(req.params.userId);

        db.prepare(`
      UPDATE users
      SET is_active = 0
      WHERE id = ?
    `).run(userId);

        res.json({
            success: true
        });

    }
);


/* =========================================================
   404 API
   ========================================================= */

app.use(
    "/api",
    (req, res) => {

        res.status(404).json({
            error:
                "API endpoint not found."
        });

    }
);


/* =========================================================
   START SERVER
   ========================================================= */

app.listen(
    PORT,
    () => {

        console.log("");
        console.log(
            "=========================================="
        );

        console.log(
            " EN ROUTE FORUM SERVER"
        );

        console.log(
            "=========================================="
        );

        console.log(
            ` Local: http://localhost:${PORT}`
        );

        console.log(
            "=========================================="
        );

    }
);