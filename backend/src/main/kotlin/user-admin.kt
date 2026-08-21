import com.enroute.auth.AuthenticatedUser
import com.enroute.auth.deleteAllUserSessions
import com.enroute.auth.getUserFromToken
import com.enroute.auth.isAdmin
import com.enroute.auth.isModerator

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.time.Instant

// This is the user row shape the admin client works with. It is deliberately
// richer than PublicUser (request-helper.kt), since a moderator needs to see
// role and ban/timeout state that a normal API consumer never sees.
@Serializable
data class AdminUserView(
    val id: Long,
    val username: String,
    val email: String,
    val role: String,
    val banned: Boolean,
    val timeoutUntil: String?,
    val joinedAt: String?
)

@Serializable
data class AdminUserListResponse(
    val users: List<AdminUserView>,
    val page: Int,
    val perPage: Int,
    val total: Int
)

@Serializable
data class RoleChangeRequest(val role: String?)

@Serializable
data class TimeoutRequest(val minutes: Int?)

// This loads the caller from their bearer token and checks they are at
// least a moderator. It writes the appropriate error response and returns
// null when the caller should not proceed any further.
private suspend fun io.ktor.server.application.ApplicationCall.requireModerator(
    connection: Connection
): AuthenticatedUser? {
    val token = bearerToken()

    if (token == null) {
        respond(HttpStatusCode.Unauthorized, ErrorResponse("Authentication required"))
        return null
    }

    val user = getUserFromToken(connection, token)

    if (user == null) {
        respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid or expired session"))
        return null
    }

    if (!isModerator(user)) {
        respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
        return null
    }

    return user
}

private fun loadAdminUser(connection: Connection, userId: Long): AdminUserView? {
    return connection.prepareStatement(
        """
        SELECT id, username, email, role, banned, timeout_until, joined_at
        FROM users
        WHERE id = ?
        """.trimIndent()
    ).use { stmt ->
        stmt.setLong(1, userId)
        stmt.executeQuery().use { rs ->
            if (!rs.next()) {
                null
            } else {
                AdminUserView(
                    id = rs.getLong("id"),
                    username = rs.getString("username"),
                    email = rs.getString("email"),
                    role = rs.getString("role"),
                    banned = rs.getInt("banned") != 0,
                    timeoutUntil = rs.getString("timeout_until"),
                    joinedAt = rs.getString("joined_at")
                )
            }
        }
    }
}

// This counts how many non-banned admins currently exist. It is used to
// stop the last admin account from being demoted, banned, or deleted,
// which would otherwise lock everyone out of the admin tooling.
private fun activeAdminCount(connection: Connection): Int {
    return connection.prepareStatement(
        """
        SELECT COUNT(*) AS count
        FROM users
        WHERE role = 'admin' AND banned = 0
        """.trimIndent()
    ).use { stmt ->
        stmt.executeQuery().use { rs ->
            rs.next()
            rs.getInt("count")
        }
    }
}

