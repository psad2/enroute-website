package com.enroute.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

const val TOKEN_EXPIRY_DAYS = 30L

private val secureRandom = SecureRandom()

data class AuthenticatedUser(
    val id: Long,
    val username: String,
    val email: String,
    val role: String
)

fun cleanupExpiredSessions(
    connection: Connection
) {
    connection.prepareStatement(
        """
        DELETE FROM sessions
        WHERE expires_at <= ?
        """.trimIndent()
    ).use { statement ->

        statement.setString(
            1,
            Instant.now().toString()
        )

        statement.executeUpdate()
    }
}

// This creates a new session for a user and returns the raw token.
// The token itself goes to the client. The database keeps only its hash
// (see hashToken below), so a stolen database dump does not give an
// attacker a usable session.
fun createSession(
    connection: Connection,
    userId: Long
): String {

    // SecureRandom, not a normal random generator, since this token
    // must not be guessable.
    val tokenBytes = ByteArray(48)

    secureRandom.nextBytes(tokenBytes)

    val token = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(tokenBytes)

    val tokenHash = hashToken(token)

    val expiresAt = Instant.now()
        .plus(
            TOKEN_EXPIRY_DAYS,
            ChronoUnit.DAYS
        )
        .toString()

    cleanupExpiredSessions(connection)

    connection.prepareStatement(
        """
        INSERT INTO sessions (
            user_id,
            token_hash,
            expires_at
        )
        VALUES (?, ?, ?)
        """.trimIndent()
    ).use { statement ->

        statement.setLong(
            1,
            userId
        )

        statement.setString(
            2,
            tokenHash
        )

        statement.setString(
            3,
            expiresAt
        )

        statement.executeUpdate()
    }

    return token
}

fun getUserFromToken(
    connection: Connection,
    token: String
): AuthenticatedUser? {

    val tokenHash = hashToken(token)

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
        WHERE sessions.token_hash = ?
          AND sessions.expires_at > ?
        """.trimIndent()
    ).use { statement ->

        statement.setString(
            1,
            tokenHash
        )

        statement.setString(
            2,
            Instant.now().toString()
        )

        statement.executeQuery().use { result ->

            if (!result.next()) {
                return null
            }

            return AuthenticatedUser(
                id = result.getLong("id"),
                username = result.getString("username"),
                email = result.getString("email"),
                role = result.getString("role")
            )
        }
    }
}

fun deleteSession(
    connection: Connection,
    token: String
) {

    val tokenHash = hashToken(token)

    connection.prepareStatement(
        """
        DELETE FROM sessions
        WHERE token_hash = ?
        """.trimIndent()
    ).use { statement ->

        statement.setString(
            1,
            tokenHash
        )

        statement.executeUpdate()
    }
}

fun deleteAllUserSessions(
    connection: Connection,
    userId: Long
) {

    connection.prepareStatement(
        """
        DELETE FROM sessions
        WHERE user_id = ?
        """.trimIndent()
    ).use { statement ->

        statement.setLong(
            1,
            userId
        )

        statement.executeUpdate()
    }
}

// This hashes a raw session token with SHA-256, for storage and lookup.
// The database never holds a raw token, only this hash. This is why a
// stolen sessions table row cannot be used to log in as the user.
private fun hashToken(
    token: String
): String {

    return MessageDigest
        .getInstance("SHA-256")
        .digest(
            token.toByteArray(Charsets.UTF_8)
        )
        .joinToString("") {
            "%02x".format(it)
        }
}

// 210,000 is the current OWASP-recommended minimum iteration count for
// PBKDF2-SHA256. A higher count makes each password guess slower to check.
private const val PBKDF2_ITERATIONS = 210_000
private const val PBKDF2_KEY_LENGTH_BITS = 256
private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"

// This hashes a password for storage. The stored string has the form
// pbkdf2_sha256$<iterations>$<base64 salt>$<base64 hash>.
// It carries its own salt and iteration count, so verifyPassword can
// check a password without any other stored state.
fun hashPassword(password: String): String {
    val salt = ByteArray(16)
    secureRandom.nextBytes(salt)

    val hash = pbkdf2(password, salt, PBKDF2_ITERATIONS)

    return listOf(
        "pbkdf2_sha256",
        PBKDF2_ITERATIONS.toString(),
        Base64.getEncoder().encodeToString(salt),
        Base64.getEncoder().encodeToString(hash)
    ).joinToString("$")
}

fun verifyPassword(password: String, stored: String): Boolean {
    val parts = stored.split("$")

    if (parts.size != 4 || parts[0] != "pbkdf2_sha256") {
        return false
    }

    val iterations = parts[1].toIntOrNull() ?: return false
    val salt = Base64.getDecoder().decode(parts[2])
    val expectedHash = Base64.getDecoder().decode(parts[3])

    val actualHash = pbkdf2(password, salt, iterations)

    // MessageDigest.isEqual runs in constant time. It does not stop at the
    // first different byte. A plain == comparison does stop early, and an
    // attacker could use that timing difference to guess the hash one
    // byte at a time. This is why isEqual is used here, not ==.
    return MessageDigest.isEqual(actualHash, expectedHash)
}

private fun pbkdf2(password: String, salt: ByteArray, iterations: Int): ByteArray {
    val spec = PBEKeySpec(password.toCharArray(), salt, iterations, PBKDF2_KEY_LENGTH_BITS)
    val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)

    return factory.generateSecret(spec).encoded
}