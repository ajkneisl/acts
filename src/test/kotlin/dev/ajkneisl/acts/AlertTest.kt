package dev.ajkneisl.acts

import dev.ajkneisl.acts.alert.ErrorNotifier
import dev.ajkneisl.acts.alert.Mailer
import dev.ajkneisl.acts.alert.SesMailer
import dev.ajkneisl.acts.health.SyncHealth
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A throwaway SMTP server, so the client is exercised over a real socket. */
class FakeSmtpServer(private val failAt: String? = null) : AutoCloseable {
    private val server = ServerSocket(0)
    val port: Int get() = server.localPort
    val received = StringBuilder()
    private val done = CountDownLatch(1)

    init {
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { socket -> converse(socket) }
            }
            done.countDown()
        }
    }

    private fun converse(socket: Socket) {
        val reader = BufferedReader(InputStreamReader(socket.inputStream))
        val writer = PrintWriter(socket.outputStream, true)
        writer.print("220 fake ESMTP\r\n")
        writer.flush()

        var inData = false
        // AUTH LOGIN is a three-step exchange: the verb, then the username, then the password,
        // each of the last two arriving as a bare base64 token with no verb to match on.
        var authStep = 0

        while (true) {
            val line = reader.readLine() ?: return
            received.append(line).append('\n')

            if (inData) {
                if (line == ".") {
                    inData = false
                    writer.print("250 Ok: queued\r\n")
                    writer.flush()
                }
                continue
            }

            val verb = line.substringBefore(' ').uppercase()
            if (failAt != null && verb == failAt) {
                writer.print("535 Authentication credentials invalid\r\n")
                writer.flush()
                return
            }
            when {
                verb == "EHLO" -> writer.print("250-fake\r\n250 AUTH LOGIN\r\n")
                verb == "AUTH" -> { authStep = 1; writer.print("334 VXNlcm5hbWU6\r\n") }
                authStep == 1 -> { authStep = 2; writer.print("334 UGFzc3dvcmQ6\r\n") }
                authStep == 2 -> { authStep = 0; writer.print("235 Authenticated\r\n") }
                verb == "MAIL" -> writer.print("250 Ok\r\n")
                verb == "RCPT" -> writer.print("250 Ok\r\n")
                verb == "DATA" -> { inData = true; writer.print("354 End with .\r\n") }
                verb == "QUIT" -> { writer.print("221 Bye\r\n"); writer.flush(); return }
                else -> writer.print("250 Ok\r\n")
            }
            writer.flush()
        }
    }

    fun awaitClose(): Boolean = done.await(5, TimeUnit.SECONDS)

    override fun close() {
        runCatching { server.close() }
    }
}

class SesMailerTest {

    private var server: FakeSmtpServer? = null

    @AfterTest
    fun stop() {
        server?.close()
    }

    private fun mailer(fake: FakeSmtpServer, to: List<String> = listOf("ops@example.com")) =
        SesMailer(
            host = "localhost",
            port = fake.port,
            username = "AKIAEXAMPLE",
            password = "secret",
            from = "alerts@example.com",
            recipients = to,
            connect = { Socket("localhost", fake.port) },
        )

    @Test
    fun `sends a message through the whole SMTP conversation`() {
        val fake = FakeSmtpServer().also { server = it }
        mailer(fake).send("acts sync is failing", "Something went wrong")
        fake.awaitClose()

        val log = fake.received.toString()
        assertTrue(log.contains("EHLO acts"), log)
        assertTrue(log.contains("AUTH LOGIN"), log)
        assertTrue(log.contains("MAIL FROM:<alerts@example.com>"), log)
        assertTrue(log.contains("RCPT TO:<ops@example.com>"), log)
        assertTrue(log.contains("Subject: acts sync is failing"), log)
        assertTrue(log.contains("QUIT"), log)
    }

    @Test
    fun `credentials are sent base64 encoded`() {
        val fake = FakeSmtpServer().also { server = it }
        mailer(fake).send("s", "b")
        fake.awaitClose()

        val log = fake.received.toString()
        assertTrue(log.contains(Base64.getEncoder().encodeToString("AKIAEXAMPLE".toByteArray())), log)
        assertTrue(log.contains(Base64.getEncoder().encodeToString("secret".toByteArray())), log)
        // And never in the clear.
        assertTrue(!log.contains("\nsecret"), "the password went out unencoded")
    }

