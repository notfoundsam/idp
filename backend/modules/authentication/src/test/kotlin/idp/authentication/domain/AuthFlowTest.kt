package idp.authentication.domain

import idp.authentication.domain.AuthFactor.PASSKEY
import idp.authentication.domain.AuthFactor.PASSWORD
import idp.authentication.domain.AuthFactor.RECOVERY_CODE
import idp.authentication.domain.AuthFactor.TOTP
import idp.sharedkernel.security.UserRole
import idp.sharedkernel.security.UserRole.TENANT_ADMIN
import idp.sharedkernel.security.UserRole.USER
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant
import java.util.UUID

private const val RETURN_URL = "/saml/resume/7f3c"
private val MOMENT: Instant = Instant.parse("2026-11-01T12:00:00Z")
private val TTL: Duration = Duration.ofMinutes(15)
private val RELAXED = TenantPolicy(mfaForUsers = false)
private val PASSKEY_ONLY = TenantPolicy(passkeyRequired = true)
private val SECOND_FACTORS = setOf(TOTP, PASSKEY, RECOVERY_CODE)
private val LOGIN = "sealed login".toByteArray()
private val SESSION: UUID = UUID.randomUUID()

private fun user(
    vararg enrolled: AuthFactor,
    role: UserRole = USER,
) = FlowUser(UUID.randomUUID(), role, enrolled.toSet())

private fun flow(
    policy: TenantPolicy = TenantPolicy(),
    requirements: SpRequirements = SpRequirements(),
    prior: PriorAuthentication? = null,
) = AuthFlow.start(UUID.randomUUID(), policy, requirements, RETURN_URL, prior, MOMENT, TTL)

private fun AuthFlow.step() = step(MOMENT)

private fun AuthFlow.identify(
    user: FlowUser?,
    now: Instant,
) = identify(user, LOGIN, now)

private fun AuthFlow.signIn(user: FlowUser) =
    apply {
        identify(user, MOMENT)
        passwordPassed(MOMENT)
    }

