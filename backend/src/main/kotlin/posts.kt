import com.enroute.auth.getUserFromToken
import com.enroute.auth.isModerator

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

@Serializable
data class ReplyResponse(
    val message: String,
    val post: SerializedPost
)

@Serializable
data class EditPostResponse(
    val message: String,
    val content: String,
    val contentHtml: String
)

fun Route.postsRoute() {

    // This adds a reply post to a thread.
    post("/api/threads/{threadId}/reply") {
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

        val data = call.getJsonBody()

        if (data == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid JSON body"))
            return@post
        }

        val content = (data["content"]?.jsonPrimitive?.contentOrNull ?: "").trim()

        if (content.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Content is required"))
            return@post
        }

        if (content.length > MAX_CONTENT_LEN) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Content must be at most $MAX_CONTENT_LEN characters"))
            return@post
        }

        db().use { connection ->
            val user = getUserFromToken(connection, token)

            if (user == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid or expired session"))
                return@post
            }

            val threadLocked = connection.prepareStatement(
                "SELECT locked FROM threads WHERE id = ?"
            ).use { stmt ->
                stmt.setLong(1, threadId)

                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getInt("locked") != 0 else null
                }
            }

            if (threadLocked == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Thread not found"))
                return@post
            }

            if (threadLocked && !isModerator(user)) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("This thread is locked"))
                return@post
            }

            var parentPostId: Long? = null
            val parentPostIdElement = data["parent_post_id"]

            if (parentPostIdElement != null) {
                parentPostId = parentPostIdElement.jsonPrimitive.longOrNull

                if (parentPostId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid parent_post_id"))
                    return@post
                }

                val parentExists = connection.prepareStatement(
                    "SELECT id FROM posts WHERE id = ? AND thread_id = ?"
                ).use { stmt ->
                    stmt.setLong(1, parentPostId)
                    stmt.setLong(2, threadId)
                    stmt.executeQuery().use { rs -> rs.next() }
                }

                if (!parentExists) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Parent post not found in this thread"))
                    return@post
                }
            }

            var quotePostId: Long? = null
            val quotePostIdElement = data["quote_post_id"]

            if (quotePostIdElement != null) {
                quotePostId = quotePostIdElement.jsonPrimitive.longOrNull

                if (quotePostId == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid quote_post_id"))
                    return@post
                }

                val quotedExists = connection.prepareStatement(
                    "SELECT id FROM posts WHERE id = ? AND thread_id = ?"
                ).use { stmt ->
                    stmt.setLong(1, quotePostId)
                    stmt.setLong(2, threadId)
                    stmt.executeQuery().use { rs -> rs.next() }
                }

                if (!quotedExists) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Quoted post not found in this thread"))
                    return@post
                }
            }

            val postId = connection.prepareStatement(
                """
                INSERT INTO posts (
                    thread_id,
                    content,
                    user_id,
                    parent_post_id,
                    quote_post_id
                )
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
                java.sql.Statement.RETURN_GENERATED_KEYS
            ).use { stmt ->
                stmt.setLong(1, threadId)
                stmt.setString(2, content)
                stmt.setLong(3, user.id)
                stmt.setNullableLong(4, parentPostId)
                stmt.setNullableLong(5, quotePostId)
                stmt.executeUpdate()

                stmt.generatedKeys.use { keys ->
                    keys.next()
                    keys.getLong(1)
                }
            }

            val postRecord = connection.prepareStatement(
                """
                SELECT
                    posts.*,
                    users.username

                FROM posts

                INNER JOIN users
                    ON users.id = posts.user_id

                WHERE posts.id = ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setLong(1, postId)

                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.toPostRecord()
                }
            }

            val postData = serializePost(connection, postRecord, user.id)

            if (quotePostId != null) {
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
                    """.trimIndent()
                ).use { stmt ->
                    stmt.setLong(1, quotePostId)

                    stmt.executeQuery().use { rs ->
                        if (rs.next()) {
                            val quotedContent = rs.getString("content")

                            postData.quotedPost = QuotedPostSummary(
                                id = rs.getLong("id"),
                                threadId = rs.getLong("thread_id"),
                                content = quotedContent,
                                contentHtml = MarkdownRenderer.render(quotedContent),
                                createdAt = rs.getString("created_at"),
                                userId = rs.getLong("user_id"),
                                username = rs.getString("username")
                            )
                        }
                    }
                }
            }

            call.respond(
                HttpStatusCode.Created,
                ReplyResponse(
                    message = "Reply posted",
                    post = postData
                )
            )
        }
    }

    // This edits the content of a post.
    put("/api/posts/{postId}") {
        val postId = call.parameters["postId"]?.toLongOrNull()

        if (postId == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Post not found"))
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

        val content = (data["content"]?.jsonPrimitive?.contentOrNull ?: "").trim()

        if (content.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Content is required"))
            return@put
        }

        if (content.length > MAX_CONTENT_LEN) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Content must be at most $MAX_CONTENT_LEN characters"))
            return@put
        }

        db().use { connection ->
            val user = getUserFromToken(connection, token)

            if (user == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid or expired session"))
                return@put
            }

            data class PostRow(val userId: Long, val threadId: Long)

            val post = connection.prepareStatement(
                "SELECT * FROM posts WHERE id = ?"
            ).use { stmt ->
                stmt.setLong(1, postId)

                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        PostRow(
                            userId = rs.getLong("user_id"),
                            threadId = rs.getLong("thread_id")
                        )
                    } else {
                        null
                    }
                }
            }

            if (post == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Post not found"))
                return@put
            }

            if (post.userId != user.id && !isModerator(user)) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("You can only edit your own posts"))
                return@put
            }

            val threadLocked = connection.prepareStatement(
                "SELECT locked FROM threads WHERE id = ?"
            ).use { stmt ->
                stmt.setLong(1, post.threadId)

                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getInt("locked") != 0 else null
                }
            }

            if (threadLocked == true && !isModerator(user)) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("This thread is locked"))
                return@put
            }

            connection.prepareStatement(
                "UPDATE posts SET content = ? WHERE id = ?"
            ).use { stmt ->
                stmt.setString(1, content)
                stmt.setLong(2, postId)
                stmt.executeUpdate()
            }

            // The thread's own content column is a copy of its first post.
            // This keeps that copy in step with the edit above.
            val firstPostId = connection.prepareStatement(
                "SELECT MIN(id) AS first_id FROM posts WHERE thread_id = ?"
            ).use { stmt ->
                stmt.setLong(1, post.threadId)

                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        val id = rs.getLong("first_id")
                        if (rs.wasNull()) null else id
                    } else {
                        null
                    }
                }
            }

            if (firstPostId == postId) {
                connection.prepareStatement(
                    "UPDATE threads SET content = ? WHERE id = ?"
                ).use { stmt ->
                    stmt.setString(1, content)
                    stmt.setLong(2, post.threadId)
                    stmt.executeUpdate()
                }
            }

            call.respond(
                HttpStatusCode.OK,
                EditPostResponse(
                    message = "Post updated",
                    content = content,
                    contentHtml = MarkdownRenderer.render(content)
                )
            )
        }
    }

    // This deletes a post's row entirely.
    delete("/api/posts/{postId}") {
        val postId = call.parameters["postId"]?.toLongOrNull()

        if (postId == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Post not found"))
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

            data class PostRow(val userId: Long, val threadId: Long)

            val post = connection.prepareStatement(
                "SELECT * FROM posts WHERE id = ?"
            ).use { stmt ->
                stmt.setLong(1, postId)

                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        PostRow(
                            userId = rs.getLong("user_id"),
                            threadId = rs.getLong("thread_id")
                        )
                    } else {
                        null
                    }
                }
            }

            if (post == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Post not found"))
                return@delete
            }

            if (post.userId != user.id && !isModerator(user)) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("You can only delete your own posts"))
                return@delete
            }

            // The first post in a thread holds the thread's own content.
            // Deleting it must not be allowed, since the thread itself
            // depends on it.
            val firstPostId = connection.prepareStatement(
                "SELECT MIN(id) AS first_id FROM posts WHERE thread_id = ?"
            ).use { stmt ->
                stmt.setLong(1, post.threadId)

                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        val id = rs.getLong("first_id")
                        if (rs.wasNull()) null else id
                    } else {
                        null
                    }
                }
            }

            if (firstPostId == postId) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("The original thread post cannot be deleted"))
                return@delete
            }

            connection.prepareStatement(
                """
                UPDATE posts
                SET quote_post_id = NULL,
                    parent_post_id = NULL
                WHERE quote_post_id = ?
                   OR parent_post_id = ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setLong(1, postId)
                stmt.setLong(2, postId)
                stmt.executeUpdate()
            }

            connection.prepareStatement(
                "DELETE FROM reactions WHERE post_id = ?"
            ).use { stmt ->
                stmt.setLong(1, postId)
                stmt.executeUpdate()
            }

            connection.prepareStatement(
                "DELETE FROM reports WHERE post_id = ?"
            ).use { stmt ->
                stmt.setLong(1, postId)
                stmt.executeUpdate()
            }

            connection.prepareStatement(
                "DELETE FROM posts WHERE id = ?"
            ).use { stmt ->
                stmt.setLong(1, postId)
                stmt.executeUpdate()
            }

            call.respond(HttpStatusCode.OK, MessageResponse("Post deleted"))
        }
    }
}
