import com.enroute.auth.getUserFromToken
import com.enroute.auth.isModerator

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

import java.sql.PreparedStatement
import java.sql.Statement
import kotlin.math.ceil

fun PreparedStatement.setParam(index: Int, value: Any) {
    when (value) {
        is Long -> setLong(index, value)
        is Int -> setInt(index, value)
        is String -> setString(index, value)
        else -> setObject(index, value)
    }
}

@Serializable
data class ThreadListItem(
    val id: Long,
    val title: String,
    val content: String,
    val contentHtml: String,
    val createdAt: String,
    val userId: Long,
    val categoryId: Long,
    val pinned: Boolean,
    val locked: Boolean,
    val username: String,
    val category: String,
    val replyCount: Int
)

@Serializable
data class ThreadListResponse(
    val page: Int,
    val perPage: Int,
    val limit: Int,
    val total: Int,
    val totalPages: Int,
    val threads: List<ThreadListItem>
)

@Serializable
data class CreateThreadResponse(
    val message: String,
    val threadId: Long
)

@Serializable
data class ThreadDetail(
    val id: Long,
    val title: String,
    val content: String,
    val contentHtml: String,
    val userId: Long,
    val categoryId: Long,
    val pinned: Boolean,
    val locked: Boolean,
    val createdAt: String,
    val username: String,
    val category: String,
    val firstPostId: Long?,
    val reactions: ReactionCounts
)

@Serializable
data class ThreadResponse(
    val thread: ThreadDetail,
    val posts: List<SerializedPost>,
    val page: Int,
    val perPage: Int,
    val limit: Int,
    val totalPosts: Int,
    val totalPages: Int
)

@Serializable
data class EditThreadResponse(
    val message: String,
    val title: String,
    val content: String,
    val contentHtml: String
)

@Serializable
data class PinResponse(val pinned: Boolean)

@Serializable
data class LockResponse(val locked: Boolean)

