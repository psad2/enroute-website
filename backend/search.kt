import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

@Serializable
data class ThreadSearchResult(
    val id: Long,
    val title: String,
    val content: String,
    val contentHtml: String,
    val createdAt: String,
    val userId: Long,
    val username: String,
    val category: String
)

@Serializable
data class PostSearchResult(
    val id: Long,
    val threadId: Long,
    val content: String,
    val contentHtml: String,
    val createdAt: String,
    val userId: Long,
    val username: String,
    val threadTitle: String
)

@Serializable
data class SearchResponse(
    val query: String,
    val page: Int,
    val perPage: Int,
    val limit: Int,
    val threads: List<ThreadSearchResult>,
    val posts: List<PostSearchResult>
)

fun Route.searchRoute() {
    get("/api/search") {
        val q = (call.request.queryParameters["q"] ?: "").trim()

        if (q.length < 2) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Query must be at least 2 characters"))
            return@get
        }

        val pagination = call.paginateArgs()
        val like = "%$q%"

        db().use { connection ->
            val threads = connection.prepareStatement(
                """
                SELECT
                    threads.id,
                    threads.title,
                    threads.content,
                    threads.created_at,
                    threads.user_id,
                    users.username,
                    categories.name AS category

                FROM threads

                INNER JOIN users
                    ON users.id = threads.user_id

                INNER JOIN categories
                    ON categories.id = threads.category_id

                WHERE
                    threads.title LIKE ?
                    OR threads.content LIKE ?

                ORDER BY
                    threads.pinned DESC,
                    threads.created_at DESC

                LIMIT ?
                OFFSET ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, like)
                stmt.setString(2, like)
                stmt.setInt(3, pagination.perPage)
                stmt.setInt(4, pagination.offset)

                stmt.executeQuery().use { rs ->
                    val result = mutableListOf<ThreadSearchResult>()

                    while (rs.next()) {
                        val content = rs.getString("content")

                        result.add(
                            ThreadSearchResult(
                                id = rs.getLong("id"),
                                title = rs.getString("title"),
                                content = content,
                                contentHtml = MarkdownRenderer.render(content),
                                createdAt = rs.getString("created_at"),
                                userId = rs.getLong("user_id"),
                                username = rs.getString("username"),
                                category = rs.getString("category")
                            )
                        )
                    }

                    result
                }
            }

            val posts = connection.prepareStatement(
                """
                SELECT
                    posts.id,
                    posts.thread_id,
                    posts.content,
                    posts.created_at,
                    posts.user_id,
                    users.username,
                    threads.title AS thread_title

                FROM posts

                INNER JOIN users
                    ON users.id = posts.user_id

                INNER JOIN threads
                    ON threads.id = posts.thread_id

                WHERE posts.content LIKE ?

                ORDER BY
                    posts.created_at DESC

                LIMIT ?
                OFFSET ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, like)
                stmt.setInt(2, pagination.perPage)
                stmt.setInt(3, pagination.offset)

                stmt.executeQuery().use { rs ->
                    val result = mutableListOf<PostSearchResult>()

                    while (rs.next()) {
                        val content = rs.getString("content")

                        result.add(
                            PostSearchResult(
                                id = rs.getLong("id"),
                                threadId = rs.getLong("thread_id"),
                                content = content,
                                contentHtml = MarkdownRenderer.render(content),
                                createdAt = rs.getString("created_at"),
                                userId = rs.getLong("user_id"),
                                username = rs.getString("username"),
                                threadTitle = rs.getString("thread_title")
                            )
                        )
                    }

                    result
                }
            }

            call.respond(
                HttpStatusCode.OK,
                SearchResponse(
                    query = q,
                    page = pagination.page,
                    perPage = pagination.perPage,
                    limit = pagination.perPage,
                    threads = threads,
                    posts = posts
                )
            )
        }
    }
}
