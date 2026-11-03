package idp.federation.application

import idp.federation.ADMIN
import idp.federation.Federation
import idp.federation.IP
import idp.federation.spMetadata
import idp.identity.application.ProfileChange
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.audit.AuditTarget
import idp.sharedkernel.tenant.TenantContext
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import java.util.UUID

private const val EMAIL = "ada@acme.test"
private const val NAME = "Ada Lovelace"
private const val PHONE = "+81 90 1234 5678"

private fun Federation.activeUser(
    tenant: TenantContext,
    email: String = EMAIL,
): UUID =
    TenantContext.with(tenant) {
        val id = identity.invite(email, idp.sharedkernel.security.UserRole.USER, ADMIN)
        identity.acceptInvitation(checkNotNull(identity.issueInvitation(id, ADMIN)), Actor.User(id))
        identity.updateProfile(id, ProfileChange(NAME, PHONE), ADMIN)
        id
    }

private fun Federation.assigned(
    tenant: TenantContext,
    user: UUID,
    metadata: String = spMetadata(),
    settings: SpSettings = SpSettings(),
): UUID {
    val sp = with(this) { tenant.register(metadata) }
    tenant.acting {
        if (settings != SpSettings()) changeSettings(sp, settings, ADMIN)
        assign(sp, listOf(user), ADMIN)
    }
    return sp
}

