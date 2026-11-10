package idp.app.security

import idp.app.account.passwordChangeBody
import idp.app.admin.inviteBody
import idp.app.admin.signedIn
import idp.app.login.ACME
import idp.app.login.Browser
import idp.app.login.GLOBEX
import idp.app.login.LoginEnvironment
import idp.app.login.PASSWORD
import idp.app.login.PasskeyDevice
import idp.app.login.USER_AGENT
import idp.app.login.WRONG_PASSWORD
import idp.app.login.codeBody
import idp.app.login.decodeBase32
import idp.app.login.emailBody
import idp.app.login.invitationBody
import idp.app.login.loginBody
import idp.app.login.passwordBody
import idp.app.login.strings
import idp.app.login.text
import idp.app.login.tokenBody
import idp.app.login.totpCode
import idp.federation.SP_ENTITY_ID
import idp.federation.TestSp
import idp.federation.application.FederationService
import idp.federation.authnRequest
import idp.identity.application.IdentityService
import idp.identity.application.ProfileChange
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.security.UserRole
import idp.sharedkernel.testing.Finding
import idp.sharedkernel.testing.Plaintext
import idp.sharedkernel.testing.TestValkey
import idp.sharedkernel.testing.scan
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.util.UUID

private const val VIEWER_HEADER = "CloudFront-Viewer-Address"
private const val VIEWER = "203.0.113.77"
private const val INVITED = "grace.hopper@globex.test"
private const val CHANGED = "grace.brewster@globex.test"
private const val PENDING = "amazing.grace@globex.test"
private const val NOBODY = "katherine.johnson@globex.test"
private const val MEMBER = "hedy.lamarr@acme.test"
private const val ADMIN = "barbara.liskov@acme.test"
private const val NEWCOMER = "frances.allen@acme.test"
private const val NAME = "Grace Brewster Murray"
private const val PHONE = "+44 20 7946 0958"
private const val CHOSEN_PASSWORD = "copper-lantern-drifts-softly"
private const val CHANGED_PASSWORD = "velvet-meadow-hums-gently"
private const val RESET_PASSWORD = "amber-quarry-sings-lightly"
private const val SHORTEST_COOKIE = 8
private val BOUNCE = Regex("""url=(/saml/sso/[0-9a-f-]{36})""")
private val FLOW = Regex("""^/login/\?flow=([0-9a-f-]{36})$""")
private val SAML_RESPONSE = Regex("""name="SAMLResponse" value="([^"]+)"""")

/** Every table and Valkey after a journey through every kind of data the IdP keeps, searched for what the journey gave in clear. */
class PlaintextAtRestTest :
    FunSpec({
        val environment = LoginEnvironment()
        val valkey = TestValkey.start()
        val application =
            environment.start(
                "--VALKEY_URL=${valkey.url}",
                "--THROTTLE_IP_HMAC_KEY=${UUID.randomUUID()}",
                "--CLIENT_IP_HEADER=$VIEWER_HEADER",
                throttled = true,
            )
        val identity = application.getBean(IdentityService::class.java)
        val federation = application.getBean(FederationService::class.java)
        val secrets =
            mutableListOf(
                Plaintext.text("viewer address", VIEWER),
                Plaintext.text("user agent", USER_AGENT),
                Plaintext.text("wrong password", WRONG_PASSWORD),
                Plaintext.text("password of the fixtures", PASSWORD),
                Plaintext.text("name", NAME),
                Plaintext.text("phone", PHONE),
            )
        val browsers = mutableListOf<Browser>()

        afterSpec {
            application.close()
            valkey.close()
        }

        fun keep(
            label: String,
            value: String,
        ): String = value.also { secrets += Plaintext.text(label, it) }

        fun browser(host: String) = Browser(application, host, listOf(VIEWER_HEADER, "$VIEWER:443")).also { browsers += it }

        fun keepCodes(
            label: String,
            codes: List<String>,
        ) = codes.forEachIndexed { index, code ->
            keep("$label $index", code)
            keep("$label $index without dashes", code.replace("-", ""))
        }

        fun keepTotp(
            label: String,
            secret: String,
        ) {
            keep(label, secret)
            secrets += Plaintext.bytes("$label as bytes", decodeBase32(secret))
        }

        /** An invitation accepted at `globex`, TOTP and passkey enrolled, then the account, an email change and a password reset. */
        fun userJourney() {
            listOf(INVITED, CHANGED, PENDING, NOBODY).forEach { keep(it, it) }
            listOf(CHOSEN_PASSWORD, CHANGED_PASSWORD, RESET_PASSWORD).forEach { keep(it, it) }
            val user = environment.globex.acting { identity.invite(INVITED, UserRole.USER, Actor.System) }
            val invitation = keep("invitation token", environment.globex.acting { identity.issueInvitation(user, Actor.System) }!!)
            val browser = browser(GLOBEX)
            val flow = browser.startFlow()
            browser.post("/api/flows/$flow/invitation", invitationBody(invitation, CHOSEN_PASSWORD)).status shouldBe 200
            val secret = browser.post("/api/flows/$flow/enrollment/totp").json().text("secret")
            keepTotp("TOTP secret", secret)
            val enrolled = browser.post("/api/flows/$flow/enrollment/totp/confirmation", codeBody(totpCode(secret)))
            enrolled.status shouldBe 200
            keepCodes("first recovery code", enrolled.json().strings("recoveryCodes"))
            browser.complete(flow).status shouldBe 200

            val options = browser.post("/api/account/passkeys/options").json()
            browser.post("/api/account/passkeys", PasskeyDevice(GLOBEX).attestation(options)).status shouldBe 200
            val renewed = browser.post("/api/account/recovery-codes")
            renewed.status shouldBe 200
            keepCodes("recovery code", renewed.json().strings("recoveryCodes"))
            browser.post("/api/account/password", passwordChangeBody(CHOSEN_PASSWORD, CHANGED_PASSWORD)).status shouldBe 204
            environment.globex.acting { identity.updateProfile(user, ProfileChange(NAME, PHONE), Actor.User(user)) }

            browser(GLOBEX).post("/api/password-reset", loginBody(INVITED)).status shouldBe 202
            val reset = keep("reset token", environment.globex.acting { identity.issuePasswordReset(user, Actor.System) }!!)
            browser(GLOBEX)
                .post("/api/password-reset/confirmation", """{"token":"$reset","password":"$RESET_PASSWORD"}""")
                .status shouldBe 204

            val again = browser(GLOBEX)
            val next = again.identified(INVITED)
            again.password(next, RESET_PASSWORD).json().text("step") shouldBe "SECOND_FACTOR"
            val code = renewed.json().strings("recoveryCodes").first()
            again.post("/api/flows/$next/recovery-code", codeBody(code)).json().text("step") shouldBe "COMPLETE"
            again.complete(next).status shouldBe 200

            again.post("/api/email-change", emailBody(CHANGED)).status shouldBe 202
            val change = keep("email change token", environment.globex.acting { identity.issueEmailChange(user, Actor.System) }!!)
            browser(GLOBEX).post("/api/email-change/confirmation", tokenBody(change)).status shouldBe 204
            again.post("/api/email-change", emailBody(PENDING)).status shouldBe 202
            keep("pending email change token", environment.globex.acting { identity.issueEmailChange(user, Actor.System) }!!)
            keep("pending reset token", environment.globex.acting { identity.issuePasswordReset(user, Actor.System) }!!)

            val refused = browser(GLOBEX)
            refused.password(refused.identified(CHANGED), WRONG_PASSWORD).status shouldBe 422
            refused.identified(NOBODY)
        }

        /** A SAML login at `acme` with a RelayState, one AuthnRequest left waiting, and an admin who invites and blocks. */
        fun samlAndAdminJourney() {
            listOf(MEMBER, ADMIN, NEWCOMER).forEach { keep(it, it) }
            val sp = TestSp()
            val member = with(environment) { application.user(MEMBER) }
            environment.acme.acting {
                federation.createIdentity(ACME)
                val spId = federation.registerServiceProvider("Wiki", sp.metadata().toByteArray(), Actor.System)
                federation.assign(spId, listOf(member), Actor.System)
            }
            val browser = browser(ACME)
            val requestId = keep("AuthnRequest ID", "_${UUID.randomUUID()}")
            val relayState = keep("RelayState", "return-to-${UUID.randomUUID()}")
            val bounce = BOUNCE.find(browser.get("/saml/sso?${sp.redirectQuery(authnRequest(id = requestId), relayState)}").body)
            val flow = FLOW.find(browser.get(bounce.shouldNotBeNull().groupValues[1]).header("Location")!!)!!.groupValues[1]
            browser.post("/api/flows/$flow/login", loginBody(MEMBER))
            browser.post("/api/flows/$flow/password", passwordBody(PASSWORD)).status shouldBe 200
            val posted = browser.get(browser.complete(flow).json().text("returnUrl"))
            SAML_RESPONSE.find(posted.body).shouldNotBeNull()
            val waiting = keep("waiting AuthnRequest ID", "_${UUID.randomUUID()}")
            val waitingState = keep("waiting RelayState", "return-to-${UUID.randomUUID()}")
            BOUNCE.find(browser(ACME).get("/saml/sso?${sp.redirectQuery(authnRequest(id = waiting), waitingState)}").body).shouldNotBeNull()

            val admin = environment.signedIn(application, ADMIN)
            browsers += admin.browser
            keepTotp("TOTP secret of the admin", admin.secret!!)
            val invited = admin.browser.post("/api/admin/users", inviteBody(NEWCOMER, UserRole.USER))
            invited.status shouldBe 201
            val newcomer = UUID.fromString(invited.json().text("id"))
            keep("waiting invitation token", environment.acme.acting { identity.issueInvitation(newcomer, Actor.System) }!!)
            admin.browser.post("/api/admin/users/$member/block").status shouldBe 204
        }

        val stored by lazy {
            userJourney()
            samlAndAdminJourney()
            browsers.flatMap { it.cookies.entries }.filter { (_, value) -> value.length >= SHORTEST_COOKIE }.forEach { (name, value) ->
                keep("cookie $name", value)
            }
            environment.database.scan(secrets)
        }

        test("no column of any table holds a personal value, a password, a secret or a token as the journey gave it") {
            stored.findings.shouldBeEmpty()
        }

        test("the scan read the rows that hold them, in the tenant schemas and the platform") {
            val tables =
                listOf(
                    "users",
                    "user_profiles",
                    "user_keys",
                    "password_credentials",
                    "totp_credentials",
                    "recovery_codes",
                    "passkey_credentials",
                    "email_changes",
                    "email_change_notices",
                    "password_resets",
                    "sso_sessions",
                    "flows",
                    "audit_entries",
                ).map { "${environment.globex.schema}.$it" } +
                    listOf("invitations", "saml_requests", "saml_request_ids", "audit_entries").map { "${environment.acme.schema}.$it" } +
                    "platform.outbox"

            tables.filter { (stored.rows[it] ?: 0) == 0 }.shouldBeEmpty()
        }

        test("the same scan finds what the IdP keeps in clear by design, such as the EntityID of a Service Provider") {
            stored
            val entityId = Plaintext.text("EntityID", SP_ENTITY_ID)

            environment.database.scan(listOf(entityId)).findings shouldContain
                Finding("${environment.acme.schema}.service_providers.entity_id", "EntityID")
        }

        test("Valkey counts the limits under ids and hashed addresses, and holds nothing of the journey") {
            stored
            val entries = valkey.entries()

            entries.shouldNotBeEmpty()
            entries.flatMap { (key, value) -> listOf(key, value) }.forEach { kept ->
                secrets.filter { it.foundIn(kept) }.map { it.label }.shouldBeEmpty()
            }
        }
    })
