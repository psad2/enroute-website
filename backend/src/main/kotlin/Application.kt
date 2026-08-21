import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
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

        // This matches app.py's 404 errorhandler: only /api/* paths get a JSON
        // body, since those are the only ones a client parses as JSON. A
        // missed static/page path falls through to Ktor's default 404.
        status(HttpStatusCode.NotFound) { call, status ->
            if (call.request.path().startsWith("/api/")) {
                call.respond(status, ErrorResponse("Endpoint not found"))
            }
        }
    }

    routing {
        registerRoute()
        loginRoute()
        logoutRoute()

        categoriesRoute()
        crewRoute()
        healthRoute()
        meRoute()
        moderationRoute()
        postsRoute()
        profileRoute()
        reactionRoutes()
        reportsRoute()
        searchRoute()
        threadsRoute()

        // staticFrontendRoutes() below mounts static file serving at "/",
        // which would otherwise catch an unmatched /api/* path too and run
        // it through StaticRoutes.kt's allowlist -- turning "endpoint
        // doesn't exist" into a misleading 403 instead of a 404. This is a
        // more specific route branch than that "/" mount, so Ktor's routing
        // matches it first for anything under /api/ that no route above
        // handled, and it falls through to a plain 404 (picked up by the
        // StatusPages handler above) instead of ever reaching static
        // serving.
        route("/api/{...}") {
            get { call.respond(HttpStatusCode.NotFound) }
            post { call.respond(HttpStatusCode.NotFound) }
            put { call.respond(HttpStatusCode.NotFound) }
            delete { call.respond(HttpStatusCode.NotFound) }
        }

        staticFrontendRoutes()
    }
}
