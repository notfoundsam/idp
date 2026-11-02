package idp.identity.application

object IdentityEvents {
    const val AGGREGATE = "user"
    const val INVITED = "user.invited"
    const val PASSWORD_RESET_REQUESTED = "user.password_reset_requested"
    const val EMAIL_CHANGE_REQUESTED = "user.email_change_requested"
    const val EMAIL_CHANGED = "user.email_changed"
}
