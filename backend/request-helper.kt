import io.ktor.http.Parameters
import io.ktor.server.application.*
import io.ktor.server.request.*
import kotlinx.serialization.json.JsonObject

const val PER_PAGE_DEFAULT = 20
const val PER_PAGE_MAX = 100

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
        ?.coerceIn(1)
        ?: 1

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
data class User(
    val id: Long,
    val username: String,
    val email: String,
    val passwords: String
)

@Serializable
data class PublicUser(
    val id: Long,
    val username: String,
    val email: String
)

fun User.toPublicUser(): PublicUser {
    return PublicUser(
        id = id,
        username = username,
        email = email
    )
}