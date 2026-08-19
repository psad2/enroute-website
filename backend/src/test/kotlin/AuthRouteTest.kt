import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import java.nio.file.Files
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@OptIn(ExperimentalSerializationApi::class)
class AuthRouteTest {

    @BeforeTest
    fun pointAtFreshDatabase() {
        val tempFile = Files.createTempFile("enroute-test-", ".db").toFile()
        tempFile.deleteOnExit()
        DATABASE = tempFile.absolutePath

        // Each RateLimiter is a single shared object for the whole JVM run.
        // Without this reset, an earlier test's calls would count against a
        // later test's limit.
        registerLimiterPerMinute.buckets.clear()
        registerLimiterPerHour.buckets.clear()
        loginLimiterPerMinute.buckets.clear()
        loginLimiterPerUsernamePerMinute.buckets.clear()
    }

    private fun testJson() = Json {
        namingStrategy = JsonNamingStrategy.SnakeCase
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Test
    fun `register with valid data succeeds`() = testApplication {
        application { module() }
        val client = createClient { install(ContentNegotiation) { json(testJson()) } }

        val response = client.post("/api/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"alice","email":"alice@example.com","password":"correcthorse"}""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals("Account created", response.body<MessageResponse>().message)
    }

    @Test
    fun `register with short password is rejected`() = testApplication {
        application { module() }
        val client = createClient { install(ContentNegotiation) { json(testJson()) } }

        val response = client.post("/api/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"bob","email":"bob@example.com","password":"short"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `register with taken username is rejected`() = testApplication {
        application { module() }
        val client = createClient { install(ContentNegotiation) { json(testJson()) } }

        client.post("/api/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"carol","email":"carol@example.com","password":"correcthorse"}""")
        }

        val response = client.post("/api/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"carol","email":"carol2@example.com","password":"correcthorse"}""")
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    @Test
    fun `login with correct credentials returns a token and authenticates`() = testApplication {
        application { module() }
        val client = createClient { install(ContentNegotiation) { json(testJson()) } }

        client.post("/api/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"dave","email":"dave@example.com","password":"correcthorse"}""")
        }

        val loginResponse = client.post("/api/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"dave","password":"correcthorse"}""")
        }

        assertEquals(HttpStatusCode.OK, loginResponse.status)
        val body = loginResponse.body<LoginResponse>()
        assertEquals("dave", body.user.username)
        assertNotNull(body.token)

        val meResponse = client.get("/api/me") {
            header("Authorization", "Bearer ${body.token}")
        }

        assertEquals(HttpStatusCode.OK, meResponse.status)
        assertEquals("dave", meResponse.body<MeResponse>().username)
    }

    @Test
    fun `login with wrong password is rejected`() = testApplication {
        application { module() }
        val client = createClient { install(ContentNegotiation) { json(testJson()) } }

        client.post("/api/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"erin","email":"erin@example.com","password":"correcthorse"}""")
        }

        val response = client.post("/api/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"erin","password":"wrongpassword"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `logout without a token is rejected`() = testApplication {
        application { module() }

        val response = client.post("/api/logout")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `logout with a valid token succeeds`() = testApplication {
        application { module() }
        val client = createClient { install(ContentNegotiation) { json(testJson()) } }

        client.post("/api/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"frank","email":"frank@example.com","password":"correcthorse"}""")
        }

        val loginResponse = client.post("/api/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"frank","password":"correcthorse"}""")
        }
        val token = loginResponse.body<LoginResponse>().token

        val response = client.post("/api/logout") {
            header("Authorization", "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }
}
