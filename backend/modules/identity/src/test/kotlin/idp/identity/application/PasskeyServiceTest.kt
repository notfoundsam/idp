package idp.identity.application

import com.webauthn4j.converter.AttestationObjectConverter
import com.webauthn4j.converter.AuthenticatorDataConverter
import com.webauthn4j.converter.CollectedClientDataConverter
import com.webauthn4j.converter.util.ObjectConverter
import com.webauthn4j.data.AttestationConveyancePreference
import com.webauthn4j.data.AuthenticatorSelectionCriteria
import com.webauthn4j.data.PublicKeyCredentialCreationOptions
import com.webauthn4j.data.PublicKeyCredentialParameters
import com.webauthn4j.data.PublicKeyCredentialRequestOptions
import com.webauthn4j.data.PublicKeyCredentialRpEntity
import com.webauthn4j.data.PublicKeyCredentialType
import com.webauthn4j.data.PublicKeyCredentialUserEntity
import com.webauthn4j.data.ResidentKeyRequirement
import com.webauthn4j.data.UserVerificationRequirement
import com.webauthn4j.data.attestation.AttestationObject
import com.webauthn4j.data.attestation.authenticator.AAGUID
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData
import com.webauthn4j.data.attestation.authenticator.AuthenticatorData
import com.webauthn4j.data.attestation.authenticator.COSEKey
import com.webauthn4j.data.attestation.authenticator.EC2COSEKey
import com.webauthn4j.data.attestation.authenticator.EdDSACOSEKey
import com.webauthn4j.data.attestation.authenticator.RSACOSEKey
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement
import com.webauthn4j.data.client.ClientDataType
import com.webauthn4j.data.client.CollectedClientData
import com.webauthn4j.data.client.Origin
import com.webauthn4j.data.client.challenge.DefaultChallenge
import com.webauthn4j.data.extension.authenticator.AuthenticationExtensionAuthenticatorOutput
import com.webauthn4j.data.extension.authenticator.RegistrationExtensionAuthenticatorOutput
import com.webauthn4j.test.authenticator.webauthn.NoneAttestationAuthenticator
import com.webauthn4j.test.authenticator.webauthn.WebAuthnAuthenticatorAdaptor
import com.webauthn4j.test.client.ClientPlatform
import idp.identity.adapters.persistence.Passkeys
import idp.identity.domain.credential.PasskeyCredential
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.audit.AuditLog
import idp.sharedkernel.audit.AuditRecord
import idp.sharedkernel.audit.AuditTarget
import idp.sharedkernel.crypto.KeyHierarchy
import idp.sharedkernel.crypto.adapter.OpenBaoKeyManagementService
import idp.sharedkernel.migration.TenantMigrationRunner
import idp.sharedkernel.migration.TenantSchema
import idp.sharedkernel.migration.TenantSchemaFactory
import idp.sharedkernel.outbox.OutboxPublisher
import idp.sharedkernel.scheduling.TenantCleanup
import idp.sharedkernel.security.UserRole
import idp.sharedkernel.tenant.TenantContext
import idp.sharedkernel.tenant.TenantTransaction
import idp.sharedkernel.testing.TestDatabase
import idp.sharedkernel.testing.TestOpenBao
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.jdbc.core.JdbcTemplate
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.interfaces.EdECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import java.sql.Timestamp
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val EMAIL = "ada.lovelace@acme.test"
private const val HOST = "acme.idp.test"
private const val OTHER_HOST = "globex.idp.test"
private const val IP = "198.51.100.7"
private const val WAIT_SECONDS = 30L
private const val RSA_BITS = 2048
private val ADMIN = Actor.User(UUID.randomUUID())
private val KEY_CACHE_TTL: Duration = Duration.ofMinutes(1)
private val CHALLENGE_TTL: Duration = Duration.ofMinutes(5)
private val OBJECTS = ObjectConverter()
private val MALFORMED_CLIENT_DATA =
    mapOf(
        "bytes" to byteArrayOf(3),
        "nothing" to byteArrayOf(),
        "text" to "not json".toByteArray(),
        "an empty object" to "{}".toByteArray(),
        "a challenge that is no base64url" to """{"challenge":"***"}""".toByteArray(),
        "a number for a challenge" to """{"challenge":7}""".toByteArray(),
        "a bad origin" to """{"type":"webauthn.get","challenge":"AAAA","origin":"\u0000"}""".toByteArray(),
    )

private class PasskeyAudit : AuditLog {
    val changes = CopyOnWriteArrayList<AuditRecord>()
    val denials = CopyOnWriteArrayList<AuditRecord>()

    @Volatile var failing = false

    override fun recordChange(record: AuditRecord) {
        check(!failing) { "audit is down" }
        changes += record
    }

