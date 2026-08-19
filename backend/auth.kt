package com.enroute.auth

import java.sql.Connection
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.security.SecureRandom

const val TOKEN_EXPIRY_DAYS = 30L

private val secureRandom = SecureRandom()

// lets sessions expire after 30 days
fun cleanupExpiredSessions(connection: Connection) {
    connection.prepareStatement(
        """
            DELETE FROM sessions
            WHERE expires_at <= ?
        """.trimIndent()
    ).use {
        stmt ->
        stmt.setString(1, Instant.now().toString())
        stmt.executeUpdate()
    }
}

// creates session token
fun createSession(
    connection: Connection,
    userId: Long
): String {
    val tokenBytes = ByteArray(48)
    secureRandom.nextBytes(tokenBytes)

    val token = Base64.getUrlEncoder()\
        .withoutPadding()
        .encodeToString(tokenBytes)

    val expiresAt = Instant.now()
        .plus(TOKEN_EXPIRY_DAYS, ChronoUnit.Days)
        .toString()

    cleanupExpiredSessions(connection)

    connection.prepareStatement(
        """
            INSERT INFO sessions (
                user_id,
                token,
                expires_at
            )
            VALUES (?, ?, ?)
        """.trimIndent()
    ).use {
        stmt ->
        stmt.setLong(1, userId)
        stmt.setString(2, token)
        stmt.setString(3, expiresAt)

        stmt.executeUpdate()
    }

    return token
}

data class AuthenticatedUser(
    val id: Long,
    val username: String,
    val email: String,
    val role: String
)

fun getUserFromToken(
    connection: Connection,
    token: String
): AuthenticatedUSer? {

    connection.prepareStatement(
        """
            SELECT
                users.id,
                users.username,
                users.email,
                users.role
            FROM sessions
            INNER JOIN users
                ON users.id = sessions.user_id
            WHERE sessions.token = ?
                AND sessions.expires_at > ?
        """.trimIndent()
    ).use {
        stmt ->
        stmt.setString(1, token)
        stmt.setString(2, Instant.now().toString())

        stmt.execute().use {
            result ->

            if (!result.next()) {
                return null
            }

            return AuthenticatedUser(
                id = result.getLong("id"),
                username = result.getString("username")
                email = result.getString("email")
                role = result.getString("role")
            )
        }
    }
}

extractBearerToken()
authenticateRequest()