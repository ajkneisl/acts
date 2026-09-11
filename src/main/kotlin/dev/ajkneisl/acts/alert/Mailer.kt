package dev.ajkneisl.acts.alert

/** Somewhere to send an alert. */
fun interface Mailer {
    fun send(subject: String, body: String)
}
