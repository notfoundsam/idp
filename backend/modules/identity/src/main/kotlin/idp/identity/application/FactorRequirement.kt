package idp.identity.application

import idp.identity.adapters.persistence.Passkeys
import idp.identity.adapters.persistence.Totps
import idp.identity.adapters.persistence.Users
import idp.sharedkernel.domain.DomainRuleViolation
import org.jooq.DSLContext
import java.util.UUID

/** What the policy of the tenant asks of a user. `authentication` owns the policy and names it, `identity` keeps the factors to it. */
enum class FactorRequirement {
    NONE,
    SECOND_FACTOR,
    PASSKEY,
}

private const val RECOVERY_CODES = "recovery_codes"
private const val REMOVED = "removed"

class FactorRequired : DomainRuleViolation("The policy needs the factor the user would remove")

/** The details of a removed factor tell whether the recovery codes went with it. */
internal fun removedCodes(removed: Boolean): Map<String, String> = if (removed) mapOf(RECOVERY_CODES to REMOVED) else emptyMap()

/**
 * Removing the last factor the policy asks for would leave the account behind the password alone, and the next login would let
 * whoever holds it enroll their own. The lock keeps two removals in parallel from each seeing the other factor left.
 */
internal class FactorFloor(
    private val users: Users,
    private val totps: Totps,
    private val passkeys: Passkeys,
) {
    /** Throws after [removal], so its transaction rolls the removal back. */
    fun <T> removing(
        dsl: DSLContext,
        userId: UUID,
        requirement: FactorRequirement,
        removal: () -> T,
    ): T {
        users.lock(dsl, userId)
        val result = removal()
        if (!isMet(dsl, userId, requirement)) throw FactorRequired()
        return result
    }

    private fun isMet(
        dsl: DSLContext,
        userId: UUID,
        requirement: FactorRequirement,
    ): Boolean {
        val passkey = { passkeys.credentialIds(dsl, userId).isNotEmpty() }
        return when (requirement) {
            FactorRequirement.NONE -> true
            FactorRequirement.PASSKEY -> passkey()
            FactorRequirement.SECOND_FACTOR -> passkey() || totps.find(dsl, userId)?.isConfirmed == true
        }
    }
}