fun Route.userAdminRoute() {

    // Lists/searches users. Supports ?query= against username/email, plus
    // the usual ?page=&per_page= pagination helper from request-helper.kt.
    get("/api/admin/users") {
        db().use { connection ->
            if (call.requireModerator(connection) == null) return@get

            val pagination = call.paginateArgs()
            val query = call.request.queryParameters["query"]?.trim()

            val (where, params) = if (query.isNullOrBlank()) {
                "" to emptyList()
            } else {
                "WHERE username LIKE ? OR email LIKE ?" to listOf("%$query%", "%$query%")
            }

            val total = connection.prepareStatement(
                "SELECT COUNT(*) AS count FROM users $where"
            ).use { stmt ->
                params.forEachIndexed { i, p -> stmt.setString(i + 1, p) }
                stmt.executeQuery().use { rs -> rs.next(); rs.getInt("count") }
            }

            val users = connection.prepareStatement(
                """
                SELECT id, username, email, role, banned, timeout_until, joined_at
                FROM users
                $where
                ORDER BY id ASC
                LIMIT ? OFFSET ?
                """.trimIndent()
            ).use { stmt ->
                var i = 1
                for (p in params) { stmt.setString(i, p); i++ }
                stmt.setInt(i, pagination.perPage); i++
                stmt.setInt(i, pagination.offset)

                stmt.executeQuery().use { rs ->
                    val results = mutableListOf<AdminUserView>()
                    while (rs.next()) {
                        results.add(
                            AdminUserView(
                                id = rs.getLong("id"),
                                username = rs.getString("username"),
                                email = rs.getString("email"),
                                role = rs.getString("role"),
                                banned = rs.getInt("banned") != 0,
                                timeoutUntil = rs.getString("timeout_until"),
                                joinedAt = rs.getString("joined_at")
                            )
                        )
                    }
                    results
                }
            }

            call.respond(
                HttpStatusCode.OK,
                AdminUserListResponse(
                    users = users,
                    page = pagination.page,
                    perPage = pagination.perPage,
                    total = total
                )
            )
        }
    }

    get("/api/admin/users/{id}") {
        db().use { connection ->
            if (call.requireModerator(connection) == null) return@get

            val userId = call.parameters["id"]?.toLongOrNull()
            if (userId == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                return@get
            }

            val user = loadAdminUser(connection, userId)
            if (user == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                return@get
            }

            call.respond(HttpStatusCode.OK, user)
        }
    }

    // Changing a role is admin-only. A moderator cannot promote/demote anyone,
    // including themselves.
    patch("/api/admin/users/{id}/role") {
        db().use { connection ->
            val caller = call.requireModerator(connection) ?: return@patch

            if (!isAdmin(caller)) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("Only an admin can change roles"))
                return@patch
            }

            val userId = call.parameters["id"]?.toLongOrNull()
            if (userId == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                return@patch
            }

            val body = try {
                call.receive<RoleChangeRequest>()
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid JSON body"))
                return@patch
            }

            val newRoleName = body.role?.trim()?.lowercase()
            if (newRoleName !in listOf("user", "moderator", "admin")) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Role must be user, moderator, or admin"))
                return@patch
            }

            val target = loadAdminUser(connection, userId)
            if (target == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                return@patch
            }

            // This stops the very last admin from demoting themselves (or
            // being demoted) and leaving nobody able to manage the site.
            if (
                target.role == "admin" &&
                newRoleName != "admin" &&
                activeAdminCount(connection) <= 1
            ) {
                call.respond(HttpStatusCode.Conflict, ErrorResponse("Cannot demote the last remaining admin"))
                return@patch
            }

            connection.prepareStatement(
                "UPDATE users SET role = ? WHERE id = ?"
            ).use { stmt ->
                stmt.setString(1, newRoleName)
                stmt.setLong(2, userId)
                stmt.executeUpdate()
            }

            call.respond(HttpStatusCode.OK, MessageResponse("Role updated to $newRoleName"))
        }
    }

    // A timeout is temporary: it sets timeout_until minutes into the future
    // and immediately revokes the user's existing sessions, so they are
    // logged out right away rather than only at their next login attempt.
    post("/api/admin/users/{id}/timeout") {
        db().use { connection ->
            if (call.requireModerator(connection) == null) return@post

            val userId = call.parameters["id"]?.toLongOrNull()
            if (userId == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                return@post
            }

            val body = try {
                call.receive<TimeoutRequest>()
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid JSON body"))
                return@post
            }

            val minutes = body.minutes
            if (minutes == null || minutes <= 0 || minutes > 60 * 24 * 365) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("minutes must be between 1 and 525600"))
                return@post
            }

            if (loadAdminUser(connection, userId) == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                return@post
            }

            val until = Instant.now().plusSeconds(minutes * 60L).toString()

            connection.prepareStatement(
                "UPDATE users SET timeout_until = ? WHERE id = ?"
            ).use { stmt ->
                stmt.setString(1, until)
                stmt.setLong(2, userId)
                stmt.executeUpdate()
            }

            deleteAllUserSessions(connection, userId)

            call.respond(HttpStatusCode.OK, MessageResponse("User timed out until $until"))
        }
    }

    post("/api/admin/users/{id}/untimeout") {
        db().use { connection ->
            if (call.requireModerator(connection) == null) return@post

            val userId = call.parameters["id"]?.toLongOrNull()
            if (userId == null || loadAdminUser(connection, userId) == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                return@post
            }

            connection.prepareStatement(
                "UPDATE users SET timeout_until = NULL WHERE id = ?"
            ).use { stmt ->
                stmt.setLong(1, userId)
                stmt.executeUpdate()
            }

            call.respond(HttpStatusCode.OK, MessageResponse("Timeout cleared"))
        }
    }

    // A ban is indefinite, unlike a timeout. It also revokes all sessions
    // immediately, and is blocked against the last remaining admin account.
    post("/api/admin/users/{id}/ban") {
        db().use { connection ->
            if (call.requireModerator(connection) == null) return@post

            val userId = call.parameters["id"]?.toLongOrNull()
            val target = userId?.let { loadAdminUser(connection, it) }

            if (target == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                return@post
            }

            if (target.role == "admin" && activeAdminCount(connection) <= 1) {
                call.respond(HttpStatusCode.Conflict, ErrorResponse("Cannot ban the last remaining admin"))
                return@post
            }

            connection.prepareStatement(
                "UPDATE users SET banned = 1 WHERE id = ?"
            ).use { stmt ->
                stmt.setLong(1, userId)
                stmt.executeUpdate()
            }

            deleteAllUserSessions(connection, userId)

            call.respond(HttpStatusCode.OK, MessageResponse("User banned"))
        }
    }

    post("/api/admin/users/{id}/unban") {
        db().use { connection ->
            if (call.requireModerator(connection) == null) return@post

            val userId = call.parameters["id"]?.toLongOrNull()
            if (userId == null || loadAdminUser(connection, userId) == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                return@post
            }

            connection.prepareStatement(
                "UPDATE users SET banned = 0 WHERE id = ?"
            ).use { stmt ->
                stmt.setLong(1, userId)
                stmt.executeUpdate()
            }

            call.respond(HttpStatusCode.OK, MessageResponse("User unbanned"))
        }
    }

    // Deleting a user is admin-only and irreversible. Threads/posts/etc. all
    // reference users.id with ON DELETE CASCADE, so this also removes
    // everything that user authored.
    delete("/api/admin/users/{id}") {
        db().use { connection ->
            val caller = call.requireModerator(connection) ?: return@delete

            if (!isAdmin(caller)) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("Only an admin can delete a user"))
                return@delete
            }

            val userId = call.parameters["id"]?.toLongOrNull()
            val target = userId?.let { loadAdminUser(connection, it) }

            if (target == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                return@delete
            }

            if (target.role == "admin" && activeAdminCount(connection) <= 1) {
                call.respond(HttpStatusCode.Conflict, ErrorResponse("Cannot delete the last remaining admin"))
                return@delete
            }

            connection.prepareStatement(
                "DELETE FROM users WHERE id = ?"
            ).use { stmt ->
                stmt.setLong(1, userId)
                stmt.executeUpdate()
            }

            call.respond(HttpStatusCode.OK, MessageResponse("User deleted"))
        }
    }
}
