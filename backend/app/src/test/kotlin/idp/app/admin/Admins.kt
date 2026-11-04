package idp.app.admin

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import idp.app.login.Browser
import idp.app.login.LoginEnvironment
import idp.app.login.MultipartForm
import idp.app.login.Reply
import idp.app.login.TestTenant
import idp.app.login.codeBody
import idp.app.login.text
import idp.app.login.totpCode
import idp.authentication.application.CsrfCookie
import idp.sharedkernel.bus.decorators.LoggerDecorator
import idp.sharedkernel.security.UserRole
import org.slf4j.LoggerFactory
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

fun searchBody(email: String) = """{"email":"$email"}"""

fun usersBody(vararg ids: Any?) = """{"userIds":[${ids.joinToString(",") { if (it == null) "null" else "\"$it\"" }}]}"""

/** The values `SpSettings` starts with, unless a test names others. */
fun spSettingsBody(
    nameIdFormat: String = "PERSISTENT",
    signedPart: String = "ASSERTION",
    signatureAlgorithm: String = "RSA_PSS_SHA256",
    attributes: List<String> = emptyList(),
) = """{"nameIdFormat":"$nameIdFormat","signedPart":"$signedPart","signatureAlgorithm":"$signatureAlgorithm",""" +
    """"attributes":[${attributes.joinToString(",") { "\"$it\"" }}]}"""

/** The upload of the console: a name and the metadata as a file. */
fun metadataForm(
    name: String,
    metadata: ByteArray,
    fileName: String = "sp-metadata.xml",
) = MultipartForm().field("name", name).file("metadata", fileName, metadata)

/** The admin messages the bus ran during [action], so a test can tell that a page came from one query. */
fun messagesDuring(action: () -> Reply): Pair<Reply, List<String>> {
    val logger = LoggerFactory.getLogger(LoggerDecorator::class.java) as Logger
    val lines = ListAppender<ILoggingEvent>().also { it.start() }
    logger.addAppender(lines)
    val reply =
        try {
            action()
        } finally {
            logger.detachAppender(lines)
        }
    return reply to lines.list.map { it.formattedMessage }.filter { "message=idp.audience.admin." in it }
}

/** The values of the relaxed policy of `acme` in `LoginEnvironment`, unless a test names others. */
fun policyBody(
    mfaForUsers: Boolean = false,
    passkeyRequired: Boolean = false,
    idleSeconds: Long = 1800,
    absoluteSeconds: Long = 43200,
) = """{"mfaForUsers":$mfaForUsers,"passkeyRequired":$passkeyRequired,"sessionIdleSeconds":$idleSeconds,""" +
    """"sessionAbsoluteSeconds":$absoluteSeconds}"""

fun Browser.put(
    path: String,
    body: String,
    vararg headers: String,
): Reply {
    val csrf = cookies[CsrfCookie.NAME]?.let { arrayOf(CsrfCookie.HEADER, it) } ?: emptyArray()
    return send("PUT", path, HttpRequest.BodyPublishers.ofString(body), *csrf, *headers)
}

/** Back to the policy `LoginEnvironment` starts with, so a test that changed it leaves the next one as it found it. */
fun LoginEnvironment.resetPolicies() {
    val defaults = "passkey_required = false, session_idle_seconds = 1800, session_absolute_seconds = 43200, version = version + 1"
    sql.update("UPDATE ${acme.table("tenant_policies")} SET mfa_for_users = false, $defaults")
    sql.update("UPDATE ${globex.table("tenant_policies")} SET mfa_for_users = true, $defaults")
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
