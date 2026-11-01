package idp.identity.adapters.persistence

import idp.db.jooq.tenant.tables.Users.USERS
import idp.db.jooq.tenant.tables.records.UsersRecord
import idp.identity.domain.User
import idp.sharedkernel.persistence.VersionedUpdate
import idp.sharedkernel.security.UserRole
import idp.sharedkernel.security.UserStatus
import org.jooq.DSLContext
import java.util.UUID

internal class Users {
    private val versioned = VersionedUpdate(USERS, USERS.ID, USERS.VERSION)

    /** False when the tenant already has this email. */
    fun add(
        dsl: DSLContext,
        user: User,
    ): Boolean =
        dsl
            .insertInto(USERS)
            .set(USERS.ID, user.id)
            .set(USERS.EMAIL_INDEX, user.emailIndex)
            .set(USERS.EMAIL_CIPHERTEXT, user.emailCiphertext)
            .set(USERS.ROLE, user.role.name)
            .set(USERS.STATUS, user.status.name)
            .onConflict(USERS.EMAIL_INDEX)
            .doNothing()
            .execute() == 1

    fun find(
        dsl: DSLContext,
        id: UUID,
    ): User? = dsl.selectFrom(USERS).where(USERS.ID.eq(id)).fetchOne(::toUser)

    fun findByEmailIndex(
        dsl: DSLContext,
        index: ByteArray,
    ): User? = dsl.selectFrom(USERS).where(USERS.EMAIL_INDEX.eq(index)).fetchOne(::toUser)

    /** Sorted, so two callers cannot deadlock on each other's rows. */
    fun lockActiveAdmins(dsl: DSLContext): Set<UUID> =
        dsl
            .select(USERS.ID)
            .from(USERS)
            .where(USERS.ROLE.eq(UserRole.TENANT_ADMIN.name), USERS.STATUS.eq(UserStatus.ACTIVE.name))
            .orderBy(USERS.ID)
            .forUpdate()
            .fetchSet(USERS.ID)

    fun save(
        dsl: DSLContext,
        user: User,
    ): Long =
        versioned.execute(
            dsl,
            user.id,
            user.version,
            mapOf(USERS.ROLE to user.role.name, USERS.STATUS to user.status.name),
        )

    fun delete(
        dsl: DSLContext,
        id: UUID,
    ) {
        dsl.deleteFrom(USERS).where(USERS.ID.eq(id)).execute()
    }

    private fun toUser(row: UsersRecord) =
        User(
            row.id!!,
            row.emailIndex!!,
            row.emailCiphertext!!,
            UserRole.valueOf(row.role!!),
            UserStatus.valueOf(row.status!!),
            row.version!!,
        )
}
