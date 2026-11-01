package idp.identity.domain

import idp.sharedkernel.security.UserRole
import idp.sharedkernel.security.UserRole.TENANT_ADMIN
import idp.sharedkernel.security.UserRole.USER
import idp.sharedkernel.security.UserStatus
import idp.sharedkernel.security.UserStatus.ACTIVE
import idp.sharedkernel.security.UserStatus.BLOCKED
import idp.sharedkernel.security.UserStatus.INVITED
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

private fun user(
    status: UserStatus = ACTIVE,
    role: UserRole = USER,
) = User(UUID.randomUUID(), byteArrayOf(1), byteArrayOf(2), role, status, version = 0)

class UserTest :
    FunSpec({
        context("a new user") {
            test("waits for the invitation with the given role") {
                val user = User.invited(UUID.randomUUID(), byteArrayOf(1), byteArrayOf(2), TENANT_ADMIN)

                user.status shouldBe INVITED
                user.role shouldBe TENANT_ADMIN
                user.version shouldBe 0
            }

            test("becomes active by accepting") {
                user(INVITED).apply { activate() }.status shouldBe ACTIVE
            }
        }

        context("statuses") {
            test("an active user can be blocked and unblocked") {
                val user = user(ACTIVE)

                user.block(otherActiveAdmins = 0)
                user.status shouldBe BLOCKED

                user.unblock()
                user.status shouldBe ACTIVE
            }

            test("only an invited user accepts") {
                listOf(ACTIVE, BLOCKED).forEach { status ->
                    shouldThrow<ForbiddenUserChange> { user(status).activate() }
                }
            }

            test("only an active user is blocked") {
                listOf(INVITED, BLOCKED).forEach { status ->
                    shouldThrow<ForbiddenUserChange> { user(status).block(otherActiveAdmins = 1) }
                }
            }

            test("only a blocked user is unblocked") {
                listOf(INVITED, ACTIVE).forEach { status ->
                    shouldThrow<ForbiddenUserChange> { user(status).unblock() }
                }
            }
        }

        context("roles") {
            test("a user becomes an admin and back") {
                val user = user()

                user.changeRole(TENANT_ADMIN, otherActiveAdmins = 0)
                user.role shouldBe TENANT_ADMIN

                user.changeRole(USER, otherActiveAdmins = 1)
                user.role shouldBe USER
            }
        }

        context("the last admin") {
            test("cannot be blocked") {
                shouldThrow<LastAdminRequired> { user(ACTIVE, TENANT_ADMIN).block(otherActiveAdmins = 0) }
            }

            test("cannot be demoted") {
                shouldThrow<LastAdminRequired> { user(ACTIVE, TENANT_ADMIN).changeRole(USER, otherActiveAdmins = 0) }
            }

            test("cannot be deleted") {
                shouldThrow<LastAdminRequired> { user(ACTIVE, TENANT_ADMIN).ensureErasable(otherActiveAdmins = 0) }
            }

            test("an invited admin is the last one too, because nobody else can act") {
                shouldThrow<LastAdminRequired> { user(INVITED, TENANT_ADMIN).changeRole(USER, otherActiveAdmins = 0) }
                shouldThrow<LastAdminRequired> { user(INVITED, TENANT_ADMIN).ensureErasable(otherActiveAdmins = 0) }
            }

            test("another active admin lifts the rule") {
                user(ACTIVE, TENANT_ADMIN).apply { block(otherActiveAdmins = 1) }.status shouldBe BLOCKED
                user(ACTIVE, TENANT_ADMIN).apply { changeRole(USER, otherActiveAdmins = 1) }.role shouldBe USER
                shouldNotThrowAny { user(ACTIVE, TENANT_ADMIN).ensureErasable(otherActiveAdmins = 1) }
            }

            test("a blocked admin cannot act, so losing one is not losing the last") {
                user(BLOCKED, TENANT_ADMIN).apply { changeRole(USER, otherActiveAdmins = 0) }.role shouldBe USER
                shouldNotThrowAny { user(BLOCKED, TENANT_ADMIN).ensureErasable(otherActiveAdmins = 0) }
            }

            test("the rule does not touch a plain user") {
                user(ACTIVE, USER).apply { block(otherActiveAdmins = 0) }.status shouldBe BLOCKED
                shouldNotThrowAny { user(ACTIVE, USER).ensureErasable(otherActiveAdmins = 0) }
            }

            test("keeping the role is not a demotion") {
                shouldNotThrowAny { user(ACTIVE, TENANT_ADMIN).changeRole(TENANT_ADMIN, otherActiveAdmins = 0) }
            }
        }
    })