fun Route.threadsRoute() {

    // LIST THREADS
    get("/api/threads") {
        val pagination = call.paginateArgs()

        val categoryIdParam = call.request.queryParameters["category_id"]
        val q = (call.request.queryParameters["q"] ?: "").trim()

        var categoryId: Long? = null

        if (!categoryIdParam.isNullOrBlank()) {
            categoryId = categoryIdParam.toLongOrNull()

            if (categoryId == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid category_id"))
                return@get
            }
        }

        db().use { connection ->
            val where = mutableListOf("1 = 1")
            val params = mutableListOf<Any>()

            if (categoryId != null) {
                where.add("threads.category_id = ?")
                params.add(categoryId)
            }

            if (q.isNotEmpty()) {
                where.add("(threads.title LIKE ? OR threads.content LIKE ?)")

                val like = "%$q%"

                params.add(like)
                params.add(like)
            }

            val whereClause = where.joinToString(" AND ")

            val total = connection.prepareStatement(
                "SELECT COUNT(*) AS count FROM threads WHERE $whereClause"
            ).use { stmt ->
                params.forEachIndexed { index, value -> stmt.setParam(index + 1, value) }

                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt("count")
                }
            }

            val threads = connection.prepareStatement(
                """
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

                WHERE $whereClause

                ORDER BY
                    threads.pinned DESC,
                    threads.created_at DESC,
                    threads.id DESC

                LIMIT ?
                OFFSET ?
                """.trimIndent()
            ).use { stmt ->
                var index = 1

                for (value in params) {
                    stmt.setParam(index, value)
                    index++
                }

                stmt.setInt(index, pagination.perPage)
                stmt.setInt(index + 1, pagination.offset)

                stmt.executeQuery().use { rs ->
                    val result = mutableListOf<ThreadListItem>()

                    while (rs.next()) {
                        val content = rs.getString("content")

                        result.add(
                            ThreadListItem(
                                id = rs.getLong("id"),
                                title = rs.getString("title"),
                                content = content,
                                contentHtml = MarkdownRenderer.render(content),
                                createdAt = rs.getString("created_at"),
                                userId = rs.getLong("user_id"),
                                categoryId = rs.getLong("category_id"),
                                pinned = rs.getInt("pinned") != 0,
                                locked = rs.getInt("locked") != 0,
                                username = rs.getString("username"),
                                category = rs.getString("category"),
                                replyCount = rs.getInt("reply_count")
                            )
                        )
                    }

                    result
                }
            }

            call.respond(
                HttpStatusCode.OK,
                ThreadListResponse(
                    page = pagination.page,
                    perPage = pagination.perPage,
                    limit = pagination.perPage,
                    total = total,
                    totalPages = maxOf(1, ceil(total.toDouble() / pagination.perPage).toInt()),
                    threads = threads
                )
            )
        }
    }

    // CREATE THREAD
    post("/api/threads") {
        val token = call.bearerToken()

        if (token == null) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Authentication required"))
            return@post
        }

        val data = call.getJsonBody()

        if (data == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid JSON body"))
            return@post
        }

        val title = (data["title"]?.jsonPrimitive?.contentOrNull ?: "").trim()
        val content = (data["content"]?.jsonPrimitive?.contentOrNull ?: "").trim()
        val categoryIdElement = data["category_id"]

        if (title.isEmpty() || content.isEmpty() || categoryIdElement == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Title, content and category_id are required"))
            return@post
        }

        if (title.length > MAX_TITLE_LEN) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Title must be at most $MAX_TITLE_LEN characters"))
            return@post
        }

        if (content.length > MAX_CONTENT_LEN) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Content must be at most $MAX_CONTENT_LEN characters"))
            return@post
        }

        val categoryId = categoryIdElement.jsonPrimitive.longOrNull

        if (categoryId == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid category_id"))
            return@post
        }

        db().use { connection ->
            val user = getUserFromToken(connection, token)

            if (user == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid or expired session"))
                return@post
            }

            val categoryExists = connection.prepareStatement(
                "SELECT id FROM categories WHERE id = ?"
            ).use { stmt ->
                stmt.setLong(1, categoryId)
                stmt.executeQuery().use { rs -> rs.next() }
            }

            if (!categoryExists) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Category not found"))
                return@post
            }

            val threadId = connection.prepareStatement(
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
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS
            ).use { stmt ->
                stmt.setString(1, title)
                stmt.setString(2, content)
                stmt.setLong(3, user.id)
                stmt.setLong(4, categoryId)
                stmt.executeUpdate()

                stmt.generatedKeys.use { keys ->
                    keys.next()
                    keys.getLong(1)
                }
            }

            // the first post is the actual thread content
            connection.prepareStatement(
                """
                INSERT INTO posts (
                    thread_id,
                    content,
                    user_id,
                    parent_post_id,
                    quote_post_id
                )
                VALUES (?, ?, ?, NULL, NULL)
                """.trimIndent()
            ).use { stmt ->
                stmt.setLong(1, threadId)
                stmt.setString(2, content)
                stmt.setLong(3, user.id)
                stmt.executeUpdate()
            }

            call.respond(
                HttpStatusCode.Created,
                CreateThreadResponse(
                    message = "Thread created",
                    threadId = threadId
                )
            )
        }
    }

    // GET THREAD
    get("/api/threads/{threadId}") {
        val threadId = call.parameters["threadId"]?.toLongOrNull()

        if (threadId == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Thread not found"))
            return@get
        }

        val pagination = call.paginateArgs()

        db().use { connection ->
            val threadRow = connection.prepareStatement(
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
                """.trimIndent()
            ).use { stmt ->
                stmt.setLong(1, threadId)

                stmt.executeQuery().use { rs ->
                    if (!rs.next()) {
                        null
                    } else {
                        ThreadDetail(
                            id = rs.getLong("id"),
                            title = rs.getString("title"),
                            content = rs.getString("content"),
                            contentHtml = MarkdownRenderer.render(rs.getString("content")),
                            userId = rs.getLong("user_id"),
                            categoryId = rs.getLong("category_id"),
                            pinned = rs.getInt("pinned") != 0,
                            locked = rs.getInt("locked") != 0,
                            createdAt = rs.getString("created_at"),
                            username = rs.getString("username"),
                            category = rs.getString("category"),
                            firstPostId = null,
                            reactions = emptyReactionCounts()
                        )
                    }
                }
            }

            if (threadRow == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Thread not found"))
                return@get
            }

            val firstPostId = connection.prepareStatement(
                "SELECT MIN(id) AS first_id FROM posts WHERE thread_id = ?"
            ).use { stmt ->
                stmt.setLong(1, threadId)

                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        val id = rs.getLong("first_id")
                        if (rs.wasNull()) null else id
                    } else {
                        null
                    }
                }
            }

            val totalPosts = connection.prepareStatement(
                """
                SELECT COUNT(*) AS count
                FROM posts
                WHERE thread_id = ?
                  AND (? IS NULL OR id != ?)
                """.trimIndent()
            ).use { stmt ->
                stmt.setLong(1, threadId)
                stmt.setNullableLong(2, firstPostId)
                stmt.setNullableLong(3, firstPostId)

                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt("count")
                }
            }

            val posts = connection.prepareStatement(
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
                """.trimIndent()
            ).use { stmt ->
                stmt.setLong(1, threadId)
                stmt.setNullableLong(2, firstPostId)
                stmt.setNullableLong(3, firstPostId)
                stmt.setInt(4, pagination.perPage)
                stmt.setInt(5, pagination.offset)

                stmt.executeQuery().use { rs ->
                    val result = mutableListOf<SerializedPost>()

                    while (rs.next()) {
                        val postRecord = rs.toPostRecord()
                        val serialized = serializePost(connection, postRecord)

                        val quoteId = serialized.quotePostId

                        if (quoteId != null) {
                            connection.prepareStatement(
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
                                """.trimIndent()
                            ).use { quoteStmt ->
                                quoteStmt.setLong(1, quoteId)
                                quoteStmt.setLong(2, threadId)

                                quoteStmt.executeQuery().use { quoteRs ->
                                    if (quoteRs.next()) {
                                        val quotedContent = quoteRs.getString("content")

                                        serialized.quotedPost = QuotedPostSummary(
                                            id = quoteRs.getLong("id"),
                                            threadId = quoteRs.getLong("thread_id"),
                                            content = quotedContent,
                                            contentHtml = MarkdownRenderer.render(quotedContent),
                                            createdAt = quoteRs.getString("created_at"),
                                            userId = quoteRs.getLong("user_id"),
                                            username = quoteRs.getString("username")
                                        )
                                    }
                                }
                            }
                        }

                        val parentId = serialized.parentPostId

                        if (parentId != null) {
                            connection.prepareStatement(
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
                                """.trimIndent()
                            ).use { parentStmt ->
                                parentStmt.setLong(1, parentId)
                                parentStmt.setLong(2, threadId)

                                parentStmt.executeQuery().use { parentRs ->
                                    if (parentRs.next()) {
                                        serialized.parentPost = ParentPostSummary(
                                            id = parentRs.getLong("id"),
                                            content = parentRs.getString("content"),
                                            userId = parentRs.getLong("user_id"),
                                            username = parentRs.getString("username")
                                        )
                                    }
                                }
                            }
                        }

                        result.add(serialized)
                    }

                    result
                }
            }

            val threadReactions = if (firstPostId != null) {
                getReactionCounts(connection, firstPostId)
            } else {
                emptyReactionCounts()
            }

            call.respond(
                HttpStatusCode.OK,
                ThreadResponse(
                    thread = threadRow.copy(firstPostId = firstPostId, reactions = threadReactions),
                    posts = posts,
                    page = pagination.page,
                    perPage = pagination.perPage,
                    limit = pagination.perPage,
                    totalPosts = totalPosts,
                    totalPages = maxOf(1, ceil(totalPosts.toDouble() / pagination.perPage).toInt())
                )
            )
        }
    }

    // EDIT THREAD
    put("/api/threads/{threadId}") {
        val threadId = call.parameters["threadId"]?.toLongOrNull()

        if (threadId == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Thread not found"))
            return@put
        }

        val token = call.bearerToken()

        if (token == null) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Authentication required"))
            return@put
        }

        val data = call.getJsonBody()

        if (data == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid JSON body"))
            return@put
        }

        db().use { connection ->
            val user = getUserFromToken(connection, token)

            if (user == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid or expired session"))
                return@put
            }

            data class ThreadRow(
                val userId: Long,
                val locked: Boolean,
                val title: String,
                val content: String
            )

            val thread = connection.prepareStatement(
                "SELECT * FROM threads WHERE id = ?"
            ).use { stmt ->
                stmt.setLong(1, threadId)

                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        ThreadRow(
                            userId = rs.getLong("user_id"),
                            locked = rs.getInt("locked") != 0,
                            title = rs.getString("title"),
                            content = rs.getString("content")
                        )
                    } else {
                        null
                    }
                }
            }

            if (thread == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Thread not found"))
                return@put
            }

            val isStaff = isModerator(user)

            if (thread.userId != user.id && !isStaff) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("You can only edit your own threads"))
                return@put
            }

            if (thread.locked && !isStaff) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("This thread is locked"))
                return@put
            }

            val title = (
                if (data.containsKey("title")) data["title"]?.jsonPrimitive?.contentOrNull else thread.title
            ) ?: ""
            val titleTrimmed = title.trim()

            val content = (
                if (data.containsKey("content")) data["content"]?.jsonPrimitive?.contentOrNull else thread.content
            ) ?: ""
            val contentTrimmed = content.trim()

            if (titleTrimmed.isEmpty() || contentTrimmed.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Title and content cannot be empty"))
                return@put
            }

            if (titleTrimmed.length > MAX_TITLE_LEN) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Title must be at most $MAX_TITLE_LEN characters"))
                return@put
            }

            if (contentTrimmed.length > MAX_CONTENT_LEN) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Content must be at most $MAX_CONTENT_LEN characters"))
                return@put
            }

            connection.prepareStatement(
                "UPDATE threads SET title = ?, content = ? WHERE id = ?"
            ).use { stmt ->
                stmt.setString(1, titleTrimmed)
                stmt.setString(2, contentTrimmed)
                stmt.setLong(3, threadId)
                stmt.executeUpdate()
            }

            val firstPostId = connection.prepareStatement(
                "SELECT MIN(id) AS first_id FROM posts WHERE thread_id = ?"
            ).use { stmt ->
                stmt.setLong(1, threadId)

                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        val id = rs.getLong("first_id")
                        if (rs.wasNull()) null else id
                    } else {
                        null
                    }
                }
            }

            if (firstPostId != null) {
                connection.prepareStatement(
                    "UPDATE posts SET content = ? WHERE id = ?"
                ).use { stmt ->
                    stmt.setString(1, contentTrimmed)
                    stmt.setLong(2, firstPostId)
                    stmt.executeUpdate()
                }
            }

            call.respond(
                HttpStatusCode.OK,
                EditThreadResponse(
                    message = "Thread updated",
                    title = titleTrimmed,
                    content = contentTrimmed,
                    contentHtml = MarkdownRenderer.render(contentTrimmed)
                )
            )
        }
    }

    // DELETE THREAD
    delete("/api/threads/{threadId}") {
        val threadId = call.parameters["threadId"]?.toLongOrNull()

        if (threadId == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Thread not found"))
            return@delete
        }

        val token = call.bearerToken()

        if (token == null) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Authentication required"))
            return@delete
        }

        db().use { connection ->
            val user = getUserFromToken(connection, token)

            if (user == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid or expired session"))
                return@delete
            }

            val threadUserId = connection.prepareStatement(
                "SELECT user_id FROM threads WHERE id = ?"
            ).use { stmt ->
                stmt.setLong(1, threadId)

                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getLong("user_id") else null
                }
            }

            if (threadUserId == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Thread not found"))
                return@delete
            }

            if (threadUserId != user.id && !isModerator(user)) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("You can only delete your own threads"))
                return@delete
            }

            connection.prepareStatement(
                """
                DELETE FROM reactions
                WHERE post_id IN (
                    SELECT id FROM posts WHERE thread_id = ?
                )
                """.trimIndent()
            ).use { stmt ->
                stmt.setLong(1, threadId)
                stmt.executeUpdate()
            }

            connection.prepareStatement(
                """
                DELETE FROM reports
                WHERE thread_id = ?
                   OR post_id IN (
                        SELECT id FROM posts WHERE thread_id = ?
                   )
                """.trimIndent()
            ).use { stmt ->
                stmt.setLong(1, threadId)
                stmt.setLong(2, threadId)
                stmt.executeUpdate()
            }

            connection.prepareStatement(
                "DELETE FROM posts WHERE thread_id = ?"
            ).use { stmt ->
                stmt.setLong(1, threadId)
                stmt.executeUpdate()
            }

            connection.prepareStatement(
                "DELETE FROM threads WHERE id = ?"
            ).use { stmt ->
                stmt.setLong(1, threadId)
                stmt.executeUpdate()
            }

            call.respond(HttpStatusCode.OK, MessageResponse("Thread deleted"))
        }
    }

    // PIN THREAD
    post("/api/threads/{threadId}/pin") {
        val threadId = call.parameters["threadId"]?.toLongOrNull()

        if (threadId == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Thread not found"))
            return@post
        }

        val token = call.bearerToken()

        if (token == null) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Authentication required"))
            return@post
        }

        db().use { connection ->
            val user = getUserFromToken(connection, token)

            if (user == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid or expired session"))
                return@post
            }

            if (!isModerator(user)) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                return@post
            }

            val pinned = connection.prepareStatement(
                "SELECT pinned FROM threads WHERE id = ?"
            ).use { stmt ->
                stmt.setLong(1, threadId)

                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getInt("pinned") != 0 else null
                }
            }

            if (pinned == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Thread not found"))
                return@post
            }

            val newState = !pinned

            connection.prepareStatement(
                "UPDATE threads SET pinned = ? WHERE id = ?"
            ).use { stmt ->
                stmt.setInt(1, if (newState) 1 else 0)
                stmt.setLong(2, threadId)
                stmt.executeUpdate()
            }

            call.respond(HttpStatusCode.OK, PinResponse(pinned = newState))
        }
    }

    // LOCK THREAD
    post("/api/threads/{threadId}/lock") {
        val threadId = call.parameters["threadId"]?.toLongOrNull()

        if (threadId == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Thread not found"))
            return@post
        }

        val token = call.bearerToken()

        if (token == null) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Authentication required"))
            return@post
        }

        db().use { connection ->
            val user = getUserFromToken(connection, token)

            if (user == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid or expired session"))
                return@post
            }

            if (!isModerator(user)) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                return@post
            }

            val locked = connection.prepareStatement(
                "SELECT locked FROM threads WHERE id = ?"
            ).use { stmt ->
                stmt.setLong(1, threadId)

                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getInt("locked") != 0 else null
                }
            }

            if (locked == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Thread not found"))
                return@post
            }

            val newState = !locked

            connection.prepareStatement(
                "UPDATE threads SET locked = ? WHERE id = ?"
            ).use { stmt ->
                stmt.setInt(1, if (newState) 1 else 0)
                stmt.setLong(2, threadId)
                stmt.executeUpdate()
            }

            call.respond(HttpStatusCode.OK, LockResponse(locked = newState))
        }
    }
}
