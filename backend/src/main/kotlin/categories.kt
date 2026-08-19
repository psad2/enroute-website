import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

@Serializable
data class Category(
    val id: Long,
    val name: String
)

// This lists all forum categories, in alphabetical order.
fun Route.categoriesRoute() {
    get("/api/categories") {
        db().use { connection ->
            val categories = connection.prepareStatement(
                """
                SELECT id, name
                FROM categories
                ORDER BY name ASC
                """.trimIndent()
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    val result = mutableListOf<Category>()

                    while (rs.next()) {
                        result.add(
                            Category(
                                id = rs.getLong("id"),
                                name = rs.getString("name")
                            )
                        )
                    }

                    result
                }
            }

            call.respond(HttpStatusCode.OK, categories)
        }
    }
}
