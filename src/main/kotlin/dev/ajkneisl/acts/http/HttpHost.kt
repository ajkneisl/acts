package dev.ajkneisl.acts.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

interface Endpoint {
    val path: String

    suspend fun handle(call: ApplicationCall)
}

class HttpHost(private val requestedPort: Int) : AutoCloseable {
    private val log = LoggerFactory.getLogger(HttpHost::class.java)
    private val endpoints = mutableListOf<Endpoint>()
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? =
        null

    val port: Int
        get() {
            val engine = checkNotNull(server) { "The host has not been started." }.engine
            return runBlocking { engine.resolvedConnectors().first().port }
        }

    fun mount(endpoint: Endpoint) {
        check(server == null)
        endpoints += endpoint
    }

    fun start() {
        check(server == null) { "The host is already running." }
        server =
            embeddedServer(CIO, port = requestedPort) { module() }.also { it.start(wait = false) }
        log.info("Listening on :{} for {}", port, endpoints.joinToString(", ") { it.path })
    }

    override fun close() {
        server?.stop(0, 0)
        server = null
    }

    private fun Application.module() {
        install(StatusPages) {
            exception<Throwable> { call, cause ->
                log.warn("{} failed: {}", call.request.path(), cause.message)
                call.respondText("error", status = HttpStatusCode.InternalServerError)
            }
        }

        routing {
            for (endpoint in endpoints) {
                route(endpoint.path) { handle { endpoint.handle(call) } }
            }
        }
    }
}
