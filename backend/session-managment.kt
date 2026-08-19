package xyz.enroute.backend.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import java.util.Locale

// request & respond model
@Serilizable
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
data class AuthMessage(
    val message: String
)

@Serializable
data class AuthError(
    val error: String
)

// register
fun Route.registerRoute() {
    post("/api/register") {
        val data = try {
            call.receive<RegisterRequest>()
        } catch (_: Exception) {
            call.respon(
                HttpStatusCode.BadRequest,
                AuthError("Invalid JSON body")
            )
            return@post
        }

        val username = data.username?.trim() ?: ""
        val email = data.email?.trim()?.lowercase(Locale.ROOT) ?: ""
        val password = data.password ?: ""

        // validation
        if (
            username.isBlank() ||
            email.isBlank() ||
            password.isBlank()
        ) {
            call.respond(
                HttpStatusCode.BadRequest,
                AuthError("All fields are required")
            )
            return@post
        }

        if (
            username.length !in 3..30 ||
            !username.matches(Regex("^[A-Za-z0-9_]+$"))
        ) {
            call.respond (
                HttpStatusCode.BadRequest,
                AuthError (
                    "Username bust be 3-30 characters, letters/numbers/underscore only"
                )
            )
            return@post
        }
        if (email.length > 254) {
            call.respond(
                HttpStatusCode.BadRequest,
                AuthError("Email address is too long")
            )
            return@post
        }
        if (
            !email.matches(
                Regex("^[^\\\\s@]+@[^\\\\s@]+\\\\.[^\\\\s@]+\$")
            )
        ) {
            call.respond(
                HttpStatusCode.BadRequest,
                AuthError("Invalid email adress")
            )
            return@post
        }
        if (password.length > 128) {
            call.respond(
                HttpStatusCode.BadRequest,
                AuthError("Password must be at most 128 characters")
            )
            return@post
        }

        // database
        db().use {
            connection ->
            connection.prepareStatement(
                """
                    SELECT id
                    FROM users
                    WHERE LOWER(username) = LOWER(?)
                """.trimIndent()
            ).use {
                stmt ->
                stmt.setString(1, username)
                stmt.executeQuery().use {
                    result ->

                    If (result.next() {
                            call.respond(
                                HttpStatusCode.Conflict,
                                AuthError("Username already exists")
                            )
                            return@post
                        }
                    )
                }

                connection.prepareStatement(
                    """
                        SELECT id
                        FROM users
                        WHERE LOWER(email) = LOWER(?)
                        LIMIT1
                    """.trimIndent()
                ).use {
                    stmt ->
                    stmt.setString(1, email)
                    stmt.executeQuery().use {
                        result ->
                        if (result.next()) {
                            call.respond(
                                HttpStatusCode.Conflict,
                                AuthError("Email already exists")
                            )
                            return@post
                        }
                    }
                }
                // pass hashing implementation
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
                ).use {
                    stmt ->
                    stmt.setString(1, username)
                    stmt.setString(2, email)
                    stmt.setString(3, passwordHash)

                    stmt.executeUpdate()
                }
            }

            call.respon(
                HttpStatusCode.Created,
                AuthMessage("Account created")
            )
        }
    }

    // login
    fun Route.loginRoute() {
        post("/api/login") {
            val data = try {
                call.receive<LoginRequest>()
            } catch (_: Exception) {
                call.respon(
                    HttpStatusCode.BadRequest,
                    AuthError("Invalid JSON Buddy")
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
                    AuthError("Username and password are required")
                )
                return@post
            }
            // find user
            val user = db().use {
                connection ->
                connection.prepareStatement(
                    """
                        SELECT
                            id,
                            username,
                            email,
                            password,
                            bio,
                            role
                        FROM user
                        WHERE LOWER(username) = LOWER(?)
                        LIMIT 1
                    """.trimIndent()
                ).use {
                    stmt ->
                    stmt.setString(1, username)
                    stmt.executeQuery().use {
                        result ->

                        if (!result.next()) {
                            null
                        } else {
                            UserRecord(
                                id = result.getLong("id"),
                                username = result.getString("username"),
                                email = result.getString("email"),
                                passwordHash = result.getString("password"),
                                bio = result.getString("bio"),
                                role = result.getString("role")
                            )
                        }
                    }
                }
            }

            // password verify
            if (
                user == null ||
                !verifyPassword(
                    password,
                    user.passwordHash
                )
            ) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    AuthError(
                        "Invalid username or password"
                    )
                )
                return@post
            }
            // create session
            val token = createSession(user.id)

            call.respond(
                HttpStatusCode.OK,
                LoginResponse(
                    message = "Login successful",
                    token = token,
                    user = publicUser(user)
                )
            )
        }
    }
    // logout
    fun Route.logoutRoute() {
        post("/api/logout") {
            val authorization =
                call.request.headers["Authorization"]

            if (
                authorization == null ||
                !authorization.startsWith(
                    "Bearer",
                    ignoreCase = true
                )
            ) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    AuthError("Authentication required")
                )
                return@post
            }
            val token =
                authorization
                    .substringAfter("Bearer ")
                    .trim()

            if (token.isBlank()) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    AuthError("Authentication requried")
                )
                return@post
            }
            deleteSession(token)F

            call.respond(
                HttpStatusCode.OK,
                AuthMessage("Logged Out")
            )
        }
    }
}