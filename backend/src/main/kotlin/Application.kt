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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

fun main() {
    embeddedServer(
        Netty,
        port = 5000,
        host = "0.0.0.0",
        module = Application::module
    ).start(wait = true)
}

fun Application.module() {
    initDb()

    // snake_case wire format -- the existing frontend (forums.html, thread.html)
    // reads keys like thread_id/created_at/content_html, matching app.py's jsonify() output
    install(ContentNegotiation) {
        json(
            Json {
                namingStrategy = JsonNamingStrategy.SnakeCase
                encodeDefaults = true
            }
        )
    }

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
    }
}
