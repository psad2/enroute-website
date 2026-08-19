import com.enroute.auth.getUserFromToken
import com.enroute.auth.isModerator

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

import java.sql.ResultSet

fun ResultSet.getLongOrNull(column: String): Long? {
    val value = getLong(column)
    return if (wasNull()) null else value
}

@Serializable
data class ReportItem(
    val id: Long,
    val postId: Long?,
    val threadId: Long?,
    val reporterId: Long,
    val reason: String,
    val status: String,
    val createdAt: String,
    val reporter: String,
    val postContent: String?,
    val threadTitle: String?
)

fun Route.reportsRoute() {

    // This reports one post for moderator review.
    post("/api/posts/{postId}/report") {
        val postId = call.parameters["postId"]?.toLongOrNull()

        if (postId == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Post not found."))
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

        val reason = (data["reason"]?.jsonPrimitive?.contentOrNull ?: "").trim()

        if (reason.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("A report reason is required."))
            return@post
        }

        if (reason.length > MAX_REASON_LEN) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Reason must be at most $MAX_REASON_LEN characters"))
            return@post
        }

        db().use { connection ->
            val user = getUserFromToken(connection, token)

            if (user == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid or expired session"))
                return@post
            }

            val threadId = connection.prepareStatement(
                "SELECT thread_id FROM posts WHERE id = ?"
            ).use { stmt ->
                stmt.setLong(1, postId)

                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getLong("thread_id") else null
                }
            }

            if (threadId == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Post not found."))
                return@post
            }

            val alreadyReported = connection.prepareStatement(
                """
                SELECT id
                FROM reports
                WHERE post_id = ?
                  AND reporter_id = ?
                  AND status = 'open'
                """.trimIndent()
            ).use { stmt ->
                stmt.setLong(1, postId)
                stmt.setLong(2, user.id)
                stmt.executeQuery().use { rs -> rs.next() }
            }

            if (alreadyReported) {
                call.respond(HttpStatusCode.Conflict, ErrorResponse("You have already reported this post."))
                return@post
            }

            connection.prepareStatement(
                """
                INSERT INTO reports (
                    post_id,
                    thread_id,
                    reporter_id,
                    reason,
                    status
                )
                VALUES (?, ?, ?, ?, 'open')
                """.trimIndent()
            ).use { stmt ->
                stmt.setLong(1, postId)
                stmt.setLong(2, threadId)
                stmt.setLong(3, user.id)
                stmt.setString(4, reason)
                stmt.executeUpdate()
            }

            call.respond(
                HttpStatusCode.Created,
                MessageResponse("Report submitted successfully.")
            )
        }
    }

    // This reports a post, a thread, or both, for moderator review.
    post("/api/reports") {
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

        val postId = data["post_id"]?.jsonPrimitive?.longOrNull
        var threadId = data["thread_id"]?.jsonPrimitive?.longOrNull

        val reason = (data["reason"]?.jsonPrimitive?.contentOrNull ?: "").trim()

        if (reason.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Reason is required"))
            return@post
        }

        if (postId == null && threadId == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Either post_id or thread_id is required"))
            return@post
        }

        if (reason.length > MAX_REASON_LEN) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Reason must be at most $MAX_REASON_LEN characters"))
            return@post
        }

        db().use { connection ->
            val user = getUserFromToken(connection, token)

            if (user == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid or expired session"))
                return@post
            }

            if (postId != null) {
                val postThreadId = connection.prepareStatement(
                    "SELECT thread_id FROM posts WHERE id = ?"
                ).use { stmt ->
                    stmt.setLong(1, postId)

                    stmt.executeQuery().use { rs ->
                        if (rs.next()) rs.getLong("thread_id") else null
                    }
                }

                if (postThreadId == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Post not found"))
                    return@post
                }

                threadId = threadId ?: postThreadId
            }

            // This copies threadId into a val. Kotlin cannot smart-cast a var
            // once a closure below, db().use { }, could change it from another
            // thread. The val below has a fixed value, so the null check on
            // it holds for the rest of this block.
            val currentThreadId = threadId

            if (currentThreadId != null) {
                val threadExists = connection.prepareStatement(
                    "SELECT id FROM threads WHERE id = ?"
                ).use { stmt ->
                    stmt.setLong(1, currentThreadId)
                    stmt.executeQuery().use { rs -> rs.next() }
                }

                if (!threadExists) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Thread not found"))
                    return@post
                }
            }

            connection.prepareStatement(
                """
                INSERT INTO reports (
                    post_id,
                    thread_id,
                    reporter_id,
                    reason,
                    status
                )
                VALUES (?, ?, ?, ?, 'open')
                """.trimIndent()
            ).use { stmt ->
                stmt.setNullableLong(1, postId)
                stmt.setNullableLong(2, threadId)
                stmt.setLong(3, user.id)
                stmt.setString(4, reason)
                stmt.executeUpdate()
            }

            call.respond(HttpStatusCode.Created, MessageResponse("Report submitted"))
        }
    }

    // This lists every open report, for a moderator to review.
    get("/api/reports") {
        val token = call.bearerToken()

        if (token == null) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Authentication required"))
            return@get
        }

        db().use { connection ->
            val user = getUserFromToken(connection, token)

            if (user == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid or expired session"))
                return@get
            }

            if (!isModerator(user)) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                return@get
            }

            val reports = connection.prepareStatement(
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
                """.trimIndent()
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    val result = mutableListOf<ReportItem>()

                    while (rs.next()) {
                        result.add(
                            ReportItem(
                                id = rs.getLong("id"),
                                postId = rs.getLongOrNull("post_id"),
                                threadId = rs.getLongOrNull("thread_id"),
                                reporterId = rs.getLong("reporter_id"),
                                reason = rs.getString("reason"),
                                status = rs.getString("status"),
                                createdAt = rs.getString("created_at"),
                                reporter = rs.getString("reporter"),
                                postContent = rs.getString("post_content"),
                                threadTitle = rs.getString("thread_title")
                            )
                        )
                    }

                    result
                }
            }

            call.respond(HttpStatusCode.OK, reports)
        }
    }

    // This marks an open report as resolved.
    put("/api/reports/{reportId}") {
        val reportId = call.parameters["reportId"]?.toLongOrNull()

        if (reportId == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Report not found"))
            return@put
        }

        val token = call.bearerToken()

        if (token == null) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Authentication required"))
            return@put
        }

        db().use { connection ->
            val user = getUserFromToken(connection, token)

            if (user == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid or expired session"))
                return@put
            }

            if (!isModerator(user)) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                return@put
            }

            val exists = connection.prepareStatement(
                "SELECT id FROM reports WHERE id = ?"
            ).use { stmt ->
                stmt.setLong(1, reportId)
                stmt.executeQuery().use { rs -> rs.next() }
            }

            if (!exists) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Report not found"))
                return@put
            }

            connection.prepareStatement(
                "UPDATE reports SET status = 'resolved' WHERE id = ?"
            ).use { stmt ->
                stmt.setLong(1, reportId)
                stmt.executeUpdate()
            }

            call.respond(HttpStatusCode.OK, MessageResponse("Report resolved"))
        }
    }
}
