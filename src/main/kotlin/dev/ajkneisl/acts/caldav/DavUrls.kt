package dev.ajkneisl.acts.caldav

import java.net.URI

/** Resolves a possibly-relative href against a base URL. */
internal fun resolve(base: String, href: String): String =
    runCatching { URI.create(base).resolve(href.trim()).toString() }.getOrDefault(href)

internal fun escapeXml(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
