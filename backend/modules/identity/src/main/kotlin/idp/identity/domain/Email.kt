package idp.identity.domain

import idp.sharedkernel.domain.DomainRuleViolation

private const val MAX_LENGTH = 254
private val ADDRESS = Regex("""[^@\s]+@[^@\s]+""")

class InvalidEmail : DomainRuleViolation("An email must be a valid address")

internal class Email private constructor(
    val value: String,
) {
    /** Logs and exception messages print objects, and an address must not reach them. */
    override fun toString(): String = "${value.first()}***@${value.substringAfter('@')}"

    companion object {
        fun of(raw: String): Email {
            val value = raw.trim()
            if (value.length > MAX_LENGTH || !ADDRESS.matches(value)) throw InvalidEmail()
            return Email(value)
        }
    }
}
