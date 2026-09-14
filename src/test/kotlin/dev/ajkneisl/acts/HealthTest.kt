package dev.ajkneisl.acts

import dev.ajkneisl.acts.http.health.HealthEndpoint
import dev.ajkneisl.acts.http.health.SyncHealth
import dev.ajkneisl.acts.http.HttpHost
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SyncHealthTest {

    @Test
    fun `starts healthy`() {
        assertTrue(SyncHealth().healthy)
    }

    @Test
    fun `one failure is not yet a problem`() {
        // A single blip should not page anybody; the loop retries in seconds.
        val health = SyncHealth()
        assertEquals(1, health.recordFailure("boom"))
        assertTrue(health.healthy)
    }

    @Test
    fun `repeated failures turn it unhealthy`() {
        val health = SyncHealth()
        repeat(SyncHealth.UNHEALTHY_AFTER) { health.recordFailure("boom") }
        assertTrue(!health.healthy)
        assertEquals("boom", health.lastError)
    }

    @Test
    fun `a success clears the run of failures`() {
        val health = SyncHealth()
        repeat(5) { health.recordFailure("boom") }
        health.recordSuccess()

        assertTrue(health.healthy)
        assertEquals(0, health.consecutiveFailures)
        assertEquals(null, health.lastError)
        assertTrue(health.lastSuccess != null)
    }
}

class HealthEndpointTest {

    private lateinit var health: SyncHealth
    private lateinit var host: HttpHost
    private val http: HttpClient = HttpClient.newHttpClient()

    @BeforeTest
    fun start() {
        health = SyncHealth()
        // Port 0: let the OS pick, so tests never collide with a real service.
        host = HttpHost(0).apply {
            mount(HealthEndpoint("/health", health))
            start()
        }
    }

    @AfterTest
    fun stop() {
        host.close()
    }

    private fun get(): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:${host.port}/health")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun body(): JsonObject = Json.parseToJsonElement(get().body()).jsonObject

    private fun stats(): JsonObject = body()["stats"]!!.jsonObject

    @Test
    fun `reports ok while the sync is working`() {
        health.recordSuccess()

        // 200 is the whole answer: a monitor never has to parse the body to know.
        assertEquals(200, get().statusCode())
        val stats = stats()
        assertEquals(0, stats["consecutiveFailures"]!!.jsonPrimitive.content.toInt())
        assertTrue(stats["lastSuccess"]!!.jsonPrimitive.content.startsWith("20"))
    }

    @Test
    fun `names the build it is running`() {
        // Proves the whole path works: Gradle stamps the version into acts.properties,
        // and the report reads it back.
        val version = body()["version"]!!.jsonPrimitive.content

        assertTrue(version.isNotBlank(), "the report named no version")
        assertNotEquals("unknown", version, "the build stamp never reached the classpath")
    }

    @Test
    fun `answers 503 once it is unhealthy, so a monitor needs no parsing`() {
        repeat(SyncHealth.UNHEALTHY_AFTER) { health.recordFailure("iCloud unreachable") }
        val response = get()

        assertEquals(503, response.statusCode())
        assertEquals("iCloud unreachable", stats()["lastError"]!!.jsonPrimitive.content)
    }

    @Test
    fun `reports uptime and time since the last success`() {
        health.recordSuccess()
        val stats = stats()

        assertTrue(stats["uptimeSeconds"]!!.jsonPrimitive.content.toLong() >= 0)
        assertTrue(stats["secondsSinceLastSuccess"]!!.jsonPrimitive.content.toLong() >= 0)
    }

    @Test
    fun `says so plainly before the first pass has run`() {
        assertEquals("null", stats()["lastSuccess"].toString())
        assertEquals(200, get().statusCode(), "no pass yet is not the same as a failing one")
    }

    @Test
    fun `a HEAD works, which is what most monitors send`() {
        health.recordSuccess()
        val response =
            http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:${host.port}/health"))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        assertEquals(200, response.statusCode())
        assertEquals("", response.body(), "a HEAD response must not carry a body")
    }

    @Test
    fun `exposes no credentials`() {
        health.recordFailure("boom")
        val body = get().body().lowercase()
        for (secret in listOf("password", "token", "secret", "apple_password")) {
            assertTrue(!body.contains(secret), "health output mentions '$secret'")
        }
    }
}
