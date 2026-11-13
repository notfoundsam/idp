package idp.app.login

import idp.authentication.application.CsrfCookie
import idp.authentication.application.FlowCookie
import idp.authentication.application.SessionCookie
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.audit.AuditTarget
import idp.sharedkernel.security.UserRole
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldBeEmpty
import io.kotest.matchers.string.shouldNotBeBlank
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val WAIT_SECONDS = 30L
private const val UNKNOWN = "nobody@acme.test"

private fun email(name: String) = "$name@acme.test"

private fun assertionOf(credentialId: String) =
    """{"credentialId":"$credentialId","userHandle":null,"authenticatorData":"AAAA","clientDataJson":"AAAA","signature":"AAAA"}"""

class FlowApiTest :
    FunSpec({
        val environment = LoginEnvironment()
        val application = environment.start()
        val sql = environment.sql

        afterSpec { application.close() }

        fun browser(host: String = ACME) = Browser(application, host)

        fun user(
            name: String,
            password: String? = PASSWORD,
            status: String = "ACTIVE",
            tenant: TestTenant = environment.acme,
            role: UserRole = UserRole.USER,
        ): UUID = with(environment) { application.user(email(name), tenant, password, status, role) }

        fun audit(tenant: TestTenant = environment.acme) = with(environment) { application.audit(tenant) }

        /** Runs each call at the same moment, each from a thread of its own. */
        fun <T> parallel(vararg calls: () -> T): List<T> {
            val barrier = CyclicBarrier(calls.size)
            val pool = Executors.newFixedThreadPool(calls.size)
            try {
                return calls
                    .map { call ->
                        pool.submit<T> {
                            barrier.await(WAIT_SECONDS, TimeUnit.SECONDS)
                            call()
                        }
                    }.map { it.get(WAIT_SECONDS, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }
        }

        fun Browser.enrollTotp(flow: String): String {
            val secret = post("/api/flows/$flow/enrollment/totp").json().text("secret")
            post("/api/flows/$flow/enrollment/totp/confirmation", codeBody(totpCode(secret))).status shouldBe 200
            return secret
        }

        fun mfaUser(name: String): Pair<String, String> {
            user(name, tenant = environment.globex)
            val browser = browser(GLOBEX)
            val flow = browser.identified(email(name))
            browser.password(flow).status shouldBe 200
            val secret = browser.enrollTotp(flow)
            browser.complete(flow).status shouldBe 200
            return email(name) to secret
        }

        fun refused(reply: Reply) {
            reply.status shouldBe 422
            reply.body.shouldBeEmpty()
        }

        context("a login") {
            test("walks the steps and opens a session") {
                user("walks")
                val browser = browser()

                val started = browser.post("/api/flows")
                started.status shouldBe 201
                started.json().text("step") shouldBe "IDENTIFY"
                val flow = started.json().text("id")

                browser.post("/api/flows/$flow/login", loginBody(email("walks"))).json().text("step") shouldBe "PASSWORD"
                browser.password(flow).json().text("step") shouldBe "COMPLETE"

                val completed = browser.complete(flow)

                completed.status shouldBe 200
                completed.json().get("returnUrl").isNull shouldBe true
                browser.cookies.keys shouldContain SessionCookie.NAME
                browser.cookies.keys shouldContain CsrfCookie.NAME
            }

            test("a page reloaded halfway reads where the flow stands") {
                user("reloads")
                val browser = browser()
                val flow = browser.identified(email("reloads"))

                browser.get("/api/flows/$flow").json().text("step") shouldBe "PASSWORD"
            }

            test("a step cannot be skipped") {
                user("skips")
                val browser = browser()
                val flow = browser.identified(email("skips"))

                browser.complete(flow).status shouldBe 409
                browser.cookies.keys shouldNotContain SessionCookie.NAME
            }

            test("a repeated step answers a conflict") {
                user("repeats")
                val browser = browser()
                val flow = browser.identified(email("repeats"))
                browser.password(flow).status shouldBe 200

                browser.password(flow).status shouldBe 409
            }

            test("an unknown flow reads as expired") {
                browser().post("/api/flows").status shouldBe 201

                browser().get("/api/flows/${UUID.randomUUID()}").status shouldBe 410
            }

            test("a flow past its time reads as expired") {
                user("late")
                val browser = browser()
                val flow = browser.identified(email("late"))
                val flows = environment.acme.table("flows")
                sql.update("UPDATE $flows SET expires_at = now() - interval '1 second' WHERE id = ?::uuid", flow)

                browser.password(flow).status shouldBe 410
            }

            test("the session cookie replaces nothing on another tenant") {
                user("tenanted")
                val browser = browser().loggedIn(email("tenanted"))
                val elsewhere = browser(GLOBEX)
                elsewhere.cookies += browser.cookies

                elsewhere.post("/api/flows").json().text("step") shouldBe "IDENTIFY"
            }
        }

        context("the second factor") {
            test("a user with no factor enrolls TOTP under the default policy and leaves with recovery codes") {
                user("enrolls", tenant = environment.globex)
                val browser = browser(GLOBEX)
                val flow = browser.identified(email("enrolls"))

                val after = browser.password(flow).json()
                after.text("step") shouldBe "ENROLL_MFA"
                after.strings("factors").shouldContainExactlyInAnyOrder("TOTP", "PASSKEY")

                val secret = browser.post("/api/flows/$flow/enrollment/totp").json().text("secret")
                val confirmed = browser.post("/api/flows/$flow/enrollment/totp/confirmation", codeBody(totpCode(secret)))

                confirmed.status shouldBe 200
                confirmed.json().text("step") shouldBe "COMPLETE"
                confirmed.json().strings("recoveryCodes") shouldHaveSize 10
                browser.complete(flow).status shouldBe 200
            }

            test("a wrong code at the enrollment proves nothing") {
                user("mistypes", tenant = environment.globex)
                val browser = browser(GLOBEX)
                val flow = browser.identified(email("mistypes"))
                browser.password(flow)
                browser.post("/api/flows/$flow/enrollment/totp")

                refused(browser.post("/api/flows/$flow/enrollment/totp/confirmation", codeBody("000000")))

                browser.get("/api/flows/$flow").json().text("step") shouldBe "ENROLL_MFA"
            }

            test("a user with TOTP passes it on the next login") {
                val (login, secret) = mfaUser("passes")
                val browser = browser(GLOBEX)
                val flow = browser.identified(login)

                val after = browser.password(flow).json()
                after.text("step") shouldBe "SECOND_FACTOR"
                after.strings("factors").shouldContainExactlyInAnyOrder("TOTP", "RECOVERY_CODE")

                browser.post("/api/flows/$flow/totp", codeBody(totpCode(secret, shift = 1))).json().text("step") shouldBe "COMPLETE"
                browser.complete(flow).status shouldBe 200
            }

            test("a password alone adds no factor to a user who has one") {
                val (login, _) = mfaUser("guarded")
                val browser = browser(GLOBEX)
                val flow = browser.identified(login)
                browser.password(flow)

                browser.post("/api/flows/$flow/enrollment/totp").status shouldBe 409
                browser.post("/api/flows/$flow/enrollment/passkey/options").status shouldBe 409
            }

            test("a wrong TOTP code is refused and the login stays at the step") {
                val (login, _) = mfaUser("wrongcode")
                val browser = browser(GLOBEX)
                val flow = browser.identified(login)
                browser.password(flow)

                refused(browser.post("/api/flows/$flow/totp", codeBody("000000")))

                browser.get("/api/flows/$flow").json().text("step") shouldBe "SECOND_FACTOR"
            }

            test("a recovery code replaces TOTP once") {
                user("recovers", tenant = environment.globex)
                val first = browser(GLOBEX)
                val enrolling = first.identified(email("recovers"))
                first.password(enrolling)
                val secret = first.post("/api/flows/$enrolling/enrollment/totp").json().text("secret")
                val codes =
                    first
                        .post("/api/flows/$enrolling/enrollment/totp/confirmation", codeBody(totpCode(secret)))
                        .json()
                        .strings("recoveryCodes")
                first.complete(enrolling)

                val browser = browser(GLOBEX)
                val flow = browser.identified(email("recovers"))
                browser.password(flow)

                browser.post("/api/flows/$flow/recovery-code", codeBody(codes.first())).json().text("step") shouldBe "COMPLETE"

                val again = browser(GLOBEX)
                val second = again.identified(email("recovers"))
                again.password(second)
                refused(again.post("/api/flows/$second/recovery-code", codeBody(codes.first())))
            }
        }

        context("passkeys") {
            fun enrolled(name: String): PasskeyDevice {
                user(name, tenant = environment.globex)
                val device = PasskeyDevice(GLOBEX)
                val browser = browser(GLOBEX)
                val flow = browser.identified(email(name))
                browser.password(flow)
                val options = browser.post("/api/flows/$flow/enrollment/passkey/options").json()
                options.text("userVerification") shouldBe "required"
                options.text("residentKey") shouldBe "required"
                options.text("attestation") shouldBe "none"

                val enrolled = browser.post("/api/flows/$flow/enrollment/passkey", device.attestation(options))

                enrolled.status shouldBe 200
                enrolled.json().text("step") shouldBe "COMPLETE"
                enrolled.json().strings("recoveryCodes") shouldHaveSize 10
                browser.complete(flow).status shouldBe 200
                return device
            }

            test("a passkey alone opens a session, without anyone being named first") {
                val device = enrolled("passwordless")
                val browser = browser(GLOBEX)
                val flow = browser.startFlow()
                val options = browser.post("/api/flows/$flow/passkey/options").json()

                options.text("userVerification") shouldBe "required"
                options.text("rpId") shouldBe GLOBEX
                options.has("allowCredentials") shouldBe false

                browser.post("/api/flows/$flow/passkey", device.assertion(options)).json().text("step") shouldBe "COMPLETE"
                browser.complete(flow).status shouldBe 200
            }

            test("a passkey of someone else and a forged one answer alike") {
                val device = enrolled("ownkey")
                user("victim", tenant = environment.globex)
                val browser = browser(GLOBEX)
                val flow = browser.identified(email("victim"))

                val options = browser.post("/api/flows/$flow/passkey/options").json()
                refused(browser.post("/api/flows/$flow/passkey", device.assertion(options)))
                refused(browser.post("/api/flows/$flow/passkey", assertionOf("AAAA")))
            }

            test("a body that is no base64url answers 400") {
                val browser = browser()
                val flow = browser.startFlow()

                browser.post("/api/flows/$flow/passkey", assertionOf("***")).status shouldBe 400
            }

            test("a blocked user cannot log in with a passkey") {
                val device = enrolled("blockedkey")
                sql.update(
                    "UPDATE ${environment.globex.table("users")} SET status = 'BLOCKED' WHERE id = " +
                        "(SELECT user_id FROM ${environment.globex.table("passkey_credentials")} ORDER BY created_at DESC LIMIT 1)",
                )
                val browser = browser(GLOBEX)
                val flow = browser.startFlow()
                val options = browser.post("/api/flows/$flow/passkey/options").json()

                refused(browser.post("/api/flows/$flow/passkey", device.assertion(options)))
            }
        }

        context("the audit") {
            test("a login records its factors, and the session opening with it") {
                val user = user("audited")

                browser().loggedIn(email("audited"))

                val entry = audit().first { it.action == "login.succeeded" && it.target == AuditTarget.user(user) }
                entry.actor shouldBe Actor.User(user)
                entry.details.shouldNotBeNull()["factors"] shouldBe "PASSWORD"
                entry.ip.shouldNotBeNull().shouldNotBeBlank()
                audit().filter { it.action == "session.opened" && it.target == AuditTarget.user(user) } shouldHaveSize 1
            }

            test("a second factor shows in the record") {
                val (login, secret) = mfaUser("twofactors")
                val browser = browser(GLOBEX)
                val flow = browser.identified(login)
                browser.password(flow)
                browser.post("/api/flows/$flow/totp", codeBody(totpCode(secret, shift = 1)))

                browser.complete(flow)

                audit(environment.globex).first { it.action == "login.succeeded" }.details.shouldNotBeNull()["factors"] shouldBe
                    "PASSWORD,TOTP"
            }

            test("a wrong password records the factor and the target, and no actor") {
                val user = user("failing")
                val browser = browser()

                refused(browser.password(browser.identified(email("failing")), WRONG_PASSWORD))

                val entry = audit().first { it.action == "login.failed" && it.target == AuditTarget.user(user) }
                entry.actor shouldBe Actor.Anonymous
                entry.details.shouldNotBeNull()["factor"] shouldBe "PASSWORD"
                entry.ip.shouldNotBeNull().shouldNotBeBlank()
            }

            test("a login that matched nobody leaves a failure without a target and without the login") {
                val before = audit().count { it.action == "login.failed" && it.target == null }
                val browser = browser()

                refused(browser.password(browser.identified(UNKNOWN)))

                val entries = audit().filter { it.action == "login.failed" && it.target == null }
                entries shouldHaveSize before + 1
                entries
                    .first()
                    .details
                    .shouldNotBeNull()
                    .values shouldNotContain UNKNOWN
            }

            test("a flow that proved nothing new is no login") {
                val user = user("repeatflow")
                val browser = browser().loggedIn(email("repeatflow"))
                val session = browser.cookies.getValue(SessionCookie.NAME)

                val completed = browser.complete(browser.startFlow())

                completed.status shouldBe 200
                completed.cookies().shouldBeEmpty()
                browser.cookies.getValue(SessionCookie.NAME) shouldBe session
                audit().filter { it.action == "login.succeeded" && it.target == AuditTarget.user(user) } shouldHaveSize 1
            }
        }

        context("the browser") {
            test("a flow cannot go on from another browser") {
                user("bound")
                val browser = browser()
                val flow = browser.identified(email("bound"))
                val thief = browser()
                thief.startFlow()

                thief.get("/api/flows/$flow").status shouldBe 410
                thief.password(flow).status shouldBe 410
                thief.complete(flow).status shouldBe 410

                browser.get("/api/flows/$flow").json().text("step") shouldBe "PASSWORD"
            }

            test("a flow cannot go on without the cookie at all") {
                val browser = browser()
                val flow = browser.startFlow()

                browser.cookies.remove(FlowCookie.NAME)

                browser.get("/api/flows/$flow").status shouldBe 410
            }

            test("the tabs of one browser keep their own flows") {
                val browser = browser()
                val first = browser.startFlow()
                val second = browser.startFlow()

                first shouldNotBe second
                browser.get("/api/flows/$first").status shouldBe 200
                browser.get("/api/flows/$second").status shouldBe 200
            }
        }

        context("parallel submissions") {
            test("of one step do not both pass, and the second gets a conflict without a false alarm") {
                val user = user("double")
                val browser = browser()
                val flow = browser.identified(email("double"))

                val statuses = parallel({ browser.password(flow).status }, { browser.password(flow).status })

                statuses.shouldContainExactlyInAnyOrder(200, 409)
                audit().filter { it.action == "login.failed" && it.target == AuditTarget.user(user) }.shouldBeEmpty()
                environment.failedAttempts(user) shouldBe 0
            }

            test("of a second factor do not burn the code") {
                val (login, secret) = mfaUser("doublecode")
                val browser = browser(GLOBEX)
                val flow = browser.identified(login)
                browser.password(flow)
                val code = codeBody(totpCode(secret, shift = 1))

                val statuses =
                    parallel(
                        { browser.post("/api/flows/$flow/totp", code).status },
                        { browser.post("/api/flows/$flow/totp", code).status },
                    )

                statuses.shouldContainExactlyInAnyOrder(200, 409)
            }

            test("of completion open one session") {
                val user = user("doublecomplete")
                val browser = browser()
                val flow = browser.identified(email("doublecomplete"))
                browser.password(flow)

                val statuses = parallel({ browser.complete(flow).status }, { browser.complete(flow).status })

                statuses.count { it == 200 } shouldBe 1
                listOf(409, 410) shouldContain statuses.single { it != 200 }
                sql.queryForObject(
                    "SELECT count(*) FROM ${environment.acme.table("sso_sessions")} WHERE user_id = ?",
                    Int::class.java,
                    user,
                ) shouldBe 1
            }

            test("a lease left by a crashed check ends, so the flow is not stuck") {
                user("crashed")
                val browser = browser()
                val flow = browser.identified(email("crashed"))
                val flows = environment.acme.table("flows")
                sql.update("UPDATE $flows SET step_lease_until = now() + interval '1 minute' WHERE id = ?::uuid", flow)

                browser.password(flow).status shouldBe 409

                sql.update("UPDATE $flows SET step_lease_until = now() - interval '1 second' WHERE id = ?::uuid", flow)
                browser.password(flow).status shouldBe 200
            }
        }

        context("what a stranger can learn") {
            test("an unknown login reads like a known one at every step") {
                user("known")
                val known = browser()
                val unknown = browser()
                val knownFlow = known.startFlow()
                val unknownFlow = unknown.startFlow()

                val knownStep = known.post("/api/flows/$knownFlow/login", loginBody(email("known")))
                val unknownStep = unknown.post("/api/flows/$unknownFlow/login", loginBody(UNKNOWN))

                unknownStep.status shouldBe knownStep.status
                unknownStep.json().text("step") shouldBe knownStep.json().text("step")
                unknownStep.json().get("factors") shouldBe knownStep.json().get("factors")

                val knownRefusal = known.password(knownFlow, WRONG_PASSWORD)
                val unknownRefusal = unknown.password(unknownFlow, WRONG_PASSWORD)

                unknownRefusal.status shouldBe knownRefusal.status
                unknownRefusal.body shouldBe knownRefusal.body
                unknownRefusal.cookies() shouldBe knownRefusal.cookies()
            }

            test("a blocked user, an invited user and a user without a password read like a wrong password") {
                user("blocked", status = "BLOCKED")
                user("invited", status = "INVITED")
                user("nopassword", password = null)

                listOf("blocked", "invited").forEach { name ->
                    val browser = browser()
                    refused(browser.password(browser.identified(email(name))))
                }
                val browser = browser()
                refused(browser.password(browser.identified(email("nopassword"))))
            }

            test("a user who is blocked during the flow gets no session") {
                val user = user("blockedlater")
                val browser = browser()
                val flow = browser.identified(email("blockedlater"))
                browser.password(flow).status shouldBe 200

                sql.update("UPDATE ${environment.acme.table("users")} SET status = 'BLOCKED' WHERE id = ?", user)

                browser.complete(flow).status shouldBe 409
                browser.cookies.keys shouldNotContain SessionCookie.NAME
            }

            test("a running delay reads like a wrong password, even for the right one") {
                val user = user("delayed")
                val browser = browser()

                repeat(4) { refused(browser.password(browser.identified(email("delayed")), WRONG_PASSWORD)) }
                val credentials = environment.acme.table("password_credentials")
                val delayed = sql.queryForObject("SELECT next_attempt_at FROM $credentials WHERE user_id = ?", Timestamp::class.java, user)
                delayed.shouldNotBeNull()
                // From the clock of the test, so the delay is still running however slow the next request is.
                sql.update(
                    "UPDATE $credentials SET next_attempt_at = ? WHERE user_id = ?",
                    Timestamp.from(Instant.now().plus(Duration.ofHours(1))),
                    user,
                )

                refused(browser.password(browser.identified(email("delayed"))))
                environment.failedAttempts(user) shouldBe 4
            }
        }

        context("reauthentication") {
            test("a flow that asks for it does not count the session") {
                user("again")
                val browser = browser().loggedIn(email("again"))

                val started = browser.post("/api/flows", """{"reauthenticate":true}""").json()

                started.text("step") shouldBe "IDENTIFY"
            }

            test("it issues a new session with a new login time") {
                user("newtime")
                val browser = browser().loggedIn(email("newtime"))
                val before = browser.cookies.getValue(SessionCookie.NAME)
                val flow = browser.startFlow("""{"reauthenticate":true}""")
                browser.post("/api/flows/$flow/login", loginBody(email("newtime")))
                browser.password(flow)

                browser.complete(flow).status shouldBe 200

                browser.cookies.getValue(SessionCookie.NAME) shouldNotBe before
            }
        }

        context("input") {
            test("an unknown field is refused") {
                val browser = browser()
                val flow = browser.startFlow()

                browser.post("/api/flows/$flow/login", """{"login":"$EMAIL","admin":true}""").status shouldBe 400
            }

            test("a missing field is refused") {
                val browser = browser()
                val flow = browser.startFlow()

                browser.post("/api/flows/$flow/login", "{}").status shouldBe 400
            }

            test("a value comes only from its declared source") {
                user("source")
                val browser = browser()
                val flow = browser.startFlow()

                browser.post("/api/flows/$flow/login?login=$UNKNOWN", loginBody(email("source")))

                browser.password(flow).status shouldBe 200
            }

            test("a flow id that is no UUID answers 400") {
                browser().get("/api/flows/nonsense").status shouldBe 400
            }

            test("a browser cannot name where a login leads") {
                val browser = browser()

                browser.post("/api/flows", """{"returnUrl":"https://evil.test"}""").status shouldBe 400
                browser.cookies.keys shouldNotContain FlowCookie.NAME
            }
        }

        test("nothing a client sends reaches the journal of another tenant") {
            val before = audit(environment.globex).size
            val browser = browser()

            refused(browser.password(browser.identified(UNKNOWN)))

            audit(environment.globex) shouldHaveSize before
        }

        test("a session cookie that is dead does not stop a new login") {
            user("deadcookie")
            val browser = browser()
            browser.cookies[SessionCookie.NAME] = "dead"

            browser.loggedIn(email("deadcookie"))

            browser.cookies.getValue(SessionCookie.NAME) shouldNotBe "dead"
        }

        test("a successful login leaves no failure behind") {
            val user = user("clean")

            browser().loggedIn(email("clean"))

            environment.failedAttempts(user) shouldBe 0
            audit().firstOrNull { it.action == "login.failed" && it.target == AuditTarget.user(user) }.shouldBeNull()
        }
    })
