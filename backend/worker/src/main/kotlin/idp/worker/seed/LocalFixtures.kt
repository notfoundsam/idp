package idp.worker.seed

import idp.federation.application.FederationService
import idp.federation.application.SamlAttribute
import idp.federation.application.SignatureAlgorithm
import idp.federation.application.SpSettings
import idp.identity.application.IdentityService
import idp.identity.application.PasswordService
import idp.identity.application.ProfileChange
import idp.identity.application.TotpService
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.crypto.KeyHierarchy
import idp.sharedkernel.security.UserRole
import idp.sharedkernel.tenant.PlatformTransaction
import idp.sharedkernel.tenant.TenantContext
import idp.sharedkernel.tenant.TenantResolver
import idp.sharedkernel.tenant.TenantTransaction
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.io.File
import java.nio.ByteBuffer
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Written in `dev-tools/README.md`, so a person and the e2e tests log in with the same values. */
const val LOCAL_PASSWORD = "local-fixture-password-acme"
const val LOCAL_TOTP_SECRET = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"

private const val TOTP_STEP_SECONDS = 30L
private const val TOTP_MODULUS = 1_000_000
private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
private const val BASE32_BITS = 5
private const val BYTE_BITS = 8

private class LocalUser(
    val email: String,
    val name: String,
    val assigned: Boolean,
)

/** Alice may use the test SP, Bob may not, so both answers of the assignment check are a login away. */
private val USERS =
    listOf(
        LocalUser("alice@acme.test", "Alice Example", assigned = true),
        LocalUser("bob@acme.test", "Bob Example", assigned = false),
    )

/**
 * Users with a known password and TOTP secret, the SimpleSAMLphp SP and its assignments, in the `acme` tenant. Runs after
 * [LocalSeed] and skips what is there, so a restart changes nothing.
 */
@Component
@Profile("local")
@Order(2)
class LocalFixtures(
    @Value("\${idp.tenants.base-domain}") private val baseDomain: String,
    @Value("\${idp.local-fixtures.sp-metadata:}") private val spMetadata: String,
    platform: PlatformTransaction,
    private val transaction: TenantTransaction,
    keys: KeyHierarchy,
    audit: AuditLog,
    private val identity: IdentityService,
    private val federation: FederationService,
) : ApplicationRunner {
    private val tenants = TenantResolver(platform, Duration.ZERO)
    private val passwords = PasswordService(transaction, identity, audit)
    private val totps = TotpService(transaction, keys, audit)

    override fun run(args: ApplicationArguments) {
        val acme = checkNotNull(tenants.resolve("acme.$baseDomain")) { "The local seed made no acme tenant" }.context()
        TenantContext.with(acme) {
            val users = USERS.associateWith(::userOf)
            val sp = spMetadata.takeIf { it.isNotBlank() }?.let(::serviceProvider) ?: return@with
            federation.assign(sp, users.filterKeys { it.assigned }.values, Actor.System)
        }
    }

    private fun userOf(user: LocalUser): UUID {
        identity.findByEmail(user.email)?.let { return it.id }
        val id =
            transaction.execute {
                val id = identity.invite(user.email, UserRole.USER, Actor.System)
                identity.acceptInvitation(checkNotNull(identity.issueInvitation(id, Actor.System)), Actor.System)
                id
            }
        identity.updateProfile(id, ProfileChange(user.name, null), Actor.System)
        passwords.setPassword(id, LOCAL_PASSWORD, emptySet(), Actor.System)
        totps.enroll(id, LOCAL_TOTP_SECRET)
        check(totps.confirm(id, totpCode(LOCAL_TOTP_SECRET), Actor.System)) { "The TOTP of ${user.email} was not confirmed" }
        return id
    }

    private fun serviceProvider(path: String): UUID {
        val metadata = File(path).readBytes()
        val entityId = Regex("""entityID="([^"]+)"""").find(String(metadata))?.groupValues?.get(1)
        entityId?.let(federation::serviceProviderByEntityId)?.let { return it.id }
        val id = federation.registerServiceProvider("SimpleSAMLphp", metadata, Actor.System)
        // SimpleSAMLphp 2.5.3 verifies no RSA-PSS, see SamlInteropTest.
        val settings =
            SpSettings(
                signatureAlgorithm = SignatureAlgorithm.RSA_SHA256,
                attributes = setOf(SamlAttribute.EMAIL, SamlAttribute.DISPLAY_NAME),
            )
        federation.changeSettings(id, settings, Actor.System)
        return id
    }
}

/** RFC 6238 with the parameters of `TotpService`, written out here because the service never hands out a code. */
private fun totpCode(secret: String): String {
    var buffer = 0
    var bits = 0
    val key =
        buildList {
            secret.forEach { char ->
                buffer = (buffer shl BASE32_BITS) or BASE32.indexOf(char)
                bits += BASE32_BITS
                if (bits >= BYTE_BITS) {
                    bits -= BYTE_BITS
                    add((buffer shr bits).toByte())
                    buffer = buffer and ((1 shl bits) - 1)
                }
            }
        }.toByteArray()
    val step = Instant.now().epochSecond / TOTP_STEP_SECONDS
    val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }
    val hash = mac.doFinal(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(step).array())
    val offset = hash.last().toInt() and 0x0f
    val value = ByteBuffer.wrap(hash, offset, Int.SIZE_BYTES).int and Int.MAX_VALUE
    return (value % TOTP_MODULUS).toString().padStart(6, '0')
}
