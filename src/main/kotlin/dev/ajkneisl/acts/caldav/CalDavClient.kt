package dev.ajkneisl.acts.caldav

import dev.ajkneisl.acts.Resources
import dev.ajkneisl.acts.caldav.models.CalendarCollection
import dev.ajkneisl.acts.caldav.models.CalendarObject
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory
import org.slf4j.LoggerFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

private const val NS_DAV = "DAV:"
private const val NS_CALDAV = "urn:ietf:params:xml:ns:caldav"
private const val NS_APPLE = "http://calendarserver.org/ns/"

/** Talks CalDAV to one calendar collection. */
class CalDavClient(
    private val username: String,
    password: String,
) {
    private val log = LoggerFactory.getLogger(CalDavClient::class.java)
    private val http: HttpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

    private val authHeader =
        "Basic " +
            Base64.getEncoder()
                .encodeToString("$username:$password".toByteArray(StandardCharsets.UTF_8))

    /** Resolve the user's principal URL from the [serverUrl]. */
    fun findPrincipal(serverUrl: String): String {
        val root = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val responses = propfind(root, depth = 0, body = Resources.text(PROPFIND_PRINCIPAL))

        val href =
            responses.firstNotNullOfOrNull { r ->
                r.props["{$NS_DAV}current-user-principal"]?.let { extractHref(it) }
            }
                ?: throw CalDavException(
                    Resources.template("/text/error-no-principal.txt", "url" to root)
                )

        return resolve(root, href)
    }

    /** Resolve the calendar-home-set for a given [principalUrl]. */
    fun findCalendarHome(principalUrl: String): String {
        val responses =
            propfind(principalUrl, depth = 0, body = Resources.text(PROPFIND_CALENDAR_HOME))

        val href =
            responses.firstNotNullOfOrNull { r ->
                r.props["{$NS_CALDAV}calendar-home-set"]?.let { extractHref(it) }
            } ?: throw CalDavException("No calendar-home-set for principal $principalUrl")

        return resolve(principalUrl, href)
    }

    /** Lists the calendar collections in a [calendarHomeUrl]. */
    fun listCalendars(calendarHomeUrl: String): List<CalendarCollection> {
        val body = Resources.text(PROPFIND_CALENDARS)

        return propfind(calendarHomeUrl, depth = 1, body = body).mapNotNull { r ->
            val resourceType = r.propElements["{$NS_DAV}resourcetype"] ?: return@mapNotNull null

            val isCalendar =
                childElements(resourceType).any {
                    it.localName == "calendar" && it.namespaceURI == NS_CALDAV
                }
            if (!isCalendar) return@mapNotNull null

            val comps = r.propElements["{$NS_CALDAV}supported-calendar-component-set"]

            val supportsEvents =
                comps == null ||
                    childElements(comps).any {
                        it.getAttribute("name").equals("VEVENT", ignoreCase = true)
                    }

            CalendarCollection(
                href = resolve(calendarHomeUrl, r.href),
                displayName = r.props["{$NS_DAV}displayname"]?.trim(),
                ctag = r.props["{$NS_APPLE}getctag"]?.trim(),
                supportsEvents = supportsEvents,
            )
        }
    }

    /** Creates a VEVENT-only calendar collection and returns its URL. */
    fun createCalendar(
        calendarHomeUrl: String,
        displayName: String,
        color: String = "#E44332",
    ): String {
        val slug =
            displayName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty {
                "acts"
            }

        val url = resolve(calendarHomeUrl, "$slug-${System.currentTimeMillis().toString(36)}/")
        val body =
            Resources.template(
                MKCALENDAR,
                "displayName" to escapeXml(displayName),
                "color" to escapeXml(color),
            )
        val response =
            request(
                "MKCALENDAR",
                url,
                body,
                mapOf("Content-Type" to "application/xml; charset=utf-8"),
            )

        if (response.statusCode() !in 200..299) {
            throw CalDavException(
                "MKCALENDAR $url failed: HTTP ${response.statusCode()} ${
                    response.body().take(300)
                }",
                response.statusCode(),
            )
        }

        log.info("Created calendar '{}' at {}", displayName, url)
        return url
    }

    /** Reads the collection CTAG. */
    fun collectionCtag(calendarUrl: String): String? {
        return propfind(calendarUrl, depth = 0, body = Resources.text(PROPFIND_CTAG))
            .firstNotNullOfOrNull { it.props["{$NS_APPLE}getctag"]?.trim() }
    }

    /** Fetches every VEVENT resource in the collection with its data. */
    fun listEvents(calendarUrl: String): List<CalendarObject> {
        val body = Resources.text(REPORT_EVENTS)

        return report(calendarUrl, depth = 1, body = body).mapNotNull { r ->
            val data = r.props["{$NS_CALDAV}calendar-data"] ?: return@mapNotNull null

            CalendarObject(
                href = resolve(calendarUrl, r.href),
                etag = r.props["{$NS_DAV}getetag"]?.trim(),
                data = data,
            )
        }
    }

    /** Writes a resource. Returns an ETag if there's one. */
    fun putEvent(
        url: String,
        ics: String,
        ifMatch: String? = null,
        ifNoneMatch: Boolean = false,
    ): String? {
        val headers = buildMap {
            put("Content-Type", "text/calendar; charset=utf-8")
            ifMatch?.let { put("If-Match", it) }
            if (ifNoneMatch) put("If-None-Match", "*")
        }

        val response = request("PUT", url, ics, headers)
        when (response.statusCode()) {
            in 200..299 -> Unit
            412 -> throw PreconditionFailed("Server copy of $url changed under us")
            else ->
                throw CalDavException(
                    "PUT $url failed: HTTP ${response.statusCode()} ${response.body().take(300)}",
                    response.statusCode(),
                )
        }

        return response.headers().firstValue("ETag").orElse(null)?.trim()
    }

    /** Delete an event by its [url]. */
    fun deleteEvent(url: String, ifMatch: String? = null) {
        val headers = buildMap { ifMatch?.let { put("If-Match", it) } }
        val response = request("DELETE", url, null, headers)
        when (response.statusCode()) {
            in 200..299,
            404 -> Unit

            412 -> throw PreconditionFailed("Server copy of $url changed under us")
            else ->
                throw CalDavException(
                    "DELETE $url failed: HTTP ${response.statusCode()}",
                    response.statusCode(),
                )
        }
    }

    /** Re-reads a single resource's ETag.. */
    fun etagOf(url: String): String? {
        return runCatching {
            propfind(url, depth = 0, body = Resources.text(PROPFIND_ETAG)).firstNotNullOfOrNull {
                it.props["{$NS_DAV}getetag"]?.trim()
            }
        }
            .getOrNull()
    }

    /** A DAV response. */
    private data class DavResponse(
        val href: String,
        val props: Map<String, String>,
        val propElements: Map<String, Element>,
    )

    private fun propfind(url: String, depth: Int, body: String): List<DavResponse> =
        multistatus("PROPFIND", url, depth, body)

    private fun report(url: String, depth: Int, body: String): List<DavResponse> =
        multistatus("REPORT", url, depth, body)

    private fun multistatus(
        method: String,
        url: String,
        depth: Int,
        body: String,
    ): List<DavResponse> {
        val response =
            request(
                method,
                url,
                body,
                mapOf(
                    "Depth" to depth.toString(),
                    "Content-Type" to "application/xml; charset=utf-8",
                ),
            )
        if (response.statusCode() !in 200..299) {
            // iCloud answers 401 when no credentials are sent and 403 when it rejects the ones
            // it got, so 403 is what a wrong or non-app-specific password actually looks like.
            val hint =
                when (response.statusCode()) {
                    401 -> Resources.text("/text/error-caldav-401.txt")
                    403 -> Resources.text("/text/error-caldav-403.txt")
                    else -> ""
                }
            throw CalDavException(
                "$method $url failed: HTTP ${response.statusCode()}$hint ${
                    response.body().take(300)
                }",
                response.statusCode(),
            )
        }
        return parseMultistatus(response.body())
    }

    private fun request(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String>,
    ): HttpResponse<String> {
        var target = url
        repeat(MAX_REDIRECTS) {
            val builder =
                HttpRequest.newBuilder(URI.create(target))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", authHeader)
                    .header("User-Agent", USER_AGENT)
            headers.forEach { (k, v) -> builder.header(k, v) }
            val publisher =
                body?.let {
                    HttpRequest.BodyPublishers.ofString(it, StandardCharsets.UTF_8)
                } ?: HttpRequest.BodyPublishers.noBody()

            val response =
                http.send(
                    builder.method(method, publisher).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
                )
            if (response.statusCode() !in setOf(301, 302, 303, 307, 308)) return response

            val location = response.headers().firstValue("Location").orElse(null) ?: return response
            target = resolve(target, location)
            log.debug("{} redirected to {}", method, target)
        }
        throw CalDavException("Too many redirects for $method $url")
    }

    // ---------------------------------------------------------------- xml bits

    private fun parseMultistatus(xml: String): List<DavResponse> {
        val doc =
            documentBuilder().parse(ByteArrayInputStream(xml.toByteArray(StandardCharsets.UTF_8)))
        val out = mutableListOf<DavResponse>()
        val responses = doc.getElementsByTagNameNS(NS_DAV, "response")
        for (i in 0 until responses.length) {
            val responseEl = responses.item(i) as Element
            val href =
                childElements(responseEl)
                    .firstOrNull { it.localName == "href" && it.namespaceURI == NS_DAV }
                    ?.textContent
                    ?.trim() ?: continue

            val props = mutableMapOf<String, String>()
            val propElements = mutableMapOf<String, Element>()
            for (propstat in childElements(responseEl).filter { it.localName == "propstat" }) {
                val status =
                    childElements(propstat)
                        .firstOrNull { it.localName == "status" }
                        ?.textContent
                        .orEmpty()
                // Skip 404/403 propstat blocks so absent properties stay absent.
                if (!status.contains(" 200 ")) continue
                val prop =
                    childElements(propstat).firstOrNull { it.localName == "prop" } ?: continue
                for (p in childElements(prop)) {
                    val key = "{${p.namespaceURI ?: NS_DAV}}${p.localName}"
                    props[key] = p.textContent ?: ""
                    propElements[key] = p
                }
            }
            out += DavResponse(href, props, propElements)
        }
        return out
    }

    private fun documentBuilder() =
        DocumentBuilderFactory.newInstance()
            .apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
                runCatching {
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                }
                runCatching {
                    setFeature("http://xml.org/sax/features/external-general-entities", false)
                }
                runCatching {
                    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                }
                runCatching { setXIncludeAware(false) }
            }
            .newDocumentBuilder()

    private fun childElements(node: Node): List<Element> {
        val out = mutableListOf<Element>()
        val kids = node.childNodes
        for (i in 0 until kids.length) {
            (kids.item(i) as? Element)?.let { out += it }
        }
        return out
    }

    /** Pulls the single `<d:href>` out of a property value such as calendar-home-set. */
    private fun extractHref(text: String): String? = text.trim().ifEmpty { null }

    private companion object {
        const val MAX_REDIRECTS = 5
        const val USER_AGENT = "acts/1.0"

        const val PROPFIND_PRINCIPAL = "/caldav/propfind-principal.xml"
        const val PROPFIND_CALENDAR_HOME = "/caldav/propfind-calendar-home.xml"
        const val PROPFIND_CALENDARS = "/caldav/propfind-calendars.xml"
        const val PROPFIND_CTAG = "/caldav/propfind-ctag.xml"
        const val PROPFIND_ETAG = "/caldav/propfind-etag.xml"
        const val REPORT_EVENTS = "/caldav/report-events.xml"
        const val MKCALENDAR = "/caldav/mkcalendar.xml"
    }
}
