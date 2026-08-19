import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

@Serializable
data class CrewMember(
    val id: Long,
    val username: String,
    val initials: String,
    val role: String
)

fun Route.crewRoute() {
    get("/api/crew") {
        db().use { connection ->
            val crew = connection.prepareStatement(
                """
                SELECT
                    id,
                    username,
                    role
                FROM users
                ORDER BY id ASC
                """.trimIndent()
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    val result = mutableListOf<CrewMember>()

                    while (rs.next()) {
                        val username = rs.getString("username")

                        result.add(
                            CrewMember(
                                id = rs.getLong("id"),
                                username = username,
                                initials = username.take(2).uppercase(),
                                role = rs.getString("role")
                            )
                        )
                    }

                    result
                }
            }

            call.respond(HttpStatusCode.OK, crew)
        }
    }
}
