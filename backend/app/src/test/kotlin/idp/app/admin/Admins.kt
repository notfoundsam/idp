package idp.app.admin

import idp.app.login.Browser
import idp.app.login.LoginEnvironment
import idp.app.login.Reply
import idp.app.login.TestTenant
import idp.app.login.codeBody
import idp.app.login.text
import idp.app.login.totpCode
import idp.authentication.application.CsrfCookie
import idp.sharedkernel.security.UserRole
import org.springframework.context.ConfigurableApplicationContext
import java.net.http.HttpRequest
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

private val AGED: Duration = Duration.ofMinutes(11)

fun roleBody(role: UserRole) = """{"role":"$role"}"""

fun inviteBody(
    email: String,
    role: UserRole,
) = """{"email":"$email","role":"$role"}"""

fun Browser.put(
    path: String,
    body: String,
): Reply {
    val headers = cookies[CsrfCookie.NAME]?.let { arrayOf(CsrfCookie.HEADER, it) } ?: emptyArray()
    return send("PUT", path, HttpRequest.BodyPublishers.ofString(body), *headers)
}

/** [secret] is the TOTP enrolled at the login, or null when the policy asked for none. */
data class SignedIn(
    val browser: Browser,
    val user: UUID,
    val secret: String?,
)

/** A login with the password and a TOTP enrolled on the way, because every policy asks an admin for a second factor. */
fun LoginEnvironment.signedIn(
    application: ConfigurableApplicationContext,
    email: String,
    tenant: TestTenant = acme,
    role: UserRole = UserRole.TENANT_ADMIN,
): SignedIn {
    val user = application.user(email, tenant, role = role)
    val browser = Browser(application, tenant.host)
    val flow = browser.identified(email)
    val secret =
        if (browser.password(flow).json().text("step") == "ENROLL_MFA") {
            browser.post("/api/flows/$flow/enrollment/totp").json().text("secret").also {
                check(browser.post("/api/flows/$flow/enrollment/totp/confirmation", codeBody(totpCode(it))).status == 200)
            }
        } else {
            null
        }
    check(browser.complete(flow).status == 200) { "The login did not complete" }
    return SignedIn(browser, user, secret)
}

/** A second session of a user who enrolled [secret]. The code is a step ahead, because the one of the enrollment is spent. */
fun Browser.loggedInWithTotp(
    email: String,
    secret: String,
): Browser {
    val flow = identified(email)
    check(password(flow).json().text("step") == "SECOND_FACTOR")
    check(post("/api/flows/$flow/totp", codeBody(totpCode(secret, shift = 1))).status == 200)
    check(complete(flow).status == 200) { "The login did not complete" }
    return this
}

/** Set from the clock of the test, which the application reads too. */
fun LoginEnvironment.age(
    user: UUID,
    tenant: TestTenant = acme,
) {
    sql.update(
        "UPDATE ${tenant.table("sso_sessions")} SET authenticated_at = ? WHERE user_id = ?",
        Timestamp.from(Instant.now().minus(AGED)),
        user,
    )
}