class AuthFlowTest :
    FunSpec({
        context("the default policy") {
            test("requires MFA of a user") {
                flow().signIn(user(TOTP)).step() shouldBe FlowStep.SecondFactor(setOf(TOTP, RECOVERY_CODE))
            }

            test("requires MFA of an admin") {
                flow().signIn(user(TOTP, role = TENANT_ADMIN)).step() shouldBe FlowStep.SecondFactor(setOf(TOTP, RECOVERY_CODE))
            }

            test("counts a passkey with user verification as MFA") {
                val flow = flow()

                flow.passkeyPassed(user(PASSKEY), MOMENT)

                flow.step() shouldBe FlowStep.Complete
            }
        }

        context("a relaxed policy") {
            test("lets a user without a second factor in with the password") {
                flow(RELAXED).signIn(user()).step() shouldBe FlowStep.Complete
            }

            test("still requires MFA of an admin") {
                flow(RELAXED).signIn(user(role = TENANT_ADMIN)).step() shouldBe FlowStep.EnrollMfa(setOf(TOTP, PASSKEY))
            }

            test("still asks for a second factor the user enrolled") {
                flow(RELAXED).signIn(user(TOTP)).step() shouldBe FlowStep.SecondFactor(setOf(TOTP, RECOVERY_CODE))
            }
        }

        context("required MFA without a second factor") {
            test("leads to EnrollMfa") {
                flow().signIn(user()).step() shouldBe FlowStep.EnrollMfa(setOf(TOTP, PASSKEY))
            }

            test("is met by an enrolled TOTP") {
                val flow = flow().signIn(user())

                flow.enrolled(TOTP, MOMENT)

                flow.step() shouldBe FlowStep.Complete
                flow.proved shouldBe setOf(PASSWORD, TOTP)
            }

            test("is met by an enrolled passkey") {
                val flow = flow().signIn(user())

                flow.enrolled(PASSKEY, MOMENT)

                flow.step() shouldBe FlowStep.Complete
                flow.proved shouldBe setOf(PASSWORD, PASSKEY)
            }

            test("cannot be met by enrolling a recovery code") {
                val flow = flow().signIn(user())

                shouldThrow<StepNotAllowed> { flow.enrolled(RECOVERY_CODE, MOMENT) }
            }
        }

        context("the second factor") {
            test("offers everything the user enrolled and a recovery code") {
                flow().signIn(user(TOTP, PASSKEY)).step() shouldBe FlowStep.SecondFactor(SECOND_FACTORS)
            }

            test("completes with TOTP") {
                val flow = flow().signIn(user(TOTP))

                flow.totpPassed(MOMENT)

                flow.step() shouldBe FlowStep.Complete
                flow.proved shouldBe setOf(PASSWORD, TOTP)
            }

            test("completes with a recovery code") {
                val flow = flow().signIn(user(TOTP))

                flow.recoveryCodeUsed(MOMENT)

                flow.step() shouldBe FlowStep.Complete
                flow.proved shouldBe setOf(PASSWORD, RECOVERY_CODE)
            }

            test("completes with a passkey") {
                val owner = user(TOTP, PASSKEY)
                val flow = flow().signIn(owner)

                flow.passkeyPassed(owner, MOMENT)

                flow.step() shouldBe FlowStep.Complete
            }

            test("refuses TOTP the user did not enroll") {
                val flow = flow().signIn(user(PASSKEY))

                shouldThrow<StepNotAllowed> { flow.totpPassed(MOMENT) }
            }

            test("refuses a passkey the user did not enroll") {
                val owner = user(TOTP)
                val flow = flow().signIn(owner)

                shouldThrow<StepNotAllowed> { flow.passkeyPassed(owner, MOMENT) }
            }
        }

        context("a passkey") {
            test("needs no password on the first step") {
                val owner = user(PASSKEY, role = TENANT_ADMIN)
                val flow = flow()

                flow.passkeyPassed(owner, MOMENT)

                flow.step() shouldBe FlowStep.Complete
                flow.userId shouldBe owner.id
                flow.proved shouldBe setOf(PASSKEY)
            }

            test("still works after the login was entered, the password may not exist") {
                val owner = user(PASSKEY)
                val flow = flow()
                flow.identify(owner, MOMENT)

                flow.passkeyPassed(owner, MOMENT)

                flow.step() shouldBe FlowStep.Complete
            }

            test("must belong to the user who entered the login") {
                val flow = flow()
                flow.identify(user(PASSKEY), MOMENT)

                shouldThrow<PasskeyNotAccepted> { flow.passkeyPassed(user(PASSKEY), MOMENT) }

                flow.step() shouldBe FlowStep.Password
                flow.proved shouldBe emptySet()
            }

            test("must belong to the user who passed the password") {
                val flow = flow().signIn(user(PASSKEY))

                shouldThrow<PasskeyNotAccepted> { flow.passkeyPassed(user(PASSKEY), MOMENT) }

                flow.proved shouldBe setOf(PASSWORD)
            }

            test("must belong to the user of the session") {
                val session = PriorAuthentication(SESSION, user(PASSKEY), setOf(PASSWORD))
                val flow = flow(requirements = SpRequirements(Assurance.PASSKEY), prior = session)

                shouldThrow<PasskeyNotAccepted> { flow.passkeyPassed(user(PASSKEY), MOMENT) }
            }
        }

        context("a required passkey") {
            test("comes from the policy for every role") {
                flow(PASSKEY_ONLY).signIn(user(PASSKEY)).step() shouldBe FlowStep.SecondFactor(setOf(PASSKEY))
                flow(PASSKEY_ONLY).signIn(user(PASSKEY, role = TENANT_ADMIN)).step() shouldBe FlowStep.SecondFactor(setOf(PASSKEY))
            }

            test("is not replaced by TOTP or a recovery code") {
                val flow = flow(PASSKEY_ONLY).signIn(user(TOTP, PASSKEY))

                flow.step() shouldBe FlowStep.SecondFactor(setOf(PASSKEY))
                shouldThrow<StepNotAllowed> { flow.totpPassed(MOMENT) }
                shouldThrow<StepNotAllowed> { flow.recoveryCodeUsed(MOMENT) }
            }

            test("is enrolled right after the password by a user without a second factor") {
                val flow = flow(PASSKEY_ONLY).signIn(user())

                flow.step() shouldBe FlowStep.EnrollMfa(setOf(PASSKEY))
                shouldThrow<StepNotAllowed> { flow.enrolled(TOTP, MOMENT) }
            }

            test("is enrolled only after the second factor the user already has") {
                val flow = flow(PASSKEY_ONLY).signIn(user(TOTP))

                flow.step() shouldBe FlowStep.SecondFactor(setOf(TOTP, RECOVERY_CODE))
                shouldThrow<StepNotAllowed> { flow.enrolled(PASSKEY, MOMENT) }

                flow.totpPassed(MOMENT)

                flow.step() shouldBe FlowStep.EnrollMfa(setOf(PASSKEY))
                flow.enrolled(PASSKEY, MOMENT)
                flow.step() shouldBe FlowStep.Complete
            }
        }

        context("the requirements of the SP") {
            test("add MFA to a relaxed policy") {
                flow(RELAXED, SpRequirements(Assurance.MULTI_FACTOR)).signIn(user()).step() shouldBe
                    FlowStep.EnrollMfa(setOf(TOTP, PASSKEY))
            }

            test("add a passkey to the default policy") {
                flow(requirements = SpRequirements(Assurance.PASSKEY)).signIn(user(PASSKEY)).step() shouldBe
                    FlowStep.SecondFactor(setOf(PASSKEY))
            }

            test("do not lower the policy") {
                flow(requirements = SpRequirements(Assurance.SINGLE_FACTOR)).signIn(user()).step() shouldBe
                    FlowStep.EnrollMfa(setOf(TOTP, PASSKEY))
            }

            test("travel with the opaque return URL") {
                flow().returnUrl shouldBe RETURN_URL
            }
        }

        context("a session") {
            test("that is enough completes the flow at once") {
                val session = PriorAuthentication(SESSION, user(TOTP), setOf(PASSWORD, TOTP))

                flow(prior = session).step() shouldBe FlowStep.Complete
            }

            test("with the password asks only for the second factor") {
                val session = PriorAuthentication(SESSION, user(TOTP), setOf(PASSWORD))

                flow(requirements = SpRequirements(Assurance.MULTI_FACTOR), prior = session).step() shouldBe
                    FlowStep.SecondFactor(setOf(TOTP, RECOVERY_CODE))
            }

            test("with two factors asks only for the passkey") {
                val session = PriorAuthentication(SESSION, user(TOTP, PASSKEY), setOf(PASSWORD, TOTP))

                flow(requirements = SpRequirements(Assurance.PASSKEY), prior = session).step() shouldBe
                    FlowStep.SecondFactor(setOf(PASSKEY))
            }

            test("that became too weak for a stricter policy asks for the missing step") {
                val session = PriorAuthentication(SESSION, user(), setOf(PASSWORD))

                flow(prior = session).step() shouldBe FlowStep.EnrollMfa(setOf(TOTP, PASSKEY))
            }

            test("keeps its factors when the missing one is passed") {
                val session = PriorAuthentication(SESSION, user(TOTP), setOf(PASSWORD))
                val flow = flow(prior = session)

                flow.totpPassed(MOMENT)

                flow.proved shouldBe setOf(PASSWORD, TOTP)
                flow.userId shouldBe session.user.id
            }

            test("counts for nothing when the SP asks for a fresh login") {
                val session = PriorAuthentication(SESSION, user(TOTP), setOf(PASSWORD, TOTP))
                val flow = flow(requirements = SpRequirements(freshLogin = true), prior = session)

                flow.step() shouldBe FlowStep.Identify
                flow.userId.shouldBeNull()
                flow.proved shouldBe emptySet()
            }

            test("is continued by a flow that takes over its factors") {
                val session = PriorAuthentication(SESSION, user(TOTP), setOf(PASSWORD))
                val flow = flow(prior = session)

                flow.sessionId shouldBe SESSION
                flow.continuesSession shouldBe true
            }

            test("is only replaced by a fresh login") {
                val session = PriorAuthentication(SESSION, user(TOTP), setOf(PASSWORD, TOTP))
                val flow = flow(requirements = SpRequirements(freshLogin = true), prior = session)

                flow.sessionId shouldBe SESSION
                flow.continuesSession shouldBe false
            }

            test("is not there for a flow without one") {
                flow().continuesSession shouldBe false
            }
        }

        context("an unknown user") {
            fun identified(user: FlowUser?) = flow().apply { identify(user, MOMENT) }

            test("starts with the same step as anyone") {
                flow().step() shouldBe FlowStep.Identify
            }

            test("gets the password step like a user with a password") {
                identified(null).step() shouldBe identified(user(TOTP)).step()
            }

            test("gets the password step like a user who has only a passkey") {
                identified(null).step() shouldBe identified(user(PASSKEY)).step()
            }

            test("gets the password step like a user without any factor") {
                identified(null).step() shouldBe identified(user()).step()
            }

            test("gets the same refusal for a foreign passkey as a user who exists") {
                val stranger = user(PASSKEY)

                val unknown = shouldThrow<PasskeyNotAccepted> { identified(null).passkeyPassed(stranger, MOMENT) }
                val known = shouldThrow<PasskeyNotAccepted> { identified(user(PASSKEY)).passkeyPassed(stranger, MOMENT) }

                unknown.message shouldBe known.message
            }

            test("stays on the password step after that refusal") {
                val flow = identified(null)

                shouldThrow<PasskeyNotAccepted> { flow.passkeyPassed(user(PASSKEY), MOMENT) }

                flow.step() shouldBe FlowStep.Password
                flow.userId.shouldBeNull()
            }

            test("cannot pass the password") {
                val flow = identified(null)

                shouldThrow<StepNotAllowed> { flow.passwordPassed(MOMENT) }

                flow.step() shouldBe FlowStep.Password
            }
        }

        context("the order of steps") {
            test("refuses the password before the login") {
                shouldThrow<StepNotAllowed> { flow().passwordPassed(MOMENT) }
            }

            test("refuses a second login") {
                val flow = flow().signIn(user(TOTP))

                shouldThrow<StepNotAllowed> { flow.identify(user(TOTP), MOMENT) }
            }

            test("refuses a repeated password") {
                val flow = flow().signIn(user(TOTP))

                shouldThrow<StepNotAllowed> { flow.passwordPassed(MOMENT) }
            }

            test("refuses TOTP before the password") {
                val flow = flow()
                flow.identify(user(TOTP), MOMENT)

                shouldThrow<StepNotAllowed> { flow.totpPassed(MOMENT) }
            }

            test("refuses a recovery code before the password") {
                val flow = flow()
                flow.identify(user(TOTP), MOMENT)

                shouldThrow<StepNotAllowed> { flow.recoveryCodeUsed(MOMENT) }
            }

            test("refuses to enroll before the password") {
                val flow = flow()
                flow.identify(user(), MOMENT)

                shouldThrow<StepNotAllowed> { flow.enrolled(TOTP, MOMENT) }
            }

            test("refuses to enroll when a second factor is due") {
                val flow = flow().signIn(user(TOTP))

                shouldThrow<StepNotAllowed> { flow.enrolled(TOTP, MOMENT) }
            }

            test("refuses every step of a finished flow") {
                val owner = user(TOTP)
                val flow = flow().signIn(owner)
                flow.totpPassed(MOMENT)

                shouldThrow<StepNotAllowed> { flow.totpPassed(MOMENT) }
                shouldThrow<StepNotAllowed> { flow.recoveryCodeUsed(MOMENT) }
                shouldThrow<StepNotAllowed> { flow.enrolled(PASSKEY, MOMENT) }
                shouldThrow<StepNotAllowed> { flow.passkeyPassed(owner, MOMENT) }
            }
        }

        context("the lifetime of a flow") {
            val expiry = MOMENT.plus(TTL)

            test("includes the last moment") {
                flow().step(expiry.minusMillis(1)) shouldBe FlowStep.Identify
            }

            test("ends at the expiry") {
                shouldThrow<FlowExpired> { flow().step(expiry) }
            }

            test("stops every step") {
                val owner = user(TOTP)
                val flow = flow().signIn(owner)

                shouldThrow<FlowExpired> { flow.totpPassed(expiry) }
                shouldThrow<FlowExpired> { flow.recoveryCodeUsed(expiry) }
                shouldThrow<FlowExpired> { flow.passkeyPassed(owner, expiry) }
                shouldThrow<FlowExpired> { flow.enrolled(TOTP, expiry) }
                flow.proved shouldBe setOf(PASSWORD)
            }

            test("stops the first steps") {
                shouldThrow<FlowExpired> { flow().identify(user(), expiry) }
                shouldThrow<FlowExpired> { flow().passkeyPassed(user(PASSKEY), expiry) }
            }

            test("stops the password") {
                val flow = flow()
                flow.identify(user(), MOMENT)

                shouldThrow<FlowExpired> { flow.passwordPassed(expiry) }
            }

            test("is not extended by a step") {
                val flow = flow()
                flow.identify(user(), expiry.minusMillis(1))

                flow.expiresAt shouldBe expiry
            }
        }
    })
