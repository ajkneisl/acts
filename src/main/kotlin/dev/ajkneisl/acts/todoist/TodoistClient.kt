package dev.ajkneisl.acts.todoist

import dev.ajkneisl.acts.todoist.models.PaginatedList
import dev.ajkneisl.acts.todoist.models.TaskPatch
import dev.ajkneisl.acts.todoist.models.TodoistProject
import dev.ajkneisl.acts.todoist.models.TodoistTask
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/** Client for the Todoist v1 API. */
class TodoistClient(
    private val token: String,
    private val baseUrl: String = "https://api.todoist.com/api/v1",
    private val http: HttpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build(),
) {
    private val log = LoggerFactory.getLogger(TodoistClient::class.java)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }
    private val utcDateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")

    // ------------------------------------------------------------------- reads

    fun listTasks(projectId: String? = null): List<TodoistTask> =
        paginate("/tasks", buildMap { projectId?.let { put("project_id", it) } })

    fun listProjects(): List<TodoistProject> = paginate("/projects", emptyMap())

    /**
     * Tasks completed between [since] and [until]. Both `/tasks` and `/tasks/{id}` are documented
     * as active-only, so a completed task simply vanishes from them -- this is the only way to
     * tell one from a task that was deleted. The API caps the range at three months.
     */
    fun listCompletedTasks(since: Instant, until: Instant): List<TodoistTask> =
        paginate(
            "/tasks/completed/by_completion_date",
            mapOf("since" to utcDateTime.format(since.atZone(ZoneOffset.UTC)),
                "until" to utcDateTime.format(until.atZone(ZoneOffset.UTC))),
            // This endpoint publishes a default of 50 and no maximum, so leave it where it is.
            limit = 50,
        )

    /** The user's own timezone, which is what a due time with no zone means. */
    fun userTimezone(): ZoneId? = runCatching {
        val user = Json.parseToJsonElement(send("GET", "/user", null)).jsonObject
        val name = user["tz_info"]?.jsonObject?.get("timezone")?.jsonPrimitive?.contentOrNull
        name?.takeIf { it.isNotBlank() }?.let { ZoneId.of(it) }
    }.getOrNull()

    fun getTask(id: String): TodoistTask? = runCatching {
        json.decodeFromString<TodoistTask>(send("GET", "/tasks/$id", null))
    }.getOrElse { if (it is TodoistException && it.status == 404) null else throw it }

    private inline fun <reified T> paginate(
        path: String,
        query: Map<String, String>,
        limit: Int = 200,
    ): List<T> {
        val out = mutableListOf<T>()
        var cursor: String? = null
        do {
            val q =
                query +
                    buildMap {
                        put("limit", limit.toString())
                        cursor?.let { put("cursor", it) }
                    }
            val page =
                json.decodeFromString<PaginatedList<T>>(send("GET", path + queryString(q), null))
            out += page.page
            cursor = page.nextCursor
        } while (cursor != null)
        return out
    }

    // ------------------------------------------------------------------ writes

    fun createTask(
        content: String,
        description: String? = null,
        projectId: String? = null,
        patch: TaskPatch = TaskPatch(),
    ): TodoistTask {
        val body = buildJsonObject {
            put("content", content)
            description?.takeIf { it.isNotEmpty() }?.let { put("description", it) }
            projectId?.let { put("project_id", it) }
            applyPatch(patch)
        }
        return json.decodeFromString(send("POST", "/tasks", body))
    }

    /** Partial update. Never send due_* for a recurring task: it drops the repeat. */
    fun updateTask(id: String, patch: TaskPatch): TodoistTask {
        val body = buildJsonObject {
            patch.content?.let { put("content", it) }
            patch.description?.let { put("description", it) }
            applyPatch(patch)
        }
        if (body.isEmpty()) return getTask(id) ?: throw TodoistException("Task $id vanished", 404)
        return json.decodeFromString(send("POST", "/tasks/$id", body))
    }

    fun closeTask(id: String) {
        send("POST", "/tasks/$id/close", JsonObject(emptyMap()))
    }

    fun deleteTask(id: String) {
        send("DELETE", "/tasks/$id", null)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.applyPatch(patch: TaskPatch) {
        when {
            patch.dueDatetime != null ->
                put("due_datetime", utcDateTime.format(patch.dueDatetime.atZone(ZoneOffset.UTC)))
            patch.dueDate != null -> put("due_date", patch.dueDate.toString())
        }
        patch.durationMinutes?.let {
            if (it > 0) {
                put("duration", it)
                put("duration_unit", "minute")
            }
        }
    }

    // -------------------------------------------------------------------- http

    private fun queryString(params: Map<String, String>): String =
        if (params.isEmpty()) ""
        else
            "?" +
                params.entries.joinToString("&") { (k, v) ->
                    "${enc(k)}=${enc(v)}"
                }

    private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8)

    private fun send(method: String, path: String, body: JsonObject?): String {
        var attempt = 0
        while (true) {
            attempt++
            val builder =
                HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/json")

            val publisher =
                if (body != null) {
                    builder.header("Content-Type", "application/json")
                    HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)
                } else {
                    HttpRequest.BodyPublishers.noBody()
                }
            val request = builder.method(method, publisher).build()

            val response =
                http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            val status = response.statusCode()

            if (status in 200..299) return response.body().ifBlank { "{}" }

            val retryable = status == 429 || status in 500..599
            if (retryable && attempt <= MAX_ATTEMPTS) {
                val wait =
                    response
                        .headers()
                        .firstValue("Retry-After")
                        .map { it.toLongOrNull() ?: 0L }
                        .orElse(0L)
                        .takeIf { it > 0 } ?: (1L shl (attempt - 1))
                log.warn("Todoist {} {} -> {}, retrying in {}s", method, path, status, wait)
                Thread.sleep(wait * 1000)
                continue
            }

            val hint =
                when (status) {
                    401,
                    403 -> " (check ACTS_TODOIST_TOKEN)"
                    404 -> " (not found)"
                    else -> ""
                }
            throw TodoistException(
                "Todoist $method $path failed: HTTP $status$hint ${response.body().take(400)}",
                status,
            )
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 5
    }
}
