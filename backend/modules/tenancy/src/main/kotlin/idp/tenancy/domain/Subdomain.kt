package idp.tenancy.domain

import idp.sharedkernel.domain.DomainRuleViolation

private val DNS_LABEL = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")
private const val SERVICE_TENANT = "ops-sso"
private val RESERVED = setOf("ops", SERVICE_TENANT, "platform", "api", "www")

// Punycode would let a subdomain look like another customer's name in the browser.
private const val PUNYCODE_PREFIX = "xn--"

class InvalidSubdomain(
    value: String,
) : DomainRuleViolation("'$value' cannot be a subdomain")

class SubdomainTaken(
    value: String,
) : DomainRuleViolation("Subdomain '$value' is taken")

internal class Subdomain private constructor(
    val value: String,
) {
    fun host(baseDomain: String) = "$value.$baseDomain"

    companion object {
        fun of(value: String): Subdomain {
            val valid = DNS_LABEL.matches(value) && !value.startsWith(PUNYCODE_PREFIX) && value !in RESERVED
            if (!valid) throw InvalidSubdomain(value)
            return Subdomain(value)
        }

        fun serviceTenant() = Subdomain(SERVICE_TENANT)

        fun stored(value: String) = Subdomain(value)
    }
}
