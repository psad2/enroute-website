import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

@Serializable
data class HealthOkResponse(val status: String = "ok", val database: String = "ok")

@Serializable
data class HealthErrorResponse(
    val status: String = "error",
    val database: String = "error",
    val message: String
)

// This matches app.py's /api/health: pings the database with SELECT 1 and
// reports whether the server and the database are both reachable.
fun Route.healthRoute() {
    get("/api/health") {
        try {
            db().use { connection ->
                connection.prepareStatement("SELECT 1").use { stmt ->
                    stmt.executeQuery()
                }
            }

            call.respond(HttpStatusCode.OK, HealthOkResponse())
        } catch (e: Exception) {
            call.respond(
                HttpStatusCode.InternalServerError,
                HealthErrorResponse(message = e.message ?: "Unknown error")
            )
        }
    }
}
