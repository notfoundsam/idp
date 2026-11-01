package idp.authentication.domain

internal enum class AuthFactor {
    PASSWORD,
    TOTP,
    PASSKEY,
    RECOVERY_CODE,
}

internal sealed interface FlowStep {
    /** Takes a login or a passkey. */
    data object Identify : FlowStep

    /** Comes for every login, known or not, and also takes a passkey: a user without a password must not be told apart here. */
    data object Password : FlowStep

    data class SecondFactor(
        val factors: Set<AuthFactor>,
    ) : FlowStep

    data class EnrollMfa(
        val factors: Set<AuthFactor>,
    ) : FlowStep

    data object Complete : FlowStep
}
