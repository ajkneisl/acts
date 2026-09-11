package dev.ajkneisl.acts.health

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/** Reports whether the sync is still working, for a healthcheck or a monitor. */
class HealthServer(
    port: Int,
    private val path: String,
    private val health: SyncHealth,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(HealthServer::class.java)
    private val server: HttpServer = HttpServer.create(InetSocketAddress(port), BACKLOG)

    val port: Int
        get() = server.address.port

    fun start() {
        server.createContext(path) { exchange -> respond(exchange) }
        server.executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "health").apply { isDaemon = true }
        }
        server.start()
        log.info("Health endpoint on port {}{}", port, path)
    }

    override fun close() {
        server.stop(0)
    }

    private fun respond(exchange: HttpExchange) {
        try {
            val status = if (health.healthy) 200 else 503
            val body = report().toByteArray(StandardCharsets.UTF_8)

            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            if (exchange.requestMethod.equals("HEAD", ignoreCase = true)) {
                exchange.sendResponseHeaders(status, -1)
            } else {
                exchange.sendResponseHeaders(status, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        } catch (e: Exception) {
            log.warn("Health request failed: {}", e.message)
        } finally {
            exchange.close()
        }
    }

    private fun report(): String {
        val now = Instant.now()

        return buildJsonObject {
            put("status", if (health.healthy) "ok" else "unhealthy")
            put("uptimeSeconds", Duration.between(health.startedAt, now).seconds)
            put("consecutiveFailures", health.consecutiveFailures)
            put("lastSuccess", orNull(health.lastSuccess))
            put("secondsSinceLastSuccess", orNull(health.lastSuccess?.let { Duration.between(it, now).seconds }))
            put("lastFailure", orNull(health.lastFailure))
            put("lastError", orNull(health.lastError))
        }
            .toString()
    }

    /** [value] as JSON, or null. */
    private fun orNull(value: Any?): JsonElement =
        when (value) {
            null -> JsonNull
            is Number -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }

    private companion object {
        const val BACKLOG = 8
    }
}
