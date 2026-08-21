package com.enroute.auth

import org.bouncycastle.crypto.generators.SCrypt

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

private fun pbkdf2(
    password: String,
    salt: ByteArray,
    iterations: Int,
    algorithm: String = PBKDF2_ALGORITHM,
    keyLengthBits: Int = PBKDF2_KEY_LENGTH_BITS
): ByteArray {
    val spec = PBEKeySpec(password.toCharArray(), salt, iterations, keyLengthBits)
    val factory = SecretKeyFactory.getInstance(algorithm)

    return factory.generateSecret(spec).encoded
}

// app.py's registration used werkzeug's generate_password_hash before this
// codebase existed, so forum.db can hold accounts in werkzeug's format:
// "<method>$<salt>$<hex hash>", where method is "scrypt:n:r:p" (werkzeug's
// current default) or "pbkdf2:<hash name>:<iterations>" (werkzeug's older
// default, still recognized on read). Neither the salt nor the hash is
// base64 here -- salt is the literal string bytes, and the hash is hex, per
// werkzeug's own source (security.py: _hash_internal/gen_salt).
private fun verifyWerkzeugHash(password: String, stored: String): Boolean {
    val parts = stored.split("$", limit = 3)

    if (parts.size != 3) {
        return false
    }

    val (method, salt, hashHex) = parts
    val expectedHash = hexToBytes(hashHex) ?: return false
    val saltBytes = salt.toByteArray(Charsets.UTF_8)
    val passwordBytes = password.toByteArray(Charsets.UTF_8)

    val methodParts = method.split(":")
    val algorithm = methodParts.firstOrNull() ?: return false
    val args = methodParts.drop(1)

    val actualHash = when (algorithm) {
        "scrypt" -> {
            val (n, r, p) = when {
                args.isEmpty() -> Triple(32768, 8, 1)
                args.size == 3 -> {
                    val parsed = args.map { it.toIntOrNull() ?: return false }
                    Triple(parsed[0], parsed[1], parsed[2])
                }
                else -> return false
            }

            SCrypt.generate(passwordBytes, saltBytes, n, r, p, expectedHash.size)
        }

        "pbkdf2" -> {
            val hashName = args.getOrElse(0) { "sha256" }
            val iterations = args.getOrNull(1)?.toIntOrNull() ?: WERKZEUG_DEFAULT_PBKDF2_ITERATIONS
            val javaAlgorithm = pbkdf2AlgorithmFor(hashName) ?: return false

            pbkdf2(password, saltBytes, iterations, javaAlgorithm, expectedHash.size * 8)
        }

        else -> return false
    }

    return MessageDigest.isEqual(actualHash, expectedHash)
}

// Matches werkzeug's DEFAULT_PBKDF2_ITERATIONS as of werkzeug 3.1. Only used
// when a legacy hash's method field omits the iteration count, which
// werkzeug's own format never actually does -- this is a defensive fallback,
// not a value expected to be exercised against real data.
private const val WERKZEUG_DEFAULT_PBKDF2_ITERATIONS = 1_000_000

private fun pbkdf2AlgorithmFor(hashName: String): String? {
    return when (hashName.lowercase()) {
        "sha1" -> "PBKDF2WithHmacSHA1"
        "sha256" -> "PBKDF2WithHmacSHA256"
        "sha384" -> "PBKDF2WithHmacSHA384"
        "sha512" -> "PBKDF2WithHmacSHA512"
        else -> null
    }
}

private fun hexToBytes(hex: String): ByteArray? {
    if (hex.length % 2 != 0) {
        return null
    }

    return try {
        ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    } catch (e: NumberFormatException) {
        null
    }
}

// This is the entry point the login route uses. It tries this codebase's
// own format first, then falls back to werkzeug's format for an account
// that predates this backend. A successful legacy verification immediately
// re-hashes the password into this codebase's own format and overwrites the
// stored value, so the account never touches the legacy path again after
// its first successful login here.
fun verifyAndMigratePassword(
    connection: Connection,
    userId: Long,
    password: String,
    stored: String
): Boolean {
    if (verifyPassword(password, stored)) {
        return true
    }

    if (!verifyWerkzeugHash(password, stored)) {
        return false
    }

    val migratedHash = hashPassword(password)

    connection.prepareStatement(
        "UPDATE users SET password = ? WHERE id = ?"
    ).use { stmt ->
        stmt.setString(1, migratedHash)
        stmt.setLong(2, userId)
        stmt.executeUpdate()
    }

    return true
}