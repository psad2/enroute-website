import io.ktor.server.http.content.staticFiles
import io.ktor.server.routing.Route
import java.io.File

// This is the project's root directory, the one that holds frontpage.html,
// public/, images/, and so on. It does not default to a path relative to
// where the process happens to be launched from -- that would tie this
// code to one specific launcher's habits. Any real launcher, including
// the devenv skill's kotlin target, must set PROJECT_ROOT itself. The
// fallback below is only for the common case of running the built jar
// by hand from the project root.
// This is a var, not a val, so a test can point it at a known-correct
// path instead of relying on the fallback above.
var PROJECT_ROOT: String = System.getenv("PROJECT_ROOT") ?: System.getProperty("user.dir")

// This is the full list of pages this server will serve at the site
// root, for example "/forums.html". A page must be added here by hand.
// This is an allowlist, not a mirror of app.py's catch-all -- app.py
// serves any file under the project root by exact path, which would also
// serve backend/ source and local-only/ (this project's own analysis
// notes and scripts) if copied here as-is.
private val ALLOWED_PAGES = setOf(
    "frontpage.html",
    "fleet.html",
    "crew.html",
    "careers.html",
    "register.html",
    "forums.html",
    "thread.html",
    "route-map.html"
)

// These are the only two directories served whole. Both hold only public
// assets (icons, fonts, images), never source code or secrets.
private val ALLOWED_TOP_LEVEL_DIRECTORIES = setOf("public", "images")

fun Route.staticFrontendRoutes() {
    val root = File(PROJECT_ROOT)

    staticFiles("/", root, index = "frontpage.html") {
        // This runs for every path under the project root, including
        // ones far outside the allowlist above. Returning true here
        // blocks the request with 403 Forbidden instead of serving the
        // file. Any path this function does not clearly allow is
        // blocked -- the default is closed, not open.
        exclude { file ->
            val relativePath = file.relativeToOrNull(root)?.path ?: return@exclude true
            val topLevelName = relativePath.substringBefore(File.separatorChar)

            val isAllowedPage = relativePath == topLevelName && topLevelName in ALLOWED_PAGES
            val isAllowedDirectory = topLevelName in ALLOWED_TOP_LEVEL_DIRECTORIES

            !(isAllowedPage || isAllowedDirectory)
        }
    }
}
