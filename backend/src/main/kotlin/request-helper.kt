import io.ktor.http.Parameters
import io.ktor.server.application.*
import io.ktor.server.request.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.sql.PreparedStatement
import java.sql.Types

const val PER_PAGE_DEFAULT = 20
const val PER_PAGE_MAX = 100

const val MAX_TITLE_LEN = 200
const val MAX_CONTENT_LEN = 20000
const val MAX_BIO_LEN = 500
const val MAX_REASON_LEN = 500

val ALLOWED_REACTIONS = setOf(
    "like",
    "love",
    "laugh",
    "wow",
    "sad"
)

suspend fun ApplicationCall.getJsonBody(): JsonObject? {
    return try {
        receive<JsonObject>()
    } catch (e: Exception) {
        null
    }
}

fun ApplicationCall.paginateArgs(): Pagination {
    val page = request.queryParameters["page"]
        ?.toIntOrNull()
        ?.coerceAtLeast(1)
        ?: 1

    val rawPerPage = request.queryParameters["per_page"]
        ?: request.queryParameters["limit"]

    val perPage = rawPerPage
        ?.toIntOrNull()
        ?.coerceIn(1, PER_PAGE_MAX)
        ?: PER_PAGE_DEFAULT

    val offset = (page - 1) * perPage

    return Pagination(
        page = page,
        perPage = perPage,
        offset = offset
    )
}

data class Pagination(
    val page: Int,
    val perPage: Int,
    val offset: Int
)

@Serializable
data class PublicUser(
    val id: Long,
    val username: String,
    val email: String
)

@Serializable
data class ErrorResponse(val error: String)

@Serializable
data class MessageResponse(val message: String)

// pulls the bearer token off the Authorization header, like login_required did in app.py
fun ApplicationCall.bearerToken(): String? {
    val header = request.headers["Authorization"] ?: return null

    if (!header.startsWith("Bearer ", ignoreCase = true)) {
        return null
    }

    val token = header.removePrefix("Bearer ").trim()

    return token.ifBlank { null }
}

// binds a nullable id, since sqlite comparisons like "? IS NULL OR id != ?" need an actual SQL NULL
fun PreparedStatement.setNullableLong(index: Int, value: Long?) {
    if (value == null) {
        setNull(index, Types.BIGINT)
    } else {
        setLong(index, value)
    }
}