package dev.ajkneisl.acts.http.health

import dev.ajkneisl.acts.Build
import dev.ajkneisl.acts.http.Endpoint
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class HealthEndpoint(
    override val path: String,
    private val health: SyncHealth,
) : Endpoint {

    override suspend fun handle(call: ApplicationCall) {
        val status = if (health.healthy) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
        call.respondText(report(), ContentType.Application.Json, status)
    }

    private fun report(): String {
        val now = Instant.now()

        return buildJsonObject {
            put("version", Build.version)

            putJsonObject("stats") {
                put("uptimeSeconds", Duration.between(health.startedAt, now).seconds)
                put("consecutiveFailures", health.consecutiveFailures)
                put("lastSuccess", orNull(health.lastSuccess))
                put(
                    "secondsSinceLastSuccess",
                    orNull(health.lastSuccess?.let { Duration.between(it, now).seconds }),
                )
                put("lastFailure", orNull(health.lastFailure))
                put("lastError", orNull(health.lastError))
            }
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
}
