package com.coursework.lms.core.email

/** Delivers plain-text emails to users. */
interface EmailSender {

    /** Sends [body] to [to] under [subject]; throws when the mail server does not accept it. */
    fun send(
        to: String,
        subject: String,
        body: String,
    )
}