    override fun recordDenial(record: AuditRecord) {
        denials += record
    }
}

private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

private fun https(host: String) = Origin("https://$host")

/** The browser and a platform authenticator of one person. */
private class Device {
    val authenticator = NoneAttestationAuthenticator()
    val client = ClientPlatform(https(HOST), WebAuthnAuthenticatorAdaptor(authenticator))

    fun attestation(
        options: PasskeyRegistrationOptions,
        rpId: String = HOST,
        origin: Origin = https(HOST),
        algorithms: List<COSEAlgorithmIdentifier> = options.algorithms.map(COSEAlgorithmIdentifier::create),
    ): PasskeyAttestation {
        client.origin = origin
        val credential =
            client.create(
                PublicKeyCredentialCreationOptions(
                    PublicKeyCredentialRpEntity(rpId, "Acme"),
                    PublicKeyCredentialUserEntity(options.userHandle, EMAIL, "Ada"),
                    DefaultChallenge(options.challenge),
                    algorithms.map { PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, it) },
                    null,
                    null,
                    AuthenticatorSelectionCriteria(null, ResidentKeyRequirement.REQUIRED, UserVerificationRequirement.REQUIRED),
                    AttestationConveyancePreference.NONE,
                    null,
                ),
            )
        val response = credential.response.shouldNotBeNull()
        return PasskeyAttestation(response.attestationObject.shouldNotBeNull(), response.clientDataJSON.shouldNotBeNull())
    }

    fun assertion(
        challenge: ByteArray,
        rpId: String = HOST,
        origin: Origin = https(HOST),
    ): PasskeyAssertion {
        client.origin = origin
        val required = UserVerificationRequirement.REQUIRED
        val credential = client.get(PublicKeyCredentialRequestOptions(DefaultChallenge(challenge), null, rpId, null, required, null))
        val response = credential.response.shouldNotBeNull()
        return PasskeyAssertion(
            credential.rawId.shouldNotBeNull(),
            response.userHandle,
            response.authenticatorData.shouldNotBeNull(),
            response.clientDataJSON.shouldNotBeNull(),
            response.signature.shouldNotBeNull(),
        )
    }
}

private class SoftKeyType(
    val signature: String,
    val generate: () -> KeyPair,
    val publicKey: (KeyPair) -> COSEKey,
)

private fun ecKey(
    curve: String,
    signature: String,
    algorithm: COSEAlgorithmIdentifier,
) = SoftKeyType(
    signature,
    { KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(curve)) }.generateKeyPair() },
    { EC2COSEKey.create(it.public as ECPublicKey, algorithm) },
)

private fun rsaKey(
    signature: String,
    algorithm: COSEAlgorithmIdentifier,
) = SoftKeyType(
    signature,
    { KeyPairGenerator.getInstance("RSA").apply { initialize(RSA_BITS) }.generateKeyPair() },
    { RSACOSEKey.create(it.public as RSAPublicKey, algorithm) },
)

private val ED25519 =
    SoftKeyType(
        "Ed25519",
        { KeyPairGenerator.getInstance("Ed25519").generateKeyPair() },
        { EdDSACOSEKey.create(it.public as EdECPublicKey, COSEAlgorithmIdentifier.EdDSA) },
    )

/** What the emulator cannot do: most algorithms, a counter stuck at zero, no user verification, a repeated credential id. */
private class SoftPasskey(
    private val type: SoftKeyType = ED25519,
    private val backupEligible: Boolean = false,
) {
    val credentialId: ByteArray = ByteArray(16).also(SecureRandom()::nextBytes)
    private val keys = type.generate()

    private fun flags(
        attested: Boolean,
        verified: Boolean,
        backedUp: Boolean,
    ): Byte {
        var flags = AuthenticatorData.BIT_UP.toInt()
        if (verified) flags = flags or AuthenticatorData.BIT_UV.toInt()
        if (attested) flags = flags or AuthenticatorData.BIT_AT.toInt()
        if (backupEligible) flags = flags or AuthenticatorData.BIT_BE.toInt()
        if (backedUp) flags = flags or AuthenticatorData.BIT_BS.toInt()
        return flags.toByte()
    }

    fun attestation(
        challenge: ByteArray,
        verified: Boolean = true,
    ): PasskeyAttestation {
        val data =
            AuthenticatorData<RegistrationExtensionAuthenticatorOutput>(
                sha256(HOST.toByteArray()),
                flags(attested = true, verified, backedUp = false),
                0,
                AttestedCredentialData(AAGUID.ZERO, credentialId, type.publicKey(keys)),
            )
        return PasskeyAttestation(
            AttestationObjectConverter(OBJECTS).convertToBytes(AttestationObject(data, NoneAttestationStatement())),
            clientData(ClientDataType.WEBAUTHN_CREATE, challenge),
        )
    }

    fun assertion(
        challenge: ByteArray,
        user: UUID,
        verified: Boolean = true,
        backedUp: Boolean = false,
    ): PasskeyAssertion {
        val data =
            AuthenticatorDataConverter(OBJECTS).convert(
                AuthenticatorData<AuthenticationExtensionAuthenticatorOutput>(
                    sha256(HOST.toByteArray()),
                    flags(attested = false, verified, backedUp),
                    0,
                ),
            )
        val client = clientData(ClientDataType.WEBAUTHN_GET, challenge)
        val signature =
            Signature.getInstance(type.signature).run {
                initSign(keys.private)
                update(data + sha256(client))
                sign()
            }
        return PasskeyAssertion(credentialId, PasskeyCredential.userHandle(user), data, client, signature)
    }

    private fun clientData(
        type: ClientDataType,
        challenge: ByteArray,
    ) = CollectedClientDataConverter(OBJECTS).convertToBytes(
        CollectedClientData(type, DefaultChallenge(challenge), https(HOST), null),
    )
}

