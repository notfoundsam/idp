package idp.worker.mail

import java.util.UUID

/** An email change has three addresses in play, and none of them may travel in an event. */
enum class MailAddress {
    CURRENT,

    /** The address that waits for its confirmation. */
    PENDING,

    /** The address the last email change left behind. */
    PREVIOUS,
}

/** Events carry only IDs, so this is the one way from a user to an address. */
fun interface MailRecipients {
    /** Null when there is no such address: such a mail is dropped, because no retry can bring it back. */
    fun addressOf(
        userId: UUID,
        which: MailAddress,
    ): String?
}
