package dev.ajkneisl.acts.todoist.webhook

import dev.ajkneisl.acts.http.Endpoint
import dev.ajkneisl.acts.sync.SyncTrigger
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.utils.io.readRemaining
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.io.readByteArray
import org.slf4j.LoggerFactory

/** Receives Todoist webhooks and nudges the watch loop. */
class TodoistWebhookEndpoint(
    override val path: String,
    private val clientSecret: String,
    private val trigger: SyncTrigger,
) : Endpoint {
    private val log = LoggerFactory.getLogger(TodoistWebhookEndpoint::class.java)

    override suspend fun handle(call: ApplicationCall) {
        if (call.request.httpMethod != HttpMethod.Post) {
            call.respondText("method not allowed", status = HttpStatusCode.MethodNotAllowed)
            return
        }

        val body = call.body()
        if (body == null) {
            call.respondText("payload too large", status = HttpStatusCode.PayloadTooLarge)
            return
        }

        val provided = call.request.headers[SIGNATURE_HEADER]
        if (provided == null || !signatureMatches(body, provided)) {
            log.warn("Rejected a webhook with a missing or invalid {}", SIGNATURE_HEADER)
            call.respondText("bad signature", status = HttpStatusCode.Unauthorized)
            return
        }

        val event = eventName(body)
        if (event != null && event.startsWith(TASK_EVENT_PREFIX)) {
            log.info("Webhook {}: syncing now", event)
            trigger.fire()
        } else {
            log.info("Webhook {}: nothing to sync for that", event)
        }

        call.respondText("ok", status = HttpStatusCode.OK)
    }

    private suspend fun ApplicationCall.body(): ByteArray? {
        if ((request.contentLength() ?: 0) > MAX_BODY_BYTES) return null
        val bytes = receiveChannel().readRemaining(MAX_BODY_BYTES + 1L).readByteArray()
        return bytes.takeIf { it.size <= MAX_BODY_BYTES }
    }

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

    private fun eventName(body: ByteArray): String? {
        val text = body.toString(StandardCharsets.UTF_8)
        return EVENT_NAME.find(text)?.groupValues?.get(1)
    }

    private companion object {
        const val SIGNATURE_HEADER = "X-Todoist-Hmac-SHA256"
        const val HMAC_ALGORITHM = "HmacSHA256"
        const val TASK_EVENT_PREFIX = "item:"
        const val MAX_BODY_BYTES = 1 shl 20
        val EVENT_NAME = Regex("\"event_name\"\\s*:\\s*\"([^\"]+)\"")
    }
}