private class PasskeyFixture {
    val clock = SteppedClock()
    val database = TestDatabase.create()
    val owner = database.owner().also { TenantMigrationRunner(it).migrateAll() }
    val sql = JdbcTemplate(owner)
    val kms = OpenBaoKeyManagementService(TestOpenBao.address, TestOpenBao.TOKEN)
    val transaction = TenantTransaction(database.pooled("worker_runtime"))
    val keys = KeyHierarchy(kms, transaction, KEY_CACHE_TTL)
    val audit = PasskeyAudit()
    val identity = IdentityService(transaction, keys, audit, OutboxPublisher(), clock)
    val service = PasskeyService(transaction, audit, clock)

    fun tenant(): TenantContext {
        val id = UUID.randomUUID()
        sql.update("INSERT INTO platform.tenants (id, name, status) VALUES (?, 'tenant', 'ACTIVE')", id)
        TenantSchemaFactory(owner).create(id)
        TenantMigrationRunner(owner).migrateTenant(id)
        val context = TenantContext(id, kms.createKey("tenant-$id"))
        TenantContext.with(context) { keys.createTenantKey() }
        return context
    }

    fun <T> TenantContext.acting(block: PasskeyService.() -> T): T = TenantContext.with(this) { service.block() }

    fun TenantContext.user(email: String = EMAIL): UUID =
        TenantContext.with(this) { identity.invite(email, UserRole.USER, ADMIN, IP) }.also { audit.changes.clear() }

    fun TenantContext.registered(
        user: UUID,
        device: Device = Device(),
    ): Device =
        device.also {
            val options = acting { beginRegistration(user) }
            acting { finishRegistration(user, it.attestation(options), HOST, ADMIN, IP) }
            audit.changes.clear()
        }

    fun TenantContext.registered(
        user: UUID,
        key: SoftPasskey,
    ) {
        val options = acting { beginRegistration(user) }
        acting { finishRegistration(user, key.attestation(options.challenge), HOST, ADMIN, IP) }
        audit.changes.clear()
    }

    fun TenantContext.loginChallenge(): ByteArray = acting { beginAuthentication() }.challenge

    fun TenantContext.table(name: String) = "\"${TenantSchema(tenantId).name}\".$name"

    fun TenantContext.credentials(user: UUID): List<Map<String, Any?>> =
        sql.queryForList("SELECT * FROM ${table("passkey_credentials")} WHERE user_id = ?", user)

    fun TenantContext.challenges(): List<Map<String, Any?>> = sql.queryForList("SELECT * FROM ${table("passkey_challenges")}")

    fun TenantContext.signCount(user: UUID): Long = credentials(user).single()["sign_count"] as Long

    fun TenantContext.userVersion(user: UUID): Long? =
        sql.queryForObject("SELECT version FROM ${table("users")} WHERE id = ?", Long::class.java, user)
}

private fun <T> inParallel(
    count: Int,
    action: () -> T,
): List<T> {
    val barrier = CyclicBarrier(count)
    val threads = Executors.newFixedThreadPool(count)
    return (1..count)
        .map {
            CompletableFuture.supplyAsync(
                {
                    barrier.await(WAIT_SECONDS, TimeUnit.SECONDS)
                    action()
                },
                threads,
            )
        }.map { it.get(WAIT_SECONDS, TimeUnit.SECONDS) }
        .also { threads.shutdown() }
}

private fun ByteArray.tampered(): ByteArray = copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }

