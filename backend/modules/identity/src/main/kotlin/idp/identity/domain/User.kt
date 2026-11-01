package idp.identity.domain

import idp.sharedkernel.domain.DomainRuleViolation
import idp.sharedkernel.security.UserRole
import idp.sharedkernel.security.UserRole.TENANT_ADMIN
import idp.sharedkernel.security.UserStatus
import idp.sharedkernel.security.UserStatus.ACTIVE
import idp.sharedkernel.security.UserStatus.BLOCKED
import idp.sharedkernel.security.UserStatus.INVITED
import java.util.UUID

class ForbiddenUserChange(
    message: String,
) : DomainRuleViolation(message)

class LastAdminRequired(
    id: UUID,
) : DomainRuleViolation("User $id is the last admin of the tenant")

class EmailTaken : DomainRuleViolation("The tenant already has a user with this email")

/** Answers for login and status. The profile and each credential are aggregates of their own, linked by ID. */
internal class User(
    val id: UUID,
    val emailIndex: ByteArray,
    val emailCiphertext: ByteArray,
    role: UserRole,
    status: UserStatus,
    val version: Long,
) {
    var role = role
        private set
    var status = status
        private set

    fun activate() = move(INVITED, to = ACTIVE)

    fun block(otherActiveAdmins: Int) {
        ensureNotLastAdmin(otherActiveAdmins)
        move(ACTIVE, to = BLOCKED)
    }

    fun unblock() = move(BLOCKED, to = ACTIVE)

    fun changeRole(
        role: UserRole,
        otherActiveAdmins: Int,
    ) {
        if (role != TENANT_ADMIN) ensureNotLastAdmin(otherActiveAdmins)
        this.role = role
    }

    fun ensureErasable(otherActiveAdmins: Int) = ensureNotLastAdmin(otherActiveAdmins)

    /** Only an active admin can replace this one: an invitation may expire, and a blocked admin cannot act. */
    private fun ensureNotLastAdmin(otherActiveAdmins: Int) {
        if (role == TENANT_ADMIN && status != BLOCKED && otherActiveAdmins == 0) throw LastAdminRequired(id)
    }

    private fun move(
        from: UserStatus,
        to: UserStatus,
    ) {
        if (status != from) throw ForbiddenUserChange("A user cannot go from $status to $to")
        status = to
    }

    companion object {
        fun invited(
            id: UUID,
            emailIndex: ByteArray,
            emailCiphertext: ByteArray,
            role: UserRole,
        ) = User(id, emailIndex, emailCiphertext, role, INVITED, version = 0)
    }
}
