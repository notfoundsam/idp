package idp.worker.mail

import java.util.UUID

/** Events carry only IDs, so this is the one way from a user to an address. */
fun interface MailRecipients {
    /** Null when the user is gone: such a mail is dropped, because no retry can bring the address back. */
    fun addressOf(userId: UUID): String?
}
