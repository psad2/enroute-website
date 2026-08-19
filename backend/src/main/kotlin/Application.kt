import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 5000

    embeddedServer(
        Netty,
        port = port,
        host = "0.0.0.0",
        module = Application::module
    ).start(wait = true)
}

@OptIn(ExperimentalSerializationApi::class)
fun Application.module() {
    initDb()

    // All JSON output uses snake_case keys, for example thread_id and content_html.
    // The frontend (forums.html, thread.html) reads these exact key names.
    // This also matches the key format from app.py's jsonify() output.
    install(ContentNegotiation) {
        json(
            Json {
                namingStrategy = JsonNamingStrategy.SnakeCase
                encodeDefaults = true
            }
        )
    }

    // This catches every otherwise-unhandled exception in a route.
    // It sends a generic message to the client. It does not send the real
    // exception message or a stack trace. This stops internal error detail
    // from reaching an attacker.
    install(StatusPages) {
        exception<Throwable> { call, _ ->
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("Internal server error")
            )
        }
    }

    routing {
        registerRoute()
        loginRoute()
        logoutRoute()

        categoriesRoute()
        crewRoute()
        meRoute()
        moderationRoute()
        postsRoute()
        profileRoute()
        reactionRoutes()
        reportsRoute()
        searchRoute()
        threadsRoute()

        staticFrontendRoutes()
    }
}
