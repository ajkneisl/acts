package dev.ajkneisl.acts.todoist.webhook

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.ajkneisl.acts.sync.SyncTrigger
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.slf4j.LoggerFactory

/** Receives Todoist webhooks and nudges the watch loop. */
class TodoistWebhookServer(
    port: Int,
    private val path: String,
    private val clientSecret: String,
    private val trigger: SyncTrigger,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(TodoistWebhookServer::class.java)
    private val server: HttpServer = HttpServer.create(InetSocketAddress(port), BACKLOG)

    val boundPort: Int
        get() = server.address.port

    fun start() {
        server.createContext(path) { exchange -> exchange.use { handle(it) } }
        server.executor =
            Executors.newFixedThreadPool(THREADS) { runnable ->
                Thread(runnable, "todoist-webhook").apply { isDaemon = true }
            }
        server.start()
        log.info("Listening for Todoist on :{}{}", boundPort, path)
    }

    override fun close() {
        server.stop(0)
    }

    private fun handle(exchange: HttpExchange) {
        if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
            exchange.reply(405, "method not allowed")
            return
        }

        val body = exchange.requestBody.readNBytes(MAX_BODY_BYTES + 1)
        if (body.size > MAX_BODY_BYTES) {
            exchange.reply(413, "payload too large")
            return
        }

        val provided = exchange.requestHeaders.getFirst(SIGNATURE_HEADER)
        if (provided == null || !signatureMatches(body, provided)) {
            // Never let an unverified caller drive our sync.
            log.warn("Rejected a webhook with a missing or invalid {}", SIGNATURE_HEADER)
            exchange.reply(401, "bad signature")
            return
        }

        val event = eventName(body)
        if (event != null && event.startsWith(TASK_EVENT_PREFIX)) {
            // At INFO on purpose. A delivery that works is otherwise completely silent, which
            // makes "no webhooks are arriving" impossible to tell from "they arrive and work".
            log.info("Webhook {}: syncing now", event)
            trigger.fire()
        } else {
            log.info("Webhook {}: nothing to sync for that", event)
        }

        // Todoist requires exactly 200, and retries anything else for up to three attempts.
        exchange.reply(200, "ok")
    }

    /** HMAC-SHA256 of the raw body, base64, compared in constant time. */
    private fun signatureMatches(body: ByteArray, provided: String): Boolean =
        try {
            val mac =
                Mac.getInstance(HMAC_ALGORITHM).apply {
                    init(
                        SecretKeySpec(
                            clientSecret.toByteArray(StandardCharsets.UTF_8),
                            HMAC_ALGORITHM,
                        )
                    )
                }
            val expected = Base64.getEncoder().encodeToString(mac.doFinal(body))
            MessageDigest.isEqual(
                expected.toByteArray(StandardCharsets.UTF_8),
                provided.trim().toByteArray(StandardCharsets.UTF_8),
            )
        } catch (e: Exception) {
            log.warn("Could not verify a webhook signature: {}", e.message)
            false
        }

    /**
     * Pulls `event_name` out without a JSON parser. The payload is only ever used to decide whether
     * to wake up, so a strict parse would buy nothing and add a failure mode.
     */
    private fun eventName(body: ByteArray): String? {
        val text = body.toString(StandardCharsets.UTF_8)
        return EVENT_NAME.find(text)?.groupValues?.get(1)
    }

    private fun HttpExchange.reply(status: Int, message: String) {
        responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
        // A HEAD response carries no body, and the JDK server rejects a content length here --
        // which used to throw, and then throw again trying to report the failure.
        if (requestMethod.equals("HEAD", ignoreCase = true)) {
            sendResponseHeaders(status, -1)
            return
        }
        val bytes = message.toByteArray(StandardCharsets.UTF_8)
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private inline fun HttpExchange.use(block: (HttpExchange) -> Unit) {
        try {
            block(this)
        } catch (e: Exception) {
            log.warn("Webhook handler failed: {}", e.message)
            runCatching { reply(500, "error") }
        } finally {
            close()
        }
    }

    private companion object {
        const val SIGNATURE_HEADER = "X-Todoist-Hmac-SHA256"
        const val HMAC_ALGORITHM = "HmacSHA256"
        const val TASK_EVENT_PREFIX = "item:"
        const val MAX_BODY_BYTES = 1 shl 20
        const val BACKLOG = 16
        const val THREADS = 2
        val EVENT_NAME = Regex("\"event_name\"\\s*:\\s*\"([^\"]+)\"")
    }
}
