package dev.ajkneisl.acts

import dev.ajkneisl.acts.sync.SyncTrigger
import dev.ajkneisl.acts.todoist.webhook.TodoistWebhookServer
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncTriggerTest {

    @Test
    fun `a fire wakes the waiter`() {
        val trigger = SyncTrigger()
        trigger.fire()
        assertTrue(trigger.awaitFor(1_000))
    }

    @Test
    fun `no fire means a timeout`() {
        assertFalse(SyncTrigger().awaitFor(50))
    }

    @Test
    fun `a burst collapses into a single wake-up`() {
        // One bulk edit in Todoist produces many webhooks; they must not become many passes.
        val trigger = SyncTrigger()
        repeat(20) { trigger.fire() }

        assertTrue(trigger.awaitFor(1_000), "first wait should be woken")
        assertFalse(trigger.awaitFor(50), "the backlog should have been drained")
    }

    @Test
    fun `settle drains anything that lands during the pause`() {
        val trigger = SyncTrigger()
        trigger.fire()
        assertTrue(trigger.awaitFor(1_000))
        trigger.fire()
        trigger.settle(10)
        assertFalse(trigger.awaitFor(50), "settle should have absorbed the straggler")
    }
}

class TodoistWebhookServerTest {

    private val secret = "test-client-secret"
    private lateinit var trigger: SyncTrigger
    private lateinit var server: TodoistWebhookServer
    private val http: HttpClient = HttpClient.newHttpClient()

    @BeforeTest
    fun start() {
        trigger = SyncTrigger()
        // Port 0: let the OS pick, so tests never collide with a real service.
        server = TodoistWebhookServer(0, "/todoist-webhook", secret, trigger).also { it.start() }
    }

    @AfterTest
    fun stop() {
        server.close()
    }

    private fun sign(body: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        }
        return Base64.getEncoder().encodeToString(mac.doFinal(body.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun post(body: String, signature: String?): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://localhost:${server.boundPort}/todoist-webhook"))
            .header("Content-Type", "application/json")
        signature?.let { builder.header("X-Todoist-Hmac-SHA256", it) }
        return http.send(
            builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    }

    private fun payload(event: String) =
        """{"event_name":"$event","user_id":"1","event_data":{"id":"7"},"version":"10"}"""

    @Test
    fun `a correctly signed task event returns 200 and wakes the loop`() {
        val body = payload("item:updated")
        val response = post(body, sign(body))

        assertEquals(200, response.statusCode(), "Todoist retries anything that is not 200")
        assertTrue(trigger.awaitFor(1_000), "a task event should have triggered a sync")
    }

    @Test
    fun `an unsigned request is rejected and never triggers a sync`() {
        val body = payload("item:updated")
        assertEquals(401, post(body, null).statusCode())
        assertFalse(trigger.awaitFor(100), "an unverified caller must not drive the sync")
    }

    @Test
    fun `a wrongly signed request is rejected`() {
        val body = payload("item:updated")
        assertEquals(401, post(body, sign("a different body")).statusCode())
        assertFalse(trigger.awaitFor(100))
    }

    @Test
    fun `a tampered body fails verification`() {
        // Signature captured over the original payload, then the body is altered in flight.
        val original = payload("item:updated")
        val signature = sign(original)
        assertEquals(401, post(payload("item:deleted"), signature).statusCode())
        assertFalse(trigger.awaitFor(100))
    }

    @Test
    fun `an unrelated event is acknowledged but does not trigger a sync`() {
        // Returning 200 matters: anything else and Todoist retries it three times.
        val body = payload("note:added")
        assertEquals(200, post(body, sign(body)).statusCode())
        assertFalse(trigger.awaitFor(100), "a comment does not affect the calendar")
    }

    @Test
    fun `every task event we care about wakes the loop`() {
        for (event in listOf("item:added", "item:updated", "item:deleted", "item:completed", "item:uncompleted")) {
            val body = payload(event)
            assertEquals(200, post(body, sign(body)).statusCode(), event)
            assertTrue(trigger.awaitFor(1_000), "$event should trigger a sync")
        }
    }

    @Test
    fun `a HEAD is refused cleanly`() {
        // Regression: a HEAD used to blow up inside the handler, because the JDK server refuses
        // a content length on a bodyless response, and the failure path then failed too.
        val response = http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:${server.boundPort}/todoist-webhook"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(405, response.statusCode())
        assertFalse(trigger.awaitFor(100))

        // And the server is still healthy afterwards.
        val body = payload("item:updated")
        assertEquals(200, post(body, sign(body)).statusCode())
        assertTrue(trigger.awaitFor(1_000))
    }

    @Test
    fun `a GET is refused`() {
        val response = http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:${server.boundPort}/todoist-webhook"))
                .GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(405, response.statusCode())
        assertFalse(trigger.awaitFor(100))
    }
}