    @Test
    fun `the body survives as UTF-8`() {
        val fake = FakeSmtpServer().also { server = it }
        val body = "Task: café review — every mon"
        mailer(fake).send("s", body)
        fake.awaitClose()

        // Base64 avoids having to negotiate 8BITMIME for a non-ASCII body.
        val log = fake.received.toString()
        assertTrue(log.contains("Content-Transfer-Encoding: base64"), log)
        val encoded = log.lines().dropWhile { it.isNotEmpty() }.joinToString("").trim().removeSuffix(".")
        assertTrue(
            String(Base64.getMimeDecoder().decode(encoded)).contains(body),
            "body did not round-trip",
        )
    }

    @Test
    fun `every recipient gets its own RCPT`() {
        val fake = FakeSmtpServer().also { server = it }
        mailer(fake, listOf("a@example.com", "b@example.com")).send("s", "b")
        fake.awaitClose()

        val log = fake.received.toString()
        assertTrue(log.contains("RCPT TO:<a@example.com>"), log)
        assertTrue(log.contains("RCPT TO:<b@example.com>"), log)
    }

    @Test
    fun `a rejection is reported, not swallowed`() {
        val fake = FakeSmtpServer(failAt = "MAIL").also { server = it }
        val failure = assertFailsWith<SesMailer.MailException> { mailer(fake).send("s", "b") }
        assertTrue(failure.message!!.contains("535"), failure.message!!)
    }
}

class ErrorNotifierTest {

    private class Recorder : Mailer {
        val sent = mutableListOf<Pair<String, String>>()

        override fun send(subject: String, body: String) {
            sent += subject to body
        }
    }

    private var now = Instant.parse("2026-09-11T12:00:00Z")
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId) = this
        override fun instant() = now
    }

    private fun notifier(mailer: Mailer, health: SyncHealth = SyncHealth()) =
        ErrorNotifier(mailer, Duration.ofMinutes(60), health, clock)

    @Test
    fun `the first failure sends one alert`() {
        val mailer = Recorder()
        notifier(mailer).onFailure("iCloud unreachable", 1)

        assertEquals(1, mailer.sent.size)
        assertTrue(mailer.sent.single().first.contains("failing"))
        assertTrue(mailer.sent.single().second.contains("iCloud unreachable"))
    }

    @Test
    fun `the failure body renders every field, with nothing left unsubstituted`() {
        val mailer = Recorder()
        val health = SyncHealth()
        health.recordSuccess()
        notifier(mailer, health).onFailure("iCloud unreachable", 4)

        val body = mailer.sent.single().second
        assertTrue(body.contains("iCloud unreachable"), body)
        assertTrue(body.contains("Consecutive failures:  4"), body)
        assertTrue(body.contains("Running since:"), body)
        assertTrue(!body.contains("{{"), "a placeholder was left unsubstituted:\n$body")
    }

    @Test
    fun `the recovery body says when, with nothing left unsubstituted`() {
        val mailer = Recorder()
        val n = notifier(mailer)
        n.onFailure("boom", 1)
        n.onSuccess()

        val body = mailer.sent.last().second
        assertTrue(body.contains("working again"), body)
        assertTrue(!body.contains("{{"), "a placeholder was left unsubstituted:\n$body")
    }

    @Test
    fun `a continuing outage does not send again until the cooldown passes`() {
        // The loop retries every few seconds; without this an outage means hundreds of emails.
        val mailer = Recorder()
        val n = notifier(mailer)

        repeat(50) { n.onFailure("iCloud unreachable", it + 1) }
        assertEquals(1, mailer.sent.size)

        now = now.plus(Duration.ofMinutes(61))
        n.onFailure("iCloud unreachable", 51)
        assertEquals(2, mailer.sent.size)
        assertTrue(mailer.sent.last().first.contains("still failing"))
    }

    @Test
    fun `recovery sends exactly one all-clear`() {
        val mailer = Recorder()
        val n = notifier(mailer)

        n.onFailure("boom", 1)
        n.onSuccess()
        n.onSuccess()
        n.onSuccess()

        assertEquals(2, mailer.sent.size)
        assertTrue(mailer.sent.last().first.contains("recovered"))
    }

    @Test
    fun `a success with nothing wrong says nothing`() {
        val mailer = Recorder()
        notifier(mailer).onSuccess()
        assertTrue(mailer.sent.isEmpty())
    }

    @Test
    fun `after recovering, the next outage alerts immediately`() {
        val mailer = Recorder()
        val n = notifier(mailer)

        n.onFailure("boom", 1)
        n.onSuccess()
        n.onFailure("boom again", 1)

        assertEquals(3, mailer.sent.size)
        assertTrue(mailer.sent.last().second.contains("boom again"))
    }

    @Test
    fun `a broken mail server never takes the sync down`() {
        val exploding = Mailer { _, _ -> throw SesMailer.MailException("no route to host") }
        // Must not propagate: the sync loop is more important than the alert.
        notifier(exploding).onFailure("boom", 1)
    }
}
