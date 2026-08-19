import com.enroute.auth.createSession
import com.enroute.auth.deleteSession
import com.enroute.auth.hashPassword
import com.enroute.auth.verifyPassword

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import java.util.Locale

// These are the request and response bodies for register, login, and logout.
@Serializable
data class RegisterRequest(
    val username: String?,
    val email: String?,
    val password: String?
)

@Serializable
data class LoginRequest(
    val username: String?,
    val password: String?
)

@Serializable
data class LoginResponse(
    val message: String,
    val token: String,
    val user: PublicUser
)

// This registers a new user account.
fun Route.registerRoute() {
    post("/api/register") {
        val clientKey = call.request.origin.remoteHost

        if (
            !registerLimiterPerMinute.tryAcquire(clientKey) ||
            !registerLimiterPerHour.tryAcquire(clientKey)
        ) {
            call.respond(
                HttpStatusCode.TooManyRequests,
                ErrorResponse(RATE_LIMIT_MESSAGE)
            )
            return@post
        }

        val data = try {
            call.receive<RegisterRequest>()
        } catch (_: Exception) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("Invalid JSON body")
            )
            return@post
        }

        val username = data.username?.trim() ?: ""
        val email = data.email?.trim()?.lowercase(Locale.ROOT) ?: ""
        val password = data.password ?: ""

        // This checks that all required fields are present and not blank.
        if (
            username.isBlank() ||
            email.isBlank() ||
            password.isBlank()
        ) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("All fields are required")
            )
            return@post
        }

        if (
            username.length !in 3..30 ||
            !username.matches(Regex("^[A-Za-z0-9_]+$"))
        ) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("Username must be 3-30 characters, letters/numbers/underscore only")
            )
            return@post
        }

        if (email.length > 254) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("Email address is too long")
            )
            return@post
        }

        if (!email.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("Invalid email address")
            )
            return@post
        }

        if (password.length < 8) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("Password must be at least 8 characters")
            )
            return@post
        }

        if (password.length > 128) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("Password must be at most 128 characters")
            )
            return@post
        }

        // This checks the username and email are free, then creates the user.
        db().use { connection ->
            val usernameTaken = connection.prepareStatement(
                """
                SELECT id
                FROM users
                WHERE LOWER(username) = LOWER(?)
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, username)
                stmt.executeQuery().use { result -> result.next() }
            }

            if (usernameTaken) {
                call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponse("Username already exists")
                )
                return@post
            }

            val emailTaken = connection.prepareStatement(
                """
                SELECT id
                FROM users
                WHERE LOWER(email) = LOWER(?)
                LIMIT 1
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, email)
                stmt.executeQuery().use { result -> result.next() }
            }

            if (emailTaken) {
                call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponse("Email already exists")
                )
                return@post
            }

            // This never stores the raw password, only the PBKDF2 hash.
            val passwordHash = hashPassword(password)

            connection.prepareStatement(
                """
                INSERT INTO users (
                    username,
                    email,
                    password,
                    bio,
                    role
                )
                VALUES (?, ?, ?, '', 'user')
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, username)
                stmt.setString(2, email)
                stmt.setString(3, passwordHash)

                stmt.executeUpdate()
            }

            call.respond(
                HttpStatusCode.Created,
                MessageResponse("Account created")
            )
        }
    }
}

// This logs in an existing user and creates a session.
fun Route.loginRoute() {
    post("/api/login") {
        val clientKey = call.request.origin.remoteHost

        if (!loginLimiterPerMinute.tryAcquire(clientKey)) {
            call.respond(
                HttpStatusCode.TooManyRequests,
                ErrorResponse(RATE_LIMIT_MESSAGE)
            )
            return@post
        }

        val data = try {
            call.receive<LoginRequest>()
        } catch (_: Exception) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("Invalid JSON body")
            )
            return@post
        }

        val username = data.username?.trim() ?: ""
        val password = data.password ?: ""

        if (
            username.isBlank() ||
            password.isBlank()
        ) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("Username and password are required")
            )
            return@post
        }

        if (!loginLimiterPerUsernamePerMinute.tryAcquire(username.lowercase())) {
            call.respond(
                HttpStatusCode.TooManyRequests,
                ErrorResponse(RATE_LIMIT_MESSAGE)
            )
            return@post
        }

        data class UserRecord(
            val id: Long,
            val username: String,
            val email: String,
            val passwordHash: String
        )

        db().use { connection ->
            // This looks up the user by username.
            val user = connection.prepareStatement(
                """
                SELECT
                    id,
                    username,
                    email,
                    password
                FROM users
                WHERE LOWER(username) = LOWER(?)
                LIMIT 1
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, username)
                stmt.executeQuery().use { result ->
                    if (result.next()) {
                        UserRecord(
                            id = result.getLong("id"),
                            username = result.getString("username"),
                            email = result.getString("email"),
                            passwordHash = result.getString("password")
                        )
                    } else {
                        null
                    }
                }
            }

            // This checks the password against the stored hash.
            if (
                user == null ||
                !verifyPassword(password, user.passwordHash)
            ) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse("Invalid username or password")
                )
                return@post
            }

            // This creates the session and returns the raw token to the client.
            val token = createSession(connection, user.id)

            call.respond(
                HttpStatusCode.OK,
                LoginResponse(
                    message = "Login successful",
                    token = token,
                    user = PublicUser(
                        id = user.id,
                        username = user.username,
                        email = user.email
                    )
                )
            )
        }
    }
}

// This ends the caller's session.
fun Route.logoutRoute() {
    post("/api/logout") {
        val token = call.bearerToken()

        if (token == null) {
            call.respond(
                HttpStatusCode.Unauthorized,
                ErrorResponse("Authentication required")
            )
            return@post
        }

        db().use { connection ->
            deleteSession(connection, token)
        }

        call.respond(
            HttpStatusCode.OK,
            MessageResponse("Logged out")
        )
    }
}
