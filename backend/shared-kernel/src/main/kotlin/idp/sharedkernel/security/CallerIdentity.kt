package idp.sharedkernel.security

/** Names whoever started this process, as the platform proved it: an audit actor must not come from an argument. */
fun interface CallerIdentity {
    fun name(): String
}
