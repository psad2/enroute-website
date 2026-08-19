import com.enroute.auth.getUserFromToken

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

// mirrors public_user() -- the full user row minus the password
@Serializable
data class MeResponse(
    val id: Long,
    val username: String,
    val email: String,
    val bio: String,
    val role: String,
    val joinedAt: String?
)

fun Route.meRoute() {
    get("/api/me") {
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

            val me = connection.prepareStatement(
                """
                SELECT
                    id,
                    username,
                    email,
                    bio,
                    role,
                    joined_at
                FROM users
                WHERE id = ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setLong(1, user.id)

                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        MeResponse(
                            id = rs.getLong("id"),
                            username = rs.getString("username"),
                            email = rs.getString("email"),
                            bio = rs.getString("bio") ?: "",
                            role = rs.getString("role"),
                            joinedAt = rs.getString("joined_at")
                        )
                    } else {
                        null
                    }
                }
            }

            if (me == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid or expired session"))
                return@get
            }

            call.respond(HttpStatusCode.OK, me)
        }
    }
}