class SignInTest :
    FunSpec({
        context("the assignment") {
            test("is required: without it nothing is said about the user and the refusal is audited") {
                Federation().run {
                    val tenant = tenant()
                    val user = activeUser(tenant)
                    val sp = tenant.register()

                    tenant.acting { signIn(sp, user, IP) }.shouldBeNull()

                    val denial = audit.denials.single()
                    denial.action shouldBe "sp.login_denied"
                    denial.actor shouldBe Actor.User(user)
                    denial.target shouldBe AuditTarget.user(user)
                    denial.ip shouldBe IP
                    denial.details shouldBe mapOf("sp_id" to sp.toString())
                    audit.of("sp.login").shouldBeEmpty()
                    tenant.count("subject_identifiers") shouldBe 0
                }
            }

            test("withdrawn counts at the next login") {
                Federation().run {
                    val tenant = tenant()
                    val user = activeUser(tenant)
                    val sp = assigned(tenant, user)
                    tenant.acting { signIn(sp, user) }.shouldNotBeNull()

                    tenant.acting { unassign(sp, listOf(user), ADMIN) }

                    tenant.acting { signIn(sp, user) }.shouldBeNull()
                }
            }

            test("lets the login through and the journal records it") {
                Federation().run {
                    val tenant = tenant()
                    val user = activeUser(tenant)
                    val sp = assigned(tenant, user)

                    tenant.acting { signIn(sp, user, IP) }.shouldNotBeNull()

                    val login = audit.of("sp.login").single()
                    login.actor shouldBe Actor.User(user)
                    login.target shouldBe AuditTarget.user(user)
                    login.ip shouldBe IP
                    login.details shouldBe mapOf("sp_id" to sp.toString())
                    audit.denials.shouldBeEmpty()
                }
            }
        }

        context("the persistent NameID") {
            test("is the default, stays the same at every login and is not derived from the user") {
                Federation().run {
                    val tenant = tenant()
                    val user = activeUser(tenant)
                    val sp = assigned(tenant, user)

                    val first = tenant.acting { signIn(sp, user) }.shouldNotBeNull()
                    val second = tenant.acting { signIn(sp, user) }.shouldNotBeNull()

                    first.nameIdFormat shouldBe NameIdFormat.PERSISTENT
                    second.nameId shouldBe first.nameId
                    first.nameId shouldNotContain user.toString()
                    first.nameId shouldNotContain "ada"
                    (first.nameId.length >= 40) shouldBe true
                }
            }

            test("is a different one at each SP, so two SPs cannot join what they know") {
                Federation().run {
                    val tenant = tenant()
                    val user = activeUser(tenant)
                    val wiki = assigned(tenant, user)
                    val crm = assigned(tenant, user, spMetadata(entityId = "https://crm.example/saml"))

                    tenant.acting { signIn(wiki, user) }!!.nameId shouldNotBe tenant.acting { signIn(crm, user) }!!.nameId
                }
            }

            test("survives a change of email") {
                Federation().run {
                    val tenant = tenant()
                    val user = activeUser(tenant)
                    val sp = assigned(tenant, user)
                    val before = tenant.acting { signIn(sp, user) }!!.nameId

                    TenantContext.with(tenant) {
                        identity.requestEmailChange(user, "ada.byron@acme.test", Actor.User(user))
                        identity.confirmEmailChange(checkNotNull(identity.issueEmailChange(user, Actor.System)))
                    }

                    tenant.acting { signIn(sp, user) }!!.nameId shouldBe before
                }
            }

            test("is a new one for a new user who takes an email that was freed") {
                Federation().run {
                    val tenant = tenant()
                    val gone = activeUser(tenant)
                    val sp = assigned(tenant, gone)
                    val old = tenant.acting { signIn(sp, gone) }!!.nameId
                    TenantContext.with(tenant) { identity.eraseUser(gone, ADMIN) }

                    val newcomer = activeUser(tenant)
                    tenant.acting { assign(sp, listOf(newcomer), ADMIN) }

                    tenant.acting { signIn(sp, newcomer) }!!.nameId shouldNotBe old
                    tenant.count("subject_identifiers") shouldBe 1
                }
            }
        }

        context("the email as NameID") {
            test("is given only where an admin set it") {
                Federation().run {
                    val tenant = tenant()
                    val user = activeUser(tenant)
                    val sp = assigned(tenant, user, settings = SpSettings(nameIdFormat = NameIdFormat.EMAIL))

                    val subject = tenant.acting { signIn(sp, user) }.shouldNotBeNull()

                    subject.nameIdFormat shouldBe NameIdFormat.EMAIL
                    subject.nameId shouldBe EMAIL
                    subject.attributes shouldBe emptyMap()
                    tenant.count("subject_identifiers") shouldBe 0
                }
            }
        }

        context("the attributes") {
            test("are none by default") {
                Federation().run {
                    val tenant = tenant()
                    val user = activeUser(tenant)
                    val sp = assigned(tenant, user)

                    tenant.acting { signIn(sp, user) }!!.attributes shouldBe emptyMap()
                }
            }

            test("are those an admin released, each from its own field") {
                Federation().run {
                    val tenant = tenant()
                    val user = activeUser(tenant)
                    val settings = SpSettings(attributes = setOf(SamlAttribute.EMAIL, SamlAttribute.DISPLAY_NAME))
                    val sp = assigned(tenant, user, settings = settings)

                    tenant.acting { signIn(sp, user) }!!.attributes shouldBe
                        mapOf(SamlAttribute.EMAIL to EMAIL, SamlAttribute.DISPLAY_NAME to NAME)
                    tenant.acting { serviceProvider(sp) }!!.settings shouldBe settings
                    audit.of("sp.settings_changed").single().details["attributes"] shouldBe "DISPLAY_NAME,EMAIL"
                }
            }

            test("open only the released fields: a broken phone does not matter to an SP that does not get it") {
                Federation().run {
                    val tenant = tenant()
                    val user = activeUser(tenant)
                    val sp = assigned(tenant, user, settings = SpSettings(attributes = setOf(SamlAttribute.DISPLAY_NAME)))
                    sql.update("UPDATE ${tenant.table("user_profiles")} SET phone_ciphertext = '\\x00'::bytea WHERE user_id = ?", user)

                    tenant.acting { signIn(sp, user) }!!.attributes shouldBe mapOf(SamlAttribute.DISPLAY_NAME to NAME)
                }
            }

            test("leave out a field the user has not filled in") {
                Federation().run {
                    val tenant = tenant()
                    val user = activeUser(tenant)
                    sql.update("UPDATE ${tenant.table("user_profiles")} SET phone_ciphertext = NULL WHERE user_id = ?", user)
                    val sp = assigned(tenant, user, settings = SpSettings(attributes = SamlAttribute.entries.toSet()))

                    tenant.acting { signIn(sp, user) }!!.attributes.keys shouldBe setOf(SamlAttribute.EMAIL, SamlAttribute.DISPLAY_NAME)
                }
            }

            test("stay out of toString") {
                Federation().run {
                    val tenant = tenant()
                    val user = activeUser(tenant)
                    val sp = assigned(tenant, user, settings = SpSettings(attributes = setOf(SamlAttribute.EMAIL)))

                    val printed = tenant.acting { signIn(sp, user) }.toString()

                    printed shouldNotContain EMAIL
                    tenant.acting { signIn(sp, user) }!!.nameId.let { printed shouldNotContain it }
                }
            }
        }

        context("the subject identifiers") {
            test("go with the user and with the SP") {
                Federation().run {
                    val tenant = tenant()
                    val user = activeUser(tenant)
                    val other = activeUser(tenant, "grace@acme.test")
                    val sp = assigned(tenant, user)
                    tenant.acting { assign(sp, listOf(other), ADMIN) }
                    tenant.acting { signIn(sp, user) }
                    tenant.acting { signIn(sp, other) }
                    sql.queryForList("SELECT value FROM ${tenant.table("subject_identifiers")}") shouldHaveSize 2

                    TenantContext.with(tenant) { identity.eraseUser(user, ADMIN) }
                    tenant.count("subject_identifiers") shouldBe 1
                    tenant.acting { removeServiceProvider(sp, ADMIN) }
                    tenant.count("subject_identifiers") shouldBe 0
                }
            }
        }
    })
