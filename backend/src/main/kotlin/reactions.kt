import com.enroute.auth.getUserFromToken

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

import java.sql.Connection
import java.sql.ResultSet

// This is a posts row joined with the poster's username.
// It is the raw form, before serializePost adds derived fields.
data class PostRecord(
    val id: Long,
    val threadId: Long,
    val content: String,
    val userId: Long,
    val username: String,
    val parentPostId: Long?,
    val quotePostId: Long?,
    val createdAt: String
)

fun ResultSet.toPostRecord(): PostRecord {
    return PostRecord(
        id = getLong("id"),
        threadId = getLong("thread_id"),
        content = getString("content"),
        userId = getLong("user_id"),
        username = getString("username"),
        parentPostId = getLong("parent_post_id").let { if (wasNull()) null else it },
        quotePostId = getLong("quote_post_id").let { if (wasNull()) null else it },
        createdAt = getString("created_at")
    )
}

@Serializable
data class ReactionCounts(
    val like: Int = 0,
    val love: Int = 0,
    val laugh: Int = 0,
    val wow: Int = 0,
    val sad: Int = 0
)

fun emptyReactionCounts(): ReactionCounts {
    return ReactionCounts()
}

fun getReactionCounts(connection: Connection, postId: Long): ReactionCounts {
    val counts = mutableMapOf(
        "like" to 0,
        "love" to 0,
        "laugh" to 0,
        "wow" to 0,
        "sad" to 0
    )

    connection.prepareStatement(
        """
        SELECT reaction, COUNT(*) AS count
        FROM reactions
        WHERE post_id = ?
        GROUP BY reaction
        """.trimIndent()
    ).use { stmt ->
        stmt.setLong(1, postId)

        stmt.executeQuery().use { rs ->
            while (rs.next()) {
                val reaction = rs.getString("reaction")

                if (counts.containsKey(reaction)) {
                    counts[reaction] = rs.getInt("count")
                }
            }
        }
    }

    return ReactionCounts(
        like = counts.getValue("like"),
        love = counts.getValue("love"),
        laugh = counts.getValue("laugh"),
        wow = counts.getValue("wow"),
        sad = counts.getValue("sad")
    )
}

fun getUserReaction(connection: Connection, postId: Long, userId: Long?): String? {
    if (userId == null) {
        return null
    }

    connection.prepareStatement(
        """
        SELECT reaction
        FROM reactions
        WHERE post_id = ?
          AND user_id = ?
        """.trimIndent()
    ).use { stmt ->
        stmt.setLong(1, postId)
        stmt.setLong(2, userId)

        stmt.executeQuery().use { rs ->
            return if (rs.next()) rs.getString("reaction") else null
        }
    }
}

@Serializable
data class QuotedPostSummary(
    val id: Long,
    val threadId: Long,
    val content: String,
    val contentHtml: String,
    val createdAt: String,
    val userId: Long,
    val username: String
)

@Serializable
data class ParentPostSummary(
    val id: Long,
    val content: String,
    val userId: Long,
    val username: String
)

@Serializable
data class SerializedPost(
    val id: Long,
    val threadId: Long,
    val content: String,
    val contentHtml: String,
    val userId: Long,
    val username: String,
    val parentPostId: Long?,
    val quotePostId: Long?,
    val createdAt: String,
    val reactions: ReactionCounts,
    val userReaction: String?,
    var quotedPost: QuotedPostSummary? = null,
    var parentPost: ParentPostSummary? = null
)

// This adds contentHtml, reactions, and userReaction to a raw post row.
// It matches serialize_post() in app.py.
fun serializePost(connection: Connection, post: PostRecord, userId: Long? = null): SerializedPost {
    return SerializedPost(
        id = post.id,
        threadId = post.threadId,
        content = post.content,
        contentHtml = MarkdownRenderer.render(post.content),
        userId = post.userId,
        username = post.username,
        parentPostId = post.parentPostId,
        quotePostId = post.quotePostId,
        createdAt = post.createdAt,
        reactions = getReactionCounts(connection, post.id),
        userReaction = getUserReaction(connection, post.id, userId)
    )
}

@Serializable
data class ReactionResponse(
    val success: Boolean,
    val reaction: String?,
    val userReaction: String?,
    val counts: ReactionCounts
)

fun Route.reactionRoutes() {
    post("/api/posts/{postId}/reaction") {
        val postId = call.parameters["postId"]?.toLongOrNull()

        if (postId == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Post not found"))
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

        val reaction = (data["reaction"]?.jsonPrimitive?.contentOrNull ?: "").trim().lowercase()

        if (reaction !in ALLOWED_REACTIONS) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid reaction"))
            return@post
        }

        db().use { connection ->
            val user = getUserFromToken(connection, token)

            if (user == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid or expired session"))
                return@post
            }

            val postExists = connection.prepareStatement(
                """
                SELECT id
                FROM posts
                WHERE id = ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setLong(1, postId)

                stmt.executeQuery().use { rs -> rs.next() }
            }

            if (!postExists) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Post not found"))
                return@post
            }

            data class ExistingReaction(val id: Long, val reaction: String)

            val existing = connection.prepareStatement(
                """
                SELECT id, reaction
                FROM reactions
                WHERE post_id = ?
                  AND user_id = ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setLong(1, postId)
                stmt.setLong(2, user.id)

                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        ExistingReaction(rs.getLong("id"), rs.getString("reaction"))
                    } else {
                        null
                    }
                }
            }

            var activeReaction: String? = reaction

            if (existing != null) {
                if (existing.reaction == reaction) {
                    // A second click of the same reaction removes it.
                    connection.prepareStatement(
                        "DELETE FROM reactions WHERE id = ?"
                    ).use { stmt ->
                        stmt.setLong(1, existing.id)
                        stmt.executeUpdate()
                    }

                    activeReaction = null
                } else {
                    // A click of a different reaction switches to it.
                    connection.prepareStatement(
                        """
                        UPDATE reactions
                        SET reaction = ?,
                            created_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """.trimIndent()
                    ).use { stmt ->
                        stmt.setString(1, reaction)
                        stmt.setLong(2, existing.id)
                        stmt.executeUpdate()
                    }
                }
            } else {
                connection.prepareStatement(
                    """
                    INSERT INTO reactions (
                        post_id,
                        user_id,
                        reaction
                    )
                    VALUES (?, ?, ?)
                    """.trimIndent()
                ).use { stmt ->
                    stmt.setLong(1, postId)
                    stmt.setLong(2, user.id)
                    stmt.setString(3, reaction)
                    stmt.executeUpdate()
                }
            }

            val counts = getReactionCounts(connection, postId)

            call.respond(
                HttpStatusCode.OK,
                ReactionResponse(
                    success = true,
                    reaction = activeReaction,
                    userReaction = activeReaction,
                    counts = counts
                )
            )
        }
    }
}
