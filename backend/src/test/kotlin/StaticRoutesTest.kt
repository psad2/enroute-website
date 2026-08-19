import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class StaticRoutesTest {

    @BeforeTest
    fun pointAtProjectRoot() {
        // Gradle runs the test task with its working directory set to
        // backend/, one level below the real project root. This test sets
        // PROJECT_ROOT explicitly, the same way AuthRouteTest.kt sets
        // DATABASE explicitly, instead of relying on the fallback in
        // StaticRoutes.kt.
        PROJECT_ROOT = File(System.getProperty("user.dir")).parentFile.absolutePath
    }

    @Test
    fun `root path serves frontpage`() = testApplication {
        application { module() }
        val client = createClient { }

        val response = client.get("/")

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `an allowlisted page is served by name`() = testApplication {
        application { module() }
        val client = createClient { }

        val response = client.get("/forums.html")

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `backend source is not served`() = testApplication {
        application { module() }
        val client = createClient { }

        val response = client.get("/backend/app.py")

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `how2run is not served`() = testApplication {
        application { module() }
        val client = createClient { }

        val response = client.get("/how2run")

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }
}
