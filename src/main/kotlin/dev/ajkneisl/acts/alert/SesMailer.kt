package dev.ajkneisl.acts.alert

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.net.ssl.SSLSocketFactory
import org.slf4j.LoggerFactory

/** Send mail to Amazon SES. */
class SesMailer(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val from: String,
    private val recipients: List<String>,
    private val connect: () -> Socket = { SSLSocketFactory.getDefault().createSocket(host, port) },
) : Mailer {
    class MailException(message: String) : RuntimeException(message)

    private val log = LoggerFactory.getLogger(SesMailer::class.java)

    override fun send(subject: String, body: String) {
        require(recipients.isNotEmpty()) { "No recipients configured" }
        connect().use { socket ->
            socket.soTimeout = TIMEOUT_MILLIS
            val reader =
                BufferedReader(InputStreamReader(socket.inputStream, StandardCharsets.UTF_8))
            val writer =
                BufferedWriter(OutputStreamWriter(socket.outputStream, StandardCharsets.UTF_8))
            val session = Session(reader, writer)

            session.expect(220, "greeting")
            session.command("EHLO acts", 250)

            session.command("AUTH LOGIN", 334)
            session.command(base64(username), 334)
            session.command(base64(password), 235)

            session.command("MAIL FROM:<$from>", 250)
            for (recipient in recipients) {
                session.command("RCPT TO:<$recipient>", 250)
            }

            session.command("DATA", 354)
            session.raw(message(subject, body))
            session.expect(250, "message body")

            // Politeness only: the message is already accepted by this point.
            runCatching { session.command("QUIT", 221) }
        }
        log.info("Sent alert to {}", recipients.joinToString(", "))
    }

    private fun message(subject: String, body: String): String {
        val encoded =
            Base64.getMimeEncoder(76, "\r\n".toByteArray())
                .encodeToString(body.toByteArray(StandardCharsets.UTF_8))
        return buildString {
            append("From: ").append(from).append(CRLF)
            append("To: ").append(recipients.joinToString(", ")).append(CRLF)
            append("Subject: ").append(subject).append(CRLF)
            append("Date: ")
                .append(DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now()))
                .append(CRLF)
            append("MIME-Version: 1.0").append(CRLF)
            append("Content-Type: text/plain; charset=utf-8").append(CRLF)
            append("Content-Transfer-Encoding: base64").append(CRLF)
            append(CRLF)
            append(encoded).append(CRLF)
            // A lone dot ends the data; base64 never produces one, but be explicit.
            append(".").append(CRLF)
        }
    }

    private fun base64(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    /** One SMTP conversation. */
    private class Session(private val reader: BufferedReader, private val writer: BufferedWriter) {

        fun command(line: String, expected: Int) {
            raw(line + CRLF)
            expect(expected, line.substringBefore(' '))
        }

        fun raw(text: String) {
            writer.write(text)
            writer.flush()
        }

        fun expect(code: Int, what: String) {
            val reply = read()
            if (!reply.startsWith(code.toString())) {
                throw MailException("SES rejected $what: $reply")
            }
        }

        /** Reads a reply, following the `250-` continuation form to its final `250 ` line. */
        private fun read(): String {
            val lines = mutableListOf<String>()
            while (true) {
                val line = reader.readLine() ?: throw MailException("SES closed the connection")
                lines += line
                if (line.length < 4 || line[3] != '-') break
            }
            return lines.joinToString(" | ")
        }
    }

    private companion object {
        const val CRLF = "\r\n"
        const val TIMEOUT_MILLIS = 30_000
    }
}
