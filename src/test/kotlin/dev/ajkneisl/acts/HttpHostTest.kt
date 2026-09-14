package dev.ajkneisl.acts

import dev.ajkneisl.acts.http.health.HealthEndpoint
import dev.ajkneisl.acts.http.health.SyncHealth
import dev.ajkneisl.acts.http.Endpoint
import dev.ajkneisl.acts.http.HttpHost
import io.ktor.server.application.ApplicationCall
import dev.ajkneisl.acts.sync.SyncTrigger
import dev.ajkneisl.acts.todoist.webhook.TodoistWebhookEndpoint
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpHostTest {

    private val secret = "test-client-secret"
    private lateinit var trigger: SyncTrigger
    private lateinit var health: SyncHealth
    private lateinit var host: HttpHost
    private val http: HttpClient = HttpClient.newHttpClient()

    @BeforeTest
    fun start() {
        trigger = SyncTrigger()
        health = SyncHealth()
        host = HttpHost(0).apply {
            mount(TodoistWebhookEndpoint("/todoist", secret, trigger))
            mount(HealthEndpoint("/health", health))
            mount(Exploding)
            start()
        }
    }

    @AfterTest
    fun stop() {
        host.close()
    }

    private fun url(path: String) = URI.create("http://localhost:${host.port}$path")

    private fun get(path: String): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(url(path)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun sign(body: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        }
        return Base64.getEncoder().encodeToString(mac.doFinal(body.toByteArray(StandardCharsets.UTF_8)))
    }

    @Test
    fun `the webhook and the healthcheck answer on one port`() {
        // The whole point of the host: one port to expose, tunnel and health-check.
        health.recordSuccess()
        assertEquals(200, get("/health").statusCode())

        val body = """{"event_name":"item:updated","event_data":{"id":"7"}}"""
        val posted = http.send(
            HttpRequest.newBuilder(url("/todoist"))
                .header("X-Todoist-Hmac-SHA256", sign(body))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertEquals(200, posted.statusCode())
        assertTrue(trigger.awaitFor(1_000), "the webhook should still drive the sync")
    }

    @Test
    fun `a path nobody mounted is a 404`() {
        assertEquals(404, get("/nope").statusCode())
    }

    @Test
    fun `a route that throws still answers, and the host survives it`() {
        assertEquals(500, get("/boom").statusCode())

        health.recordSuccess()
        assertEquals(200, get("/health").statusCode(), "one bad route must not take the host down")
    }

    private object Exploding : Endpoint {
        override val path = "/boom"

        override suspend fun handle(call: ApplicationCall) = error("boom")
    }
}
