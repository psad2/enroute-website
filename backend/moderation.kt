import com.enroute.auth.getUserFromToken
import com.enroute.auth.isModerator

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete

fun Route.moderationRoute() {
    delete("/api/moderation/posts/{postId}") {
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

            if (!isModerator(user)) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                return@delete
            }

            val exists = connection.prepareStatement(
                "SELECT id FROM posts WHERE id = ?"
            ).use { stmt ->
                stmt.setLong(1, postId)
                stmt.executeQuery().use { rs -> rs.next() }
            }

            if (!exists) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Post not found"))
                return@delete
            }

            connection.prepareStatement(
                "UPDATE posts SET content = '[removed by moderation]' WHERE id = ?"
            ).use { stmt ->
                stmt.setLong(1, postId)
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

            call.respond(HttpStatusCode.OK, MessageResponse("Post removed by moderation"))
        }
    }
}
