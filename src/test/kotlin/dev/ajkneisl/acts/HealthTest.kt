package dev.ajkneisl.acts

import dev.ajkneisl.acts.health.HealthServer
import dev.ajkneisl.acts.health.SyncHealth
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
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

class HealthServerTest {

    private lateinit var health: SyncHealth
    private lateinit var server: HealthServer
    private val http: HttpClient = HttpClient.newHttpClient()

    @BeforeTest
    fun start() {
        health = SyncHealth()
        server = HealthServer(0, "/health", health).also { it.start() }
    }

    @AfterTest
    fun stop() {
        server.close()
    }

    private fun get(): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:${server.port}/health")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `reports ok while the sync is working`() {
        health.recordSuccess()
        val response = get()

        assertEquals(200, response.statusCode())
        val body = Json.parseToJsonElement(response.body()).jsonObject
        assertEquals("ok", body["status"]!!.jsonPrimitive.content)
        assertEquals(0, body["consecutiveFailures"]!!.jsonPrimitive.content.toInt())
        assertTrue(body["lastSuccess"]!!.jsonPrimitive.content.startsWith("20"))
    }

    @Test
    fun `answers 503 once it is unhealthy, so a monitor needs no parsing`() {
        repeat(SyncHealth.UNHEALTHY_AFTER) { health.recordFailure("iCloud unreachable") }
        val response = get()

        assertEquals(503, response.statusCode())
        val body = Json.parseToJsonElement(response.body()).jsonObject
        assertEquals("unhealthy", body["status"]!!.jsonPrimitive.content)
        assertEquals("iCloud unreachable", body["lastError"]!!.jsonPrimitive.content)
    }

    @Test
    fun `reports uptime and time since the last success`() {
        health.recordSuccess()
        val body = Json.parseToJsonElement(get().body()).jsonObject

        assertTrue(body["uptimeSeconds"]!!.jsonPrimitive.content.toLong() >= 0)
        assertTrue(body["secondsSinceLastSuccess"]!!.jsonPrimitive.content.toLong() >= 0)
    }

    @Test
    fun `says so plainly before the first pass has run`() {
        val body = Json.parseToJsonElement(get().body()).jsonObject
        assertEquals("null", body["lastSuccess"].toString())
        assertEquals("ok", body["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a HEAD works, which is what most monitors send`() {
        health.recordSuccess()
        val response =
            http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:${server.port}/health"))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        assertEquals(200, response.statusCode())
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
