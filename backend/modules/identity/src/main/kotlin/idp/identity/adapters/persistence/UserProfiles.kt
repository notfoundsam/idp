package idp.identity.adapters.persistence

import idp.db.jooq.tenant.tables.UserProfiles.USER_PROFILES
import idp.identity.domain.UserProfile
import idp.sharedkernel.persistence.VersionedUpdate
import org.jooq.DSLContext
import java.util.UUID

internal class UserProfiles {
    private val versioned = VersionedUpdate(USER_PROFILES, USER_PROFILES.USER_ID, USER_PROFILES.VERSION)

    fun add(
        dsl: DSLContext,
        profile: UserProfile,
    ) {
        dsl
            .insertInto(USER_PROFILES)
            .set(USER_PROFILES.USER_ID, profile.userId)
            .set(USER_PROFILES.NAME_CIPHERTEXT, profile.nameCiphertext)
            .set(USER_PROFILES.PHONE_CIPHERTEXT, profile.phoneCiphertext)
            .execute()
    }

    fun find(
        dsl: DSLContext,
        userId: UUID,
    ): UserProfile? =
        dsl.selectFrom(USER_PROFILES).where(USER_PROFILES.USER_ID.eq(userId)).fetchOne { row ->
            UserProfile(row.userId!!, row.nameCiphertext, row.phoneCiphertext, row.version!!)
        }

    fun save(
        dsl: DSLContext,
        profile: UserProfile,
    ): Long =
        versioned.execute(
            dsl,
            profile.userId,
            profile.version,
            mapOf(
                USER_PROFILES.NAME_CIPHERTEXT to profile.nameCiphertext,
                USER_PROFILES.PHONE_CIPHERTEXT to profile.phoneCiphertext,
            ),
        )
}
