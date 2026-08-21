import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class HealthRouteTest {

    @Test
    fun `health endpoint reports ok when the database is reachable`() = testApplication {
        application { module() }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/api/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", response.body<HealthOkResponse>().status)
        assertEquals("ok", response.body<HealthOkResponse>().database)
    }

    @Test
    fun `an unmatched api path returns a JSON 404`() = testApplication {
        application { module() }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/api/this-route-does-not-exist")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("Endpoint not found", response.body<ErrorResponse>().error)
    }
}