class PasskeyServiceTest :
    FunSpec({
        context("the options of a registration") {
            test("declare ES256, EdDSA and RS256, nothing else") {
                PasskeyFixture().run {
                    val tenant = tenant()

                    tenant.acting { beginRegistration(tenant.user()) }.algorithms shouldContainExactly listOf(-7L, -8L, -257L)
                }
            }

            test("carry a challenge of 256 random bits and the handle of the user") {
                PasskeyFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()

                    val first = tenant.acting { beginRegistration(user) }
                    val second = tenant.acting { beginRegistration(user) }

                    first.challenge shouldHaveSize 32
                    first.challenge.contentEquals(second.challenge) shouldBe false
                    first.userHandle shouldBe PasskeyCredential.userHandle(user)
                }
            }

            test("exclude the passkeys the user already has") {
                PasskeyFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    tenant.registered(user)

                    val options = tenant.acting { beginRegistration(user) }

                    options.excludeCredentials.single() shouldBe tenant.credentials(user).single()["credential_id"]
                }
            }

            test("an unknown user is refused") {
                PasskeyFixture().run {
                    val tenant = tenant()

                    shouldThrow<UserNotFound> { tenant.acting { beginRegistration(UUID.randomUUID()) } }
                }
            }
        }

        context("registering") {
            test("stores the public credential of the user and writes the audit log") {
                PasskeyFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    val device = Device()
                    val options = tenant.acting { beginRegistration(user) }

                    val id = tenant.acting { finishRegistration(user, device.attestation(options), HOST, ADMIN, IP) }

                    val row = tenant.credentials(user).single()
                    row["id"] shouldBe id
                    row["last_used_at"].shouldBeNull()
                    val record = audit.changes.single()
                    record.action shouldBe "user.passkey_registered"
                    record.actor shouldBe ADMIN
                    record.target shouldBe AuditTarget.user(user)
                    record.ip shouldBe IP
                }
            }

            test("nothing is stored when the audit record fails") {
                PasskeyFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    val options = tenant.acting { beginRegistration(user) }
                    audit.failing = true

                    shouldThrow<IllegalStateException> {
                        tenant.acting { finishRegistration(user, Device().attestation(options), HOST, ADMIN) }
                    }

                    tenant.credentials(user).shouldBeEmpty()
                }
            }

            test("a user can hold several passkeys") {
                PasskeyFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()

                    tenant.registered(user)
                    tenant.registered(user)

                    tenant.credentials(user) shouldHaveSize 2
                }
            }

            test("does not touch the user and does not conflict with an edit of the profile") {
                PasskeyFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    val userVersion = tenant.userVersion(user)
                    val profileVersion = TenantContext.with(tenant) { identity.profile(user) }.shouldNotBeNull().version

                    tenant.registered(user)
                    TenantContext.with(tenant) { identity.updateProfile(user, ProfileChange("Ada", null), ADMIN) }

                    tenant.userVersion(user) shouldBe userVersion
                    val profile = TenantContext.with(tenant) { identity.profile(user) }.shouldNotBeNull()
                    profile.version shouldBe profileVersion + 1
                    profile.name shouldBe "Ada"
                }
            }

            test("a credential that is already registered is refused, for its owner and for anyone else") {
                PasskeyFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    val other = tenant.user("grace.hopper@acme.test")
                    val key = SoftPasskey()
                    tenant.registered(user, key)

                    listOf(user, other).forEach { target ->
                        val options = tenant.acting { beginRegistration(target) }
                        shouldThrow<PasskeyNotValid> {
                            tenant.acting { finishRegistration(target, key.attestation(options.challenge), HOST, ADMIN) }
                        }
                    }

                    tenant.credentials(user) shouldHaveSize 1
                    tenant.credentials(other).shouldBeEmpty()
                    audit.changes.shouldBeEmpty()
                }
            }

            context("the challenge") {
                test("works once") {
                    PasskeyFixture().run {
                        val tenant = tenant()
                        val user = tenant.user()
                        val options = tenant.acting { beginRegistration(user) }
                        val attestation = Device().attestation(options)
                        tenant.acting { finishRegistration(user, attestation, HOST, ADMIN) }

                        shouldThrow<PasskeyNotValid> { tenant.acting { finishRegistration(user, attestation, HOST, ADMIN) } }
                    }
                }

                test("is spent by a failed attempt") {
                    PasskeyFixture().run {
                        val tenant = tenant()
                        val user = tenant.user()
                        val options = tenant.acting { beginRegistration(user) }
                        val device = Device()
                        val refused = device.attestation(options, origin = https("evil.test"))
                        shouldThrow<PasskeyNotValid> { tenant.acting { finishRegistration(user, refused, HOST, ADMIN) } }

                        shouldThrow<PasskeyNotValid> {
                            tenant.acting { finishRegistration(user, device.attestation(options), HOST, ADMIN) }
                        }
                    }
                }

                test("expires") {
                    PasskeyFixture().run {
                        val tenant = tenant()
                        val user = tenant.user()
                        val options = tenant.acting { beginRegistration(user) }
                        val attestation = Device().attestation(options)
                        clock.now = clock.now.plus(CHALLENGE_TTL)

                        shouldThrow<PasskeyNotValid> { tenant.acting { finishRegistration(user, attestation, HOST, ADMIN) } }
                    }
                }

                test("of another user is refused") {
                    PasskeyFixture().run {
                        val tenant = tenant()
                        val user = tenant.user()
                        val other = tenant.user("grace.hopper@acme.test")
                        val options = tenant.acting { beginRegistration(other) }
                        val attestation = Device().attestation(options)

                        shouldThrow<PasskeyNotValid> { tenant.acting { finishRegistration(user, attestation, HOST, ADMIN) } }
                    }
                }

                test("of a login is refused") {
                    PasskeyFixture().run {
                        val tenant = tenant()
                        val user = tenant.user()
                        val options = tenant.acting { beginRegistration(user) }
                        val login = PasskeyRegistrationOptions(tenant.loginChallenge(), options.userHandle, options.algorithms, emptyList())
                        val attestation = Device().attestation(login)

                        shouldThrow<PasskeyNotValid> { tenant.acting { finishRegistration(user, attestation, HOST, ADMIN) } }
                    }
                }
            }

            context("the checks of the response") {
                test("a wrong origin is refused") {
                    PasskeyFixture().run {
                        val tenant = tenant()
                        val user = tenant.user()
                        val options = tenant.acting { beginRegistration(user) }
                        val attestation = Device().attestation(options, origin = https("evil.test"))

                        shouldThrow<PasskeyNotValid> { tenant.acting { finishRegistration(user, attestation, HOST, ADMIN) } }

                        tenant.credentials(user).shouldBeEmpty()
                    }
                }

                test("a wrong RP ID is refused") {
                    PasskeyFixture().run {
                        val tenant = tenant()
                        val user = tenant.user()
                        val options = tenant.acting { beginRegistration(user) }
                        val attestation = Device().attestation(options, rpId = OTHER_HOST)

                        shouldThrow<PasskeyNotValid> { tenant.acting { finishRegistration(user, attestation, HOST, ADMIN) } }
                    }
                }

                test("the parent domain as RP ID is refused: the RP ID is the full host of the tenant") {
                    PasskeyFixture().run {
                        val tenant = tenant()
                        val user = tenant.user()
                        val options = tenant.acting { beginRegistration(user) }
                        val attestation = Device().attestation(options, rpId = "idp.test")

                        shouldThrow<PasskeyNotValid> { tenant.acting { finishRegistration(user, attestation, HOST, ADMIN) } }
                    }
                }

                test("a response without user verification is refused") {
                    PasskeyFixture().run {
                        val tenant = tenant()
                        val user = tenant.user()
                        val options = tenant.acting { beginRegistration(user) }
                        val attestation = SoftPasskey().attestation(options.challenge, verified = false)

                        shouldThrow<PasskeyNotValid> { tenant.acting { finishRegistration(user, attestation, HOST, ADMIN) } }

                        tenant.credentials(user).shouldBeEmpty()
                    }
                }

                MALFORMED_CLIENT_DATA.forEach { (name, clientData) ->
                    test("$name as client data is refused") {
                        PasskeyFixture().run {
                            val tenant = tenant()
                            val user = tenant.user()

                            shouldThrow<PasskeyNotValid> {
                                tenant.acting { finishRegistration(user, PasskeyAttestation(byteArrayOf(1), clientData), HOST, ADMIN) }
                            }
                        }
                    }
                }

                test("a garbled attestation with a live challenge is refused") {
                    PasskeyFixture().run {
                        val tenant = tenant()
                        val user = tenant.user()
                        val options = tenant.acting { beginRegistration(user) }
                        val genuine = SoftPasskey().attestation(options.challenge)
                        val garbled = PasskeyAttestation(byteArrayOf(1), genuine.clientDataJson)

                        shouldThrow<PasskeyNotValid> { tenant.acting { finishRegistration(user, garbled, HOST, ADMIN) } }
                    }
                }
            }

            context("the algorithms") {
                mapOf(
                    "ES256" to ecKey("secp256r1", "SHA256withECDSA", COSEAlgorithmIdentifier.ES256),
                    "EdDSA" to ED25519,
                    "RS256" to rsaKey("SHA256withRSA", COSEAlgorithmIdentifier.RS256),
                ).forEach { (name, type) ->
                    test("$name is accepted and can log in") {
                        PasskeyFixture().run {
                            val tenant = tenant()
                            val user = tenant.user()
                            val key = SoftPasskey(type)

                            tenant.registered(user, key)

                            tenant.acting { authenticate(key.assertion(tenant.loginChallenge(), user), HOST) } shouldBe user
                        }
                    }
                }

                mapOf(
                    "ES384" to ecKey("secp384r1", "SHA384withECDSA", COSEAlgorithmIdentifier.ES384),
                    "ES512" to ecKey("secp521r1", "SHA512withECDSA", COSEAlgorithmIdentifier.ES512),
                    "RS1" to rsaKey("SHA1withRSA", COSEAlgorithmIdentifier.RS1),
                    "RS384" to rsaKey("SHA384withRSA", COSEAlgorithmIdentifier.RS384),
                    "RS512" to rsaKey("SHA512withRSA", COSEAlgorithmIdentifier.RS512),
                ).forEach { (name, type) ->
                    test("$name is refused") {
                        PasskeyFixture().run {
                            val tenant = tenant()
                            val user = tenant.user()
                            val options = tenant.acting { beginRegistration(user) }
                            val attestation = SoftPasskey(type).attestation(options.challenge)

                            shouldThrow<PasskeyNotValid> { tenant.acting { finishRegistration(user, attestation, HOST, ADMIN) } }

                            tenant.credentials(user).shouldBeEmpty()
                        }
                    }
                }

                test("PS256 is refused") {
                    PasskeyFixture().run {
                        val tenant = tenant()
                        val user = tenant.user()
                        val options = tenant.acting { beginRegistration(user) }
                        val attestation = Device().attestation(options, algorithms = listOf(COSEAlgorithmIdentifier.PS256))

                        shouldThrow<PasskeyNotValid> { tenant.acting { finishRegistration(user, attestation, HOST, ADMIN) } }

                        tenant.credentials(user).shouldBeEmpty()
                    }
                }
            }
        }

        context("whether a user has a passkey") {
            test("false before the first one") {
                PasskeyFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()

                    tenant.acting { hasPasskey(user) } shouldBe false
                }
            }

            test("true after a registration") {
                PasskeyFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    tenant.registered(user)

                    tenant.acting { hasPasskey(user) } shouldBe true
                }
            }

            test("a started registration does not count") {
                PasskeyFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    tenant.acting { beginRegistration(user) }

                    tenant.acting { hasPasskey(user) } shouldBe false
                }
            }

            test("the passkey of another user does not count") {
                PasskeyFixture().run {
                    val tenant = tenant()
                    tenant.registered(tenant.user())
                    val other = tenant.user("grace.hopper@acme.test")

                    tenant.acting { hasPasskey(other) } shouldBe false
                }
            }
        }

        context("logging in") {
            test("gives the owner and records the use") {
                PasskeyFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    val device = tenant.registered(user)
                    val before = tenant.signCount(user)

                    tenant.acting { authenticate(device.assertion(tenant.loginChallenge()), HOST) } shouldBe user

                    (tenant.signCount(user) > before) shouldBe true
                    (tenant.credentials(user).single()["last_used_at"] as Timestamp).toInstant() shouldBe clock.now
                }
            }

            test("changes neither the user nor the audit log: the login handler writes that") {
                PasskeyFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    val device = tenant.registered(user)
                    val before = tenant.userVersion(user)

                    tenant.acting { authenticate(device.assertion(tenant.loginChallenge()), HOST) } shouldBe user

                    tenant.userVersion(user) shouldBe before
                    audit.changes.shouldBeEmpty()
                }
            }

            MALFORMED_CLIENT_DATA.forEach { (name, clientData) ->
                test("$name as client data fails") {
                    PasskeyFixture().run {
                        val tenant = tenant()

                        val garbage = PasskeyAssertion(byteArrayOf(1), null, byteArrayOf(2), clientData, byteArrayOf(4))

                        tenant.acting { authenticate(garbage, HOST) }.shouldBeNull()
                    }
                }
            }

            test("garbled authenticator data with a live challenge fails") {
                PasskeyFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    val key = SoftPasskey()
                    tenant.registered(user, key)
                    val genuine = key.assertion(tenant.loginChallenge(), user)
                    val garbled =
                        PasskeyAssertion(
                            genuine.credentialId,
                            genuine.userHandle,
                            byteArrayOf(1),
                            genuine.clientDataJson,
                            genuine.signature,
                        )

                    tenant.acting { authenticate(garbled, HOST) }.shouldBeNull()
                }
            }

            test("an unknown credential fails") {
                PasskeyFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()

                    val assertion = SoftPasskey().assertion(tenant.loginChallenge(), user)

                    tenant.acting { authenticate(assertion, HOST) }.shouldBeNull()
                }
            }

            test("a forged signature fails") {
                PasskeyFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    val device = tenant.registered(user)
                    val genuine = device.assertion(tenant.loginChallenge())
                    val forged =
                        PasskeyAssertion(
                            genuine.credentialId,
                            genuine.userHandle,
                            genuine.authenticatorData,
                            genuine.clientDataJson,
                            genuine.signature.tampered(),
                        )

                    tenant.acting { authenticate(forged, HOST) }.shouldBeNull()
                }
            }

            test("a handle of another user fails") {
                PasskeyFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    val key = SoftPasskey()
                    tenant.registered(user, key)

                    val assertion = key.assertion(tenant.loginChallenge(), UUID.randomUUID())

                    tenant.acting { authenticate(assertion, HOST) }.shouldBeNull()
                }
            }

            test("a change of the backup state is stored") {
                PasskeyFixture().run {
                    val tenant = tenant()
                    val user = tenant.user()
                    val key = SoftPasskey(backupEligible = true)
                    tenant.registered(user, key)
                    tenant.credentials(user).single()["backed_up"] shouldBe false

                    tenant.acting { authenticate(key.assertion(tenant.loginChallenge(), user, backedUp = true), HOST) } shouldBe user

                    tenant.credentials(user).single()["backed_up"] shouldBe true
                }
            }

            context("the challenge") {
                test("works once, even for a key without a counter: nothing else stops a repeated response then") {
                    PasskeyFixture().run {
                        val tenant = tenant()
                        val user = tenant.user()
                        val key = SoftPasskey()
                        tenant.registered(user, key)
                        val assertion = key.assertion(tenant.loginChallenge(), user)
                        tenant.acting { authenticate(assertion, HOST) } shouldBe user

                        tenant.acting { authenticate(assertion, HOST) }.shouldBeNull()
                    }
                }

                test("of parallel requests with one response exactly one passes") {
                    val parallel = 16
                    PasskeyFixture().run {
                        val tenant = tenant()
                        val user = tenant.user()
                        val key = SoftPasskey()
                        tenant.registered(user, key)
                        val assertion = key.assertion(tenant.loginChallenge(), user)

                        val results = inParallel(parallel) { tenant.acting { authenticate(assertion, HOST) } }

                        results.filterNotNull() shouldContainExactly listOf(user)
                    }
                }

                test("is spent by a failed attempt") {
                    PasskeyFixture().run {
                        val tenant = tenant()
                        val user = tenant.user()
                        val device = tenant.registered(user)
                        val challenge = tenant.loginChallenge()
                        tenant.acting { authenticate(device.assertion(challenge, origin = https("evil.test")), HOST) }.shouldBeNull()

                        tenant.acting { authenticate(device.assertion(challenge), HOST) }.shouldBeNull()
                    }
                }

                test("expires") {
                    PasskeyFixture().run {
                        val tenant = tenant()
                        val user = tenant.user()
                        val device = tenant.registered(user)
                        val assertion = device.assertion(tenant.loginChallenge())
                        clock.now = clock.now.plus(CHALLENGE_TTL)

                        tenant.acting { authenticate(assertion, HOST) }.shouldBeNull()
                    }
                }

                test("of a registration is refused") {
                    PasskeyFixture().run {
                        val tenant = tenant()
                        val user = tenant.user()
                        val device = tenant.registered(user)
                        val assertion = device.assertion(tenant.acting { beginRegistration(user) }.challenge)

                        tenant.acting { authenticate(assertion, HOST) }.shouldBeNull()
                    }
                }
            }

            context("the checks of the response") {
                test("a wrong origin fails") {
                    PasskeyFixture().run {
                        val tenant = tenant()
                        val user = tenant.user()
                        val device = tenant.registered(user)

                        val assertion = device.assertion(tenant.loginChallenge(), origin = https("evil.test"))

                        tenant.acting { authenticate(assertion, HOST) }.shouldBeNull()
                    }
                }

                test("a response without user verification fails") {
                    PasskeyFixture().run {
                        val tenant = tenant()
                        val user = tenant.user()
                        val key = SoftPasskey()
                        tenant.registered(user, key)

                        val assertion = key.assertion(tenant.loginChallenge(), user, verified = false)

                        tenant.acting { authenticate(assertion, HOST) }.shouldBeNull()
                    }
                }

                test("a response for the host of another tenant fails, even with the credential in place") {
                    PasskeyFixture().run {
                        val tenant = tenant()
                        val user = tenant.user()
                        val device = tenant.registered(user)

                        val assertion = device.assertion(tenant.loginChallenge())

                        tenant.acting { authenticate(assertion, OTHER_HOST) }.shouldBeNull()
                    }
                }
            }

            context("the signature counter") {
                test("a counter that does not grow is refused, stays stored and goes to the audit log") {
                    PasskeyFixture().run {
                        val tenant = tenant()
                        val user = tenant.user()
                        val device = tenant.registered(user)
                        tenant.acting { authenticate(device.assertion(tenant.loginChallenge()), HOST) } shouldBe user
                        val stored = tenant.signCount(user)
                        device.authenticator.isCountUpEnabled = false

                        val clone = device.assertion(tenant.loginChallenge())
                        tenant.acting { authenticate(clone, HOST, IP) }.shouldBeNull()

                        tenant.signCount(user) shouldBe stored
                        val record = audit.denials.single()
                        record.action shouldBe "user.passkey_counter_regressed"
                        record.actor shouldBe Actor.Anonymous
                        record.target shouldBe AuditTarget.user(user)
                        record.ip shouldBe IP
                    }
                }

                test("the conditional update refuses a counter that fell behind in a race") {
                    PasskeyFixture().run {
                        val tenant = tenant()
                        val user = tenant.user()
                        val device = tenant.registered(user)
                        val passkeys = Passkeys()
                        val credentialId = tenant.credentials(user).single()["credential_id"] as ByteArray
                        val stale =
                            TenantContext.with(tenant) { transaction.execute { dsl -> passkeys.find(dsl, credentialId) } }.shouldNotBeNull()
                        tenant.acting { authenticate(device.assertion(tenant.loginChallenge()), HOST) } shouldBe user
                        val winner = tenant.signCount(user)

                        val recorded =
                            TenantContext.with(tenant) {
                                transaction.execute { dsl -> passkeys.recordUse(dsl, stale, winner, stale.backedUp, clock.now) }
                            }

                        recorded shouldBe false
                    }
                }

                test("an authenticator that always reports zero keeps working") {
                    PasskeyFixture().run {
                        val tenant = tenant()
                        val user = tenant.user()
                        val key = SoftPasskey()
                        tenant.registered(user, key)

                        repeat(2) { tenant.acting { authenticate(key.assertion(tenant.loginChallenge(), user), HOST) } shouldBe user }

                        tenant.signCount(user) shouldBe 0L
                        audit.denials.shouldBeEmpty()
                    }
                }
            }
        }

        context("two tenants") {
            test("a passkey of tenant A is refused on tenant B") {
                PasskeyFixture().run {
                    val first = tenant()
                    val second = tenant()
                    val user = first.user()
                    val device = first.registered(user)

                    val assertion = device.assertion(second.loginChallenge())

                    second.acting { authenticate(assertion, OTHER_HOST) }.shouldBeNull()
                    second.acting { authenticate(assertion, HOST) }.shouldBeNull()
                }
            }

            test("one authenticator can hold a passkey in each tenant, and they stay apart") {
                PasskeyFixture().run {
                    val first = tenant()
                    val second = tenant()
                    val inFirst = first.user()
                    val inSecond = second.user()
                    val key = SoftPasskey()

                    first.registered(inFirst, key)
                    second.registered(inSecond, key)

                    first.credentials(inFirst) shouldHaveSize 1
                    second.credentials(inSecond) shouldHaveSize 1
                    second.credentials(inFirst).shouldBeEmpty()
                }
            }
        }

        context("the challenges") {
            test("a login stores one without a user") {
                PasskeyFixture().run {
                    val tenant = tenant()

                    val challenge = tenant.loginChallenge()

                    challenge shouldHaveSize 32
                    tenant.challenges().single()["user_id"].shouldBeNull()
                }
            }

            test("cleanup removes only expired ones") {
                PasskeyFixture().run {
                    val tenant = tenant()
                    tenant.loginChallenge()
                    clock.now = clock.now.plus(CHALLENGE_TTL)
                    val live = tenant.loginChallenge()
                    val cleanup: TenantCleanup = identity.passkeyChallengeCleanup
                    val now: OffsetDateTime = clock.now.atOffset(ZoneOffset.UTC)

                    TenantContext.with(tenant) { transaction.execute { dsl -> cleanup.delete(dsl, now) } }

                    cleanup.name shouldBe "passkey-challenges"
                    tenant.challenges().single()["challenge"] shouldBe live
                }
            }
        }

        test("erasing the user removes the credential and the open challenges") {
            PasskeyFixture().run {
                val tenant = tenant()
                val user = tenant.user()
                tenant.registered(user)
                tenant.acting { beginRegistration(user) }

                TenantContext.with(tenant) { identity.eraseUser(user, ADMIN) }

                tenant.credentials(user).shouldBeEmpty()
                tenant.challenges().shouldBeEmpty()
            }
        }
    })
