package dev.ajkneisl.acts.config

import dev.ajkneisl.acts.Resources

/** A lookup of environment variables, so this stays testable without the real one. */
fun interface EnvLookup {
    operator fun get(name: String): String?
}

/** Every setting, read once, so the whole run sees one picture. */
class Settings(private val values: Map<Setting, String>) {

    /** The resolved value, or null when it is unset and has no default. */
    operator fun get(setting: Setting): String? = values[setting]

    /** For settings with a default, which therefore always have a value. */
    fun text(setting: Setting): String =
        get(setting) ?: error("${setting.variable} has no value and no default.")

    fun int(setting: Setting): Int =
        text(setting).toIntOrNull() ?: invalid(setting, "a whole number")

    fun long(setting: Setting): Long =
        text(setting).toLongOrNull() ?: invalid(setting, "a whole number")

    fun bool(setting: Setting): Boolean =
        text(setting).lowercase().toBooleanStrictOrNull() ?: invalid(setting, "true or false")

    /** Comma-separated, so a list survives a single environment variable. */
    fun list(setting: Setting): List<String> =
        get(setting)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    inline fun <reified E : Enum<E>> enum(setting: Setting): E {
        val raw = text(setting)
        return enumValues<E>().firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: error(
                "${setting.variable} is '$raw', which is not one of: " +
                    enumValues<E>().joinToString(", ") { it.name }
            )
    }

    /** A credential, with the message that explains where to get one when it is missing. */
    fun credential(setting: Setting, missing: String): String =
        get(setting) ?: error(Resources.text(missing))

    /** Resolved settings for logging, with anything secret reduced to whether it is set. */
    fun summary(): String =
        Setting.entries.joinToString("\n") { setting ->
            val value =
                when {
                    setting.secret -> if (get(setting) != null) "(set)" else "(unset)"
                    else -> get(setting) ?: "(unset)"
                }
            "  ${setting.variable.padEnd(40)} $value"
        }

    fun invalid(setting: Setting, expected: String): Nothing =
        error("${setting.variable} is '${get(setting)}', which is not $expected.")

    companion object {
        private val SYSTEM = EnvLookup { System.getenv(it) }

        /** Read every setting from the environment. */
        fun fromEnvironment(env: EnvLookup = SYSTEM): Settings = resolve { env[it.variable] }

        /** Read every setting from [overrides], falling back to the defaults. */
        fun of(vararg overrides: Pair<Setting, String>): Settings {
            val given = overrides.toMap()
            return resolve { given[it] }
        }

        /** A blank value counts as unset, so an empty variable cannot wipe a default. */
        private fun resolve(lookup: (Setting) -> String?): Settings =
            Settings(
                Setting.entries
                    .mapNotNull { setting ->
                        val value = lookup(setting)?.trim()?.takeIf { it.isNotEmpty() }
                        (value ?: setting.default)?.let { setting to it }
                    }
                    .toMap()
            )
    }
}
