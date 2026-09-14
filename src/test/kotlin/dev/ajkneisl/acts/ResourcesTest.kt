package dev.ajkneisl.acts

import dev.ajkneisl.acts.config.Setting
import java.io.ByteArrayInputStream
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResourcesTest {

    /** Walks the real resource tree so a newly added file is covered without editing this test. */
    private fun bundled(): List<String> {
        val root = File("src/main/resources")
        assertTrue(root.isDirectory, "expected to run from the project directory")
        return root
            .walkTopDown()
            .filter { it.isFile && (it.extension == "txt" || it.extension == "xml") }
            .map { "/" + it.relativeTo(root).path }
            .filterNot { it == "/logback.xml" }
            .sorted()
            .toList()
    }

    private fun fill(text: String) =
        text
            .replace("{{displayName}}", "Todoist")
            .replace("{{color}}", "#E44332")
            .replace("{{url}}", "https://caldav.icloud.com/")

    @Test
    fun `every bundled resource loads and is not empty`() {
        val all = bundled()
        assertTrue(all.isNotEmpty(), "found no bundled resources")
        for (path in all) {
            val text = Resources.text(path)
            assertTrue(text.isNotBlank(), "$path is blank")
            assertTrue(!text.endsWith("\n"), "$path should have its trailing newline trimmed")
        }
    }

    @Test
    fun `every CalDAV body is well-formed XML once rendered`() {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val bodies = bundled().filter { it.startsWith("/caldav/") }
        assertTrue(bodies.isNotEmpty(), "no CalDAV bodies found")
        for (path in bodies) {
            val xml = fill(Resources.text(path))
            // Throws if malformed, which is the whole point of keeping these as .xml files.
            factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))
        }
    }

    @Test
    fun `error copy names the variable it tells you to set`() {
        assertTrue(
            Resources.text("/text/error-apple-password.txt")
                .contains(Setting.APPLE_PASSWORD.variable)
        )
        assertTrue(
            Resources.text("/text/error-todoist-token.txt").contains(Setting.TODOIST_TOKEN.variable)
        )
        assertTrue(Resources.text("/text/error-apple-id.txt").contains(Setting.APPLE_ID.variable))
    }

    @Test
    fun `template substitutes placeholders`() {
        val rendered =
            Resources.template(
                "/caldav/mkcalendar.xml",
                "displayName" to "Todoist",
                "color" to "#E44332",
            )
        assertTrue(rendered.contains("<d:displayname>Todoist</d:displayname>"), rendered)
        assertTrue(rendered.contains("<i:calendar-color>#E44332</i:calendar-color>"), rendered)
        assertTrue(!rendered.contains("{{"), "placeholder left behind")
    }

    @Test
    fun `template fails loudly when a value is missing`() {
        // A half-rendered message is worse than a crash at startup.
        assertFailsWith<IllegalStateException> {
            Resources.template("/caldav/mkcalendar.xml", "displayName" to "Todoist")
        }
    }

    @Test
    fun `a missing resource names the file it expected`() {
        val failure = assertFailsWith<IllegalStateException> { Resources.text("/text/nope.txt") }
        assertTrue(failure.message!!.contains("/text/nope.txt"), failure.message!!)
    }

    @Test
    fun `no resource is left holding an unfilled placeholder in the shipped text`() {
        // Every placeholder the templates use must be one the tests know how to fill, which keeps
        // this suite honest as new placeholders are added.
        val unfilled = Regex("""\{\{(\w+)}}""")
        for (path in bundled()) {
            val remaining = unfilled.findAll(fill(Resources.text(path))).map { it.value }.toList()
            assertEquals(emptyList(), remaining, "$path has placeholders this test cannot fill")
        }
    }
}
