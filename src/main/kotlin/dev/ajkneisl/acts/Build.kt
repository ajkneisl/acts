package dev.ajkneisl.acts

import java.util.Properties

object Build {
    private const val RESOURCE = "/acts.properties"
    private const val UNKNOWN = "unknown"
    val version: String by lazy { runCatching { read("version") }.getOrDefault(UNKNOWN) }

    private fun read(name: String): String {
        val stream = Build::class.java.getResourceAsStream(RESOURCE) ?: return UNKNOWN
        val properties = stream.use { Properties().apply { load(it) } }

        return properties.getProperty(name)?.takeIf { it.isNotBlank() } ?: UNKNOWN
    }
}
