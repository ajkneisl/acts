package dev.ajkneisl.acts.sync

import dev.ajkneisl.acts.sync.models.Link
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SyncState(
    /** Cached so we do not re-run principal discovery on every pass. */
    val calendarUrl: String? = null,
    val calendarCtag: String? = null,
    val links: Map<String, Link> = emptyMap(),
    /** Events we declined to turn into tasks, so we warn once, not every pass. */
    val ignoredEventUids: Set<String> = emptySet(),
) {
    fun linkByHref(href: String): Link? = links.values.firstOrNull { it.href == href }

    fun withLink(link: Link): SyncState = copy(links = links + (link.taskId to link))

    fun withoutLink(taskId: String): SyncState = copy(links = links - taskId)

    companion object {
        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun load(path: Path): SyncState =
            if (Files.exists(path)) {
                runCatching { json.decodeFromString<SyncState>(Files.readString(path)) }
                    .getOrElse { SyncState() }
            } else {
                SyncState()
            }

        fun save(path: Path, state: SyncState) {
            Files.createDirectories(path.parent)
            // Write to a sibling then move, so an interrupted run cannot leave a half-written
            // state file that would orphan every link and duplicate every event.
            val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
            Files.writeString(tmp, json.encodeToString(state))
            Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
