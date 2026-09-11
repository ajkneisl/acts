package dev.ajkneisl.acts

import java.util.concurrent.ConcurrentHashMap

/** Handle resources. */
object Resources {
    private val cache = ConcurrentHashMap<String, String>()
    private val placeholder = Regex("""\{\{(\w+)}}""")

    /** Pull a string resource from [path]. */
    fun text(path: String): String =
        cache.getOrPut(path) {
            val stream =
                Resources::class.java.getResourceAsStream(path)
                    ?: error(
                        "Missing bundled resource '$path'. It should live under src/main/resources."
                    )
            stream.use { it.readBytes().toString(Charsets.UTF_8) }.trim('\n', '\r')
        }

    /** Reads a resource and substitutes `{{name}}` placeholders. */
    fun template(path: String, vars: Map<String, String>): String {
        val rendered =
            placeholder.replace(text(path)) { match ->
                val key = match.groupValues[1]
                vars[key] ?: error("Resource '$path' uses {{$key}}, which was not supplied.")
            }
        check(!placeholder.containsMatchIn(rendered)) {
            "Resource '$path' still has unresolved placeholders after rendering."
        }
        return rendered
    }

    fun template(path: String, vararg vars: Pair<String, String>): String =
        template(path, vars.toMap())
}
