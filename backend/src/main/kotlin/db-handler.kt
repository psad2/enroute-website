import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

// DATABASE is a var, not a val. A test can change it to a temporary file
// path, through the DATABASE_PATH environment variable. This gives each
// test run its own isolated database, separate from the real forum.db file.
var DATABASE = System.getenv("DATABASE_PATH") ?: "forum.db"

// This opens a new connection to the database file at DATABASE.
fun db(): Connection {
    val connection = DriverManager.getConnection("jdbc:sqlite:$DATABASE")

    connection.createStatement().use {
        stmt ->
        stmt.execute("PRAGMA foreign_keys = ON")
        stmt.execute("PRAGMA journal_mode = WAL")
    }

    return connection
}

// This checks if a column exists in a table. It returns true or false.
fun columnExists(connection: Connection, table: String, column: String): Boolean {
    connection.createStatement().use {
        stmt -> stmt.executeQuery("PRAGMA table_info($table)").use {
            rs -> while (rs.next()) {
                if (rs.getString("name") == column) return true
            }
        }
    }
    return false
}

fun tableExists(connection: Connection, table: String): Boolean {
    connection.prepareStatement(
        """
        SELECT name
        FROM sqlite_master
        WHERE type = 'table'
            AND name = ?
        """.trimIndent()
    ).use {
        stmt ->
        stmt.setString(1, table)
        stmt.executeQuery().use {
            rs -> return rs.next()
        }
    }
}

// This sets up the database. It creates every table if it does not
// already exist, then calls migrateSchema to update an older database
// file. It runs at every startup, so it must be safe to run more than once.
fun initDb() {
    val connection = db()

    // This creates the users table.
    connection.createStatement().use {
        stmt ->
        stmt.executeUpdate(
            """
            CREATE TABLE IF NOT EXISTS users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT UNIQUE NOT NULL,
                email TEXT UNIQUE NOT NULL,
                password TEXT NOT NULL,
                bio TEXT DEFAULT '', 
                role TEXT DEFAULT 'user',
                joined_at DATETIME DEFAULT CURRENT_TIMESTAMP
           )
           """.trimIndent()
        )

        // This creates the categories table.
        stmt.executeUpdate(
            """
            CREATE TABLE IF NOT EXISTS categories (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT UNIQUE NOT NULL
            )
            """.trimIndent()
        )

        // This creates the sessions table.
        stmt.executeUpdate(
            """
            CREATE TABLE IF NOT EXISTS sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER NOT NULL,
                token_hash TEXT UNIQUE NOT NULL,
                expires_at TEXT NOT NULL,

                FOREIGN KEY(user_id)
                    REFERENCES users(id)

            )
            """.trimIndent()
        )

        // This creates the threads table.
        stmt.executeUpdate(
            """
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
            )
            """.trimIndent()
        )

        // This creates the posts table.
        stmt.executeUpdate(
            """
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
            )
            """.trimIndent()
        )

        stmt.executeUpdate(
            """
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
            )
            """.trimIndent()
        )

        stmt.executeUpdate(
            """
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
            )
            """.trimIndent()
        )

        stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_threads_category ON threads(category_id)")
        stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_threads_user ON threads(user_id)")
        stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_threads_created ON threads(created_at)")
        stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_posts_thread ON posts(thread_id)")
        stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_posts_user ON posts(user_id)")
        stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_posts_parent ON posts(parent_post_id)")
        stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_posts_quote ON posts(quote_post_id)")
        stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_sessions_token ON sessions(token_hash)")
        stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_sessions_expires ON sessions(expires_at)")
        stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_reports_status ON reports(status)")
        stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_reactions_post ON reactions(post_id)")
    }

    migrateSchema(connection)

    val categories = listOf(
        "General Operations",
        "Flight Operations",
        "Hardware",
        "Software",
        "Helpdesk",
        "Offical Announcements",
        "Screenshots & Media",
        "Offtopic",
    )

    connection.prepareStatement(
        "INSERT OR IGNORE INTO categories (name) VALUES (?)"
    ).use {
        stmt ->
        for (category in categories) {
            stmt.setString(1, category)
            stmt.executeUpdate()
        }
    }

    connection.close()
}

// This updates an older database file to the current schema. A forum.db
// file from before a column existed will not have that column, since
// CREATE TABLE IF NOT EXISTS does not alter a table that already exists.
// This function adds each missing column, then fills in a safe default
// value for any row where the new column is still empty.
fun migrateSchema(connection: Connection) {
    data class ColumnMigration(val table: String, val column: String, val definition: String)

    val migrations = listOf(
        ColumnMigration("users", "bio", "TEXT DEFAULT ''"),
        ColumnMigration("users", "role", "TEXT DEFAULT 'user'"),
        ColumnMigration("users", "joined_at", "DATETIME"),

        ColumnMigration("threads", "pinned", "INTEGER DEFAULT 0"),
        ColumnMigration("threads", "locked", "INTEGER DEFAULT 0"),

        ColumnMigration("posts", "parent_post_id", "INTEGER"),
        ColumnMigration("posts", "quote_post_id", "INTEGER"),
    )
    connection.createStatement().use { stmt ->
        for ((table, column, definition) in migrations) {
            if (tableExists(connection, table) && !columnExists(connection, table, column)) {
                stmt.executeUpdate("ALTER TABLE $table ADD COLUMN $column $definition")
            }
        }

        stmt.executeUpdate(
            """
            UPDATE users
            SET joined_at = CURRENT_TIMESTAMP
            WHERE joined_at IS NULL
            """.trimIndent()
        )

        stmt.executeUpdate(
            """
            UPDATE users
            SET role = 'user'
            WHERE role IS NULL OR role = ''
            """.trimIndent()
        )

        stmt.executeUpdate(
            """
            UPDATE threads
            SET pinned = 0
            WHERE pinned IS NULL
            """.trimIndent()
        )

        stmt.executeUpdate(
            """
            UPDATE threads
            SET locked = 0
            WHERE locked IS NULL
            """.trimIndent()
        )
    }
}