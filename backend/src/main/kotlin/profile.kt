import com.enroute.auth.getUserFromToken
import com.enroute.auth.isAdmin

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
data class RecentThread(
    val id: Long,
    val title: String,
    val createdAt: String
)

@Serializable
data class ProfileResponse(
    val id: Long,
    val username: String,
    val bio: String,
    val role: String,
    val joinedAt: String?,
    val threadCount: Int,
    val postCount: Int,
    val reactionCount: Int,
    val recentThreads: List<RecentThread>
)

@Serializable
data class EditProfileResponse(
    val message: String,
    val bio: String
)

@Serializable
data class SetRoleResponse(
    val message: String,
    val role: String
)

val ALLOWED_ROLES = setOf("user", "moderator", "admin")

fun Route.profileRoute() {

    // This returns a user's public profile, with their recent activity.
    get("/api/users/{userId}") {
        val userId = call.parameters["userId"]?.toLongOrNull()

        if (userId == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
            return@get
        }

        db().use { connection ->
            data class UserRow(
                val id: Long,
                val username: String,
                val bio: String,
                val role: String,
                val joinedAt: String?
            )

            val user = connection.prepareStatement(
                """
                SELECT
                    id,
                    username,
                    bio,
                    role,
                    joined_at
                FROM users
                WHERE id = ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setLong(1, userId)

                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        UserRow(
                            id = rs.getLong("id"),
                            username = rs.getString("username"),
                            bio = rs.getString("bio") ?: "",
                            role = rs.getString("role"),
                            joinedAt = rs.getString("joined_at")
                        )
                    } else {
                        null
                    }
                }
            }

            if (user == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                return@get
            }

            val recentThreads = connection.prepareStatement(
                """
                SELECT
                    id,
                    title,
                    created_at
                FROM threads
                WHERE user_id = ?
                ORDER BY created_at DESC
                LIMIT 10
                """.trimIndent()
            ).use { stmt ->
                stmt.setLong(1, userId)

                stmt.executeQuery().use { rs ->
                    val result = mutableListOf<RecentThread>()

                    while (rs.next()) {
                        result.add(
                            RecentThread(
                                id = rs.getLong("id"),
                                title = rs.getString("title"),
                                createdAt = rs.getString("created_at")
                            )
                        )
                    }

                    result
                }
            }

            val threadCount = connection.prepareStatement(
                "SELECT COUNT(*) AS count FROM threads WHERE user_id = ?"
            ).use { stmt ->
                stmt.setLong(1, userId)
                stmt.executeQuery().use { rs -> rs.next(); rs.getInt("count") }
            }

            val postCount = connection.prepareStatement(
                "SELECT COUNT(*) AS count FROM posts WHERE user_id = ?"
            ).use { stmt ->
                stmt.setLong(1, userId)
                stmt.executeQuery().use { rs -> rs.next(); rs.getInt("count") }
            }

            val reactionCount = connection.prepareStatement(
                "SELECT COUNT(*) AS count FROM reactions WHERE user_id = ?"
            ).use { stmt ->
                stmt.setLong(1, userId)
                stmt.executeQuery().use { rs -> rs.next(); rs.getInt("count") }
            }

            call.respond(
                HttpStatusCode.OK,
                ProfileResponse(
                    id = user.id,
                    username = user.username,
                    bio = user.bio,
                    role = user.role,
                    joinedAt = user.joinedAt,
                    threadCount = threadCount,
                    postCount = postCount,
                    reactionCount = reactionCount,
                    recentThreads = recentThreads
                )
            )
        }
    }

    // This lets a user change their own bio.
    put("/api/users/{userId}") {
        val userId = call.parameters["userId"]?.toLongOrNull()

        if (userId == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
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

            if (user.id != userId) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("You can only edit your own profile"))
                return@put
            }

            val data = call.getJsonBody()

            if (data == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid JSON body"))
                return@put
            }

            val rawBio = (data["bio"]?.jsonPrimitive?.contentOrNull ?: "").trim()

            if (rawBio.length > MAX_BIO_LEN) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Bio must be at most $MAX_BIO_LEN characters"))
                return@put
            }

            val bio = MarkdownRenderer.sanitizePlain(rawBio)

            connection.prepareStatement(
                "UPDATE users SET bio = ? WHERE id = ?"
            ).use { stmt ->
                stmt.setString(1, bio)
                stmt.setLong(2, userId)
                stmt.executeUpdate()
            }

            call.respond(
                HttpStatusCode.OK,
                EditProfileResponse(
                    message = "Profile updated",
                    bio = bio
                )
            )
        }
    }

    // This lets an admin change another user's role.
    put("/api/users/{userId}/role") {
        val userId = call.parameters["userId"]?.toLongOrNull()

        if (userId == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
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

        val role = data["role"]?.jsonPrimitive?.contentOrNull

        if (role == null || role !in ALLOWED_ROLES) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("role must be one of: user, moderator, admin"))
            return@put
        }

        db().use { connection ->
            val user = getUserFromToken(connection, token)

            if (user == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid or expired session"))
                return@put
            }

            if (!isAdmin(user)) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                return@put
            }

            // This stops an admin from removing their own admin role by mistake.
            if (userId == user.id && role != "admin") {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("You cannot remove your own admin role"))
                return@put
            }

            val exists = connection.prepareStatement(
                "SELECT id FROM users WHERE id = ?"
            ).use { stmt ->
                stmt.setLong(1, userId)
                stmt.executeQuery().use { rs -> rs.next() }
            }

            if (!exists) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                return@put
            }

            connection.prepareStatement(
                "UPDATE users SET role = ? WHERE id = ?"
            ).use { stmt ->
                stmt.setString(1, role)
                stmt.setLong(2, userId)
                stmt.executeUpdate()
            }

            call.respond(
                HttpStatusCode.OK,
                SetRoleResponse(
                    message = "Role updated to $role",
                    role = role
                )
            )
        }
    }
}
