package idp.federation.application

import idp.federation.ADMIN
import idp.federation.Acs
import idp.federation.Federation
import idp.federation.IP
import idp.federation.adapters.saml.parseEntity
import idp.federation.adapters.saml.signingCertificatesOf
import idp.federation.domain.AcsUrlNotAllowed
import idp.federation.domain.KeyRotationInProgress
import idp.federation.domain.NoKeyRotation
import idp.federation.spMetadata
import idp.identity.application.UserNotFound
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.audit.AuditTarget
import idp.sharedkernel.crypto.FieldDecryptionException
import idp.sharedkernel.persistence.VersionConflictException
import idp.sharedkernel.tenant.TenantContext
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.opensaml.saml.saml2.metadata.EntityDescriptor
import org.opensaml.saml.saml2.metadata.IDPSSODescriptor
import org.springframework.dao.DataIntegrityViolationException
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.UUID

private fun Federation.privateKeyCiphertexts(context: TenantContext): List<ByteArray> =
    sql.queryForList("SELECT private_key_ciphertext FROM ${context.table(KEYS)}", ByteArray::class.java).filterNotNull()

private const val KEYS = "saml_signing_keys"

private fun parsedMetadata(xml: String): EntityDescriptor = parseEntity(xml)

private fun certificatesOf(xml: String) = signingCertificatesOf(parsedMetadata(xml))

private fun EntityDescriptor.idpssoDescriptors() = roleDescriptors.filterIsInstance<IDPSSODescriptor>()

private fun signs(credential: SigningCredential): Boolean {
    val data = "assertion".toByteArray()
    val signature =
        Signature
            .getInstance("SHA256withRSA")
            .apply { initSign(credential.privateKey) }
            .apply { update(data) }
            .sign()
    return Signature
        .getInstance("SHA256withRSA")
        .apply { initVerify(credential.certificate.publicKey) }
        .apply { update(data) }
        .verify(signature)
}

class FederationServiceTest :
    FunSpec({
        context("the identity of a tenant") {
            test("names the tenant host in the EntityID and the SSO endpoint") {
                Federation().run {
                    val tenant = tenant("acme.idp.test")

                    val metadata = parsedMetadata(tenant.acting { metadata() }.shouldNotBeNull())

                    metadata.entityID shouldBe "https://acme.idp.test/saml/metadata"
                    tenant.acting { entityId() } shouldBe "https://acme.idp.test/saml/metadata"
                    metadata
                        .idpssoDescriptors()
                        .single()
                        .singleSignOnServices
                        .map { it.location }
                        .toSet() shouldBe
                        setOf("https://acme.idp.test/saml/sso")
                }
            }

            test("starts with one RSA-3072 signing key, created by the system and audited") {
                Federation().run {
                    val tenant = tenant()

                    val certificate = tenant.acting { signingCredential() }.certificate

                    (certificate.publicKey as RSAPublicKey).modulus.bitLength() shouldBe 3072
                    certificate.sigAlgName shouldBe "SHA256withRSA"
                    tenant.count("saml_signing_keys") shouldBe 1
                    audit.of("saml.signing_key_created").single().actor shouldBe Actor.System
                }
            }

            test("a repeat keeps the EntityID and the first key") {
                Federation().run {
                    val tenant = tenant("acme.idp.test")
                    val first = tenant.acting { signingCredential() }.certificate

                    tenant.acting { createIdentity("other.idp.test") }

                    tenant.acting { entityId() } shouldBe "https://acme.idp.test/saml/metadata"
                    tenant.acting { signingCredential() }.certificate shouldBe first
                    audit.of("saml.signing_key_created") shouldHaveSize 1
                }
            }

            test("differs between tenants in EntityID and keys") {
                Federation().run {
                    val acme = tenant("acme.idp.test")
                    val globex = tenant("globex.idp.test")

                    acme.acting { entityId() } shouldNotBe globex.acting { entityId() }
                    acme.acting { signingCredential() }.certificate.publicKey shouldNotBe
                        globex.acting { signingCredential() }.certificate.publicKey
                }
            }

            test("is missing before provisioning, and so is the metadata") {
                Federation().run {
                    val tenant = tenant(host = null)

                    tenant.acting { metadata() }.shouldBeNull()
                    shouldThrow<SamlIdentityMissing> { tenant.acting { signingCredential() } }
                    shouldThrow<SamlIdentityMissing> { tenant.acting { startKeyRotation(ADMIN) } }
                }
            }
        }

        context("the private key") {
            test("is stored encrypted and opens into the key of the certificate") {
                Federation().run {
                    val tenant = tenant()
                    val credential = tenant.acting { signingCredential() }

                    val stored = privateKeyCiphertexts(tenant).single()

                    stored shouldNotBe credential.privateKey.encoded
                    stored.asList().windowed(16).none { window ->
                        credential.privateKey.encoded
                            .asList()
                            .windowed(16)
                            .contains(window)
                    } shouldBe
                        true
                    signs(credential) shouldBe true
                }
            }

            test("copied into another tenant does not open") {
                Federation().run {
                    val acme = tenant("acme.idp.test")
                    val globex = tenant("globex.idp.test")
                    sql.update(
                        "UPDATE ${globex.table(KEYS)} SET id = ?, private_key_ciphertext = ?",
                        sql.queryForObject("SELECT id FROM ${acme.table(KEYS)}", UUID::class.java),
                        privateKeyCiphertexts(acme).single(),
                    )

                    shouldThrow<FieldDecryptionException> { globex.acting { signingCredential() } }
                }
            }

            test("copied into another row does not open") {
                Federation().run {
                    val tenant = tenant()
                    sql.update("UPDATE ${tenant.table(KEYS)} SET id = ?", UUID.randomUUID())

                    shouldThrow<FieldDecryptionException> { tenant.acting { signingCredential() } }
                }
            }

            test("does not print") {
                Federation().run {
                    val credential = tenant().acting { signingCredential() }

                    credential.toString() shouldBe "SigningCredential(${credential.certificate.serialNumber})"
                }
            }
        }

        context("a rotation of the signing key") {
            test("publishes two certificates while it runs, the signing one first") {
                Federation().run {
                    val tenant = tenant()
                    val current = tenant.acting { signingCredential() }.certificate

                    tenant.acting { startKeyRotation(ADMIN, IP) }

                    val published = certificatesOf(tenant.acting { metadata() }.shouldNotBeNull())
                    published shouldHaveSize 2
                    published.first() shouldBe current
                    tenant.acting { signingCredential() }.certificate shouldBe current
                }
            }

            test("lets the new key sign once it completes, and publishes only that one") {
                Federation().run {
                    val tenant = tenant()
                    val old = tenant.acting { signingCredential() }.certificate
                    tenant.acting { startKeyRotation(ADMIN, IP) }
                    val next = certificatesOf(tenant.acting { metadata() }.shouldNotBeNull()).last()

                    tenant.acting { completeKeyRotation(ADMIN, IP) }

                    certificatesOf(tenant.acting { metadata() }.shouldNotBeNull()).shouldContainExactly(next)
                    tenant.acting { signingCredential() }.certificate shouldBe next
                    next shouldNotBe old
                    tenant.count("saml_signing_keys") shouldBe 1
                }
            }

            test("is audited at both ends") {
                Federation().run {
                    val tenant = tenant()

                    val next = tenant.acting { startKeyRotation(ADMIN, IP) }
                    tenant.acting { completeKeyRotation(ADMIN, IP) }

                    audit.of("saml.key_rotation_started").single().run {
                        actor shouldBe ADMIN
                        target shouldBe AuditTarget.signingKey(next)
                        ip shouldBe IP
                    }
                    audit.of("saml.key_rotation_completed").single().target shouldBe AuditTarget.signingKey(next)
                }
            }

            test("cannot start twice") {
                Federation().run {
                    val tenant = tenant()
                    tenant.acting { startKeyRotation(ADMIN) }

                    shouldThrow<KeyRotationInProgress> { tenant.acting { startKeyRotation(ADMIN) } }

                    tenant.count("saml_signing_keys") shouldBe 2
                }
            }

            test("cannot complete without a start") {
                Federation().run {
                    val tenant = tenant()

                    shouldThrow<NoKeyRotation> { tenant.acting { completeKeyRotation(ADMIN) } }

                    audit.of("saml.key_rotation_completed").shouldBeEmpty()
                }
            }

            test("shows the keys in the order of metadata, the next one marked") {
                Federation().run {
                    val tenant = tenant()
                    val active = tenant.acting { signingKeys() }.single()
                    active.next shouldBe false

                    val next = tenant.acting { startKeyRotation(ADMIN) }

                    val keys = tenant.acting { signingKeys() }
                    keys.map { it.id } shouldBe listOf(active.id, next)
                    keys.map { it.next } shouldBe listOf(false, true)
                    keys.map { it.certificate } shouldBe certificatesOf(tenant.acting { metadata() }.shouldNotBeNull())
                    tenant.acting { completeKeyRotation(ADMIN) }
                    tenant.acting { signingKeys() }.single().run {
                        id shouldBe next
                        this.next shouldBe false
                    }
                }
            }
        }

        context("registering a Service Provider") {
            test("stores what the metadata says with the default settings, whatever NameID it asks for") {
                Federation().run {
                    val tenant = tenant()

                    val id = tenant.register(spMetadata(authnRequestsSigned = true, nameIdFormats = listOf(NameIdFormat.EMAIL.uri)))

                    val sp = tenant.acting { serviceProvider(id) }.shouldNotBeNull()
                    sp.entityId shouldBe "https://sp.example/saml"
                    sp.name shouldBe "Wiki"
                    sp.acs.shouldContainExactly(AcsEndpointView(0, "https://sp.example/saml/acs", isDefault = true))
                    sp.signingCertificates shouldHaveSize 1
                    sp.authnRequestsSigned shouldBe true
                    sp.settings shouldBe SpSettings(NameIdFormat.PERSISTENT, SignedPart.ASSERTION, SignatureAlgorithm.RSA_PSS_SHA256)
                    tenant.acting { serviceProviderByEntityId("https://sp.example/saml") }.shouldNotBeNull().id shouldBe id
                    tenant.acting { serviceProviders() }.map { it.id }.shouldContainExactly(id)
                }
            }

            test("keeps several endpoints in the order of their index") {
                Federation().run {
                    val tenant = tenant()

                    val id = tenant.register(spMetadata(acs = listOf(Acs("https://sp.example/b", 2), Acs("https://sp.example/a", 1, true))))

                    tenant.acting { serviceProvider(id) }.shouldNotBeNull().acs.shouldContainExactly(
                        AcsEndpointView(1, "https://sp.example/a", isDefault = true),
                        AcsEndpointView(2, "https://sp.example/b", isDefault = false),
                    )
                }
            }

            test("is audited with the EntityID") {
                Federation().run {
                    val tenant = tenant()

                    val id = tenant.register()

                    audit.of("sp.registered").single().run {
                        actor shouldBe ADMIN
                        target shouldBe AuditTarget.serviceProvider(id)
                        ip shouldBe IP
                        details shouldBe mapOf("entity_id" to "https://sp.example/saml")
                    }
                }
            }

            test("refuses a second SP with the same EntityID in one tenant") {
                Federation().run {
                    val tenant = tenant()
                    tenant.register()

                    shouldThrow<EntityIdTaken> { tenant.register(name = "Copy") }

                    tenant.count("service_providers") shouldBe 1
                    audit.of("sp.registered") shouldHaveSize 1
                }
            }

            test("allows the same EntityID in another tenant") {
                Federation().run {
                    val acme = tenant("acme.idp.test")
                    val globex = tenant("globex.idp.test")

                    acme.register()
                    globex.register()

                    acme.count("service_providers") shouldBe 1
                    globex.count("service_providers") shouldBe 1
                }
            }

            test("keeps nothing when the metadata is refused") {
                Federation().run {
                    val tenant = tenant()

                    shouldThrow<AcsUrlNotAllowed> { tenant.register(spMetadata(acs = listOf(Acs("javascript:alert(1)")))) }

                    tenant.count("service_providers") shouldBe 0
                    audit.of("sp.registered").shouldBeEmpty()
                }
            }
        }

        context("the settings of a Service Provider") {
            test("change the NameID format and what is signed") {
                Federation().run {
                    val tenant = tenant()
                    val id = tenant.register()
                    val settings = SpSettings(NameIdFormat.EMAIL, SignedPart.RESPONSE)

                    tenant.acting { changeSettings(id, settings, ADMIN, IP) }

                    val sp = tenant.acting { serviceProvider(id) }.shouldNotBeNull()
                    sp.settings shouldBe settings
                    sp.version shouldBe 1
                }
            }

            test("sign both parts on request") {
                Federation().run {
                    val tenant = tenant()
                    val id = tenant.register()

                    tenant.acting { changeSettings(id, SpSettings(NameIdFormat.EMAIL, SignedPart.BOTH), ADMIN) }

                    tenant
                        .acting { serviceProvider(id) }
                        .shouldNotBeNull()
                        .settings.signedPart shouldBe SignedPart.BOTH
                }
            }

            test("turn on rsa-sha256 for one SP only, and the journal shows it") {
                Federation().run {
                    val tenant = tenant()
                    val legacy = tenant.register()
                    val modern = tenant.register(spMetadata(entityId = "https://modern.example/saml"))

                    tenant.acting {
                        changeSettings(
                            legacy,
                            SpSettings(NameIdFormat.EMAIL, signatureAlgorithm = SignatureAlgorithm.RSA_SHA256),
                            ADMIN,
                            IP,
                        )
                    }

                    tenant
                        .acting { serviceProvider(legacy) }
                        .shouldNotBeNull()
                        .settings.signatureAlgorithm shouldBe
                        SignatureAlgorithm.RSA_SHA256
                    tenant
                        .acting { serviceProvider(modern) }
                        .shouldNotBeNull()
                        .settings.signatureAlgorithm shouldBe
                        SignatureAlgorithm.RSA_PSS_SHA256
                    audit.of("sp.settings_changed").single().run {
                        actor shouldBe ADMIN
                        target shouldBe AuditTarget.serviceProvider(legacy)
                        ip shouldBe IP
                        details["signature_algorithm"] shouldBe "RSA_SHA256"
                    }
                }
            }

            test("left as they are record nothing") {
                Federation().run {
                    val tenant = tenant()
                    val id = tenant.register()
                    val current = tenant.acting { serviceProvider(id) }.shouldNotBeNull().settings

                    tenant.acting { changeSettings(id, current, ADMIN) }

                    audit.of("sp.settings_changed").shouldBeEmpty()
                    tenant.acting { serviceProvider(id) }.shouldNotBeNull().version shouldBe 0
                }
            }

            test("of a missing SP fail") {
                Federation().run {
                    val tenant = tenant()

                    shouldThrow<ServiceProviderNotFound> {
                        tenant.acting { changeSettings(UUID.randomUUID(), SpSettings(NameIdFormat.EMAIL), ADMIN) }
                    }
                }
            }

            test("saved with the version read answer the next one") {
                Federation().run {
                    val tenant = tenant()
                    val id = tenant.register()

                    tenant.acting { changeSettings(id, SpSettings(NameIdFormat.EMAIL), ADMIN, version = 0) } shouldBe 1
                    tenant.acting { changeSettings(id, SpSettings(NameIdFormat.EMAIL), ADMIN, version = 1) } shouldBe 1
                    tenant.acting { serviceProvider(id) }.shouldNotBeNull().version shouldBe 1
                }
            }

            test("saved with a version that is no longer current change nothing, even when they are the same") {
                Federation().run {
                    val tenant = tenant()
                    val id = tenant.register()
                    tenant.acting { changeSettings(id, SpSettings(NameIdFormat.EMAIL), ADMIN, version = 0) }

                    shouldThrow<VersionConflictException> {
                        tenant.acting { changeSettings(id, SpSettings(signedPart = SignedPart.BOTH), ADMIN, version = 0) }
                    }
                    shouldThrow<VersionConflictException> {
                        tenant.acting { changeSettings(id, SpSettings(NameIdFormat.EMAIL), ADMIN, version = 0) }
                    }

                    tenant.acting { serviceProvider(id) }.shouldNotBeNull().settings shouldBe SpSettings(NameIdFormat.EMAIL)
                    audit.of("sp.settings_changed") shouldHaveSize 1
                }
            }
        }

        context("assignments") {
            test("a new user is assigned to no SP") {
                Federation().run {
                    val tenant = tenant()
                    val sp = tenant.register()

                    val user = tenant.user()

                    tenant.acting { assignmentsOf(user) }.shouldBeEmpty()
                    tenant.acting { isAssigned(sp, user) } shouldBe false
                    tenant.acting { assignedUsers(sp) }.shouldBeEmpty()
                }
            }

            test("assigning gives access to that SP only") {
                Federation().run {
                    val tenant = tenant()
                    val wiki = tenant.register()
                    val crm = tenant.register(spMetadata(entityId = "https://crm.example/saml"), name = "CRM")
                    val user = tenant.user()

                    tenant.acting { assign(wiki, listOf(user), ADMIN, IP) }

                    tenant.acting { isAssigned(wiki, user) } shouldBe true
                    tenant.acting { isAssigned(crm, user) } shouldBe false
                    tenant.acting { assignmentsOf(user) }.shouldContainExactly(wiki)
                }
            }

            test("a list assigns everyone once, even with a repeat in it") {
                Federation().run {
                    val tenant = tenant()
                    val sp = tenant.register()
                    val ada = tenant.user("ada@acme.test")
                    val grace = tenant.user("grace@acme.test")

                    tenant.acting { assign(sp, listOf(ada, grace, ada), ADMIN, IP) }

                    tenant.acting { assignedUsers(sp) }.shouldContainExactlyInAnyOrder(ada, grace)
                    audit
                        .of(
                            "sp.user_assigned",
                        ).map { it.target }
                        .shouldContainExactlyInAnyOrder(AuditTarget.user(ada), AuditTarget.user(grace))
                }
            }

            test("assigning twice keeps one row and one record") {
                Federation().run {
                    val tenant = tenant()
                    val sp = tenant.register()
                    val user = tenant.user()

                    tenant.acting { assign(sp, listOf(user), ADMIN, IP) }
                    tenant.acting { assign(sp, listOf(user), ADMIN, IP) }

                    tenant.count("sp_assignments") shouldBe 1
                    audit.of("sp.user_assigned").single().run {
                        actor shouldBe ADMIN
                        target shouldBe AuditTarget.user(user)
                        ip shouldBe IP
                        details shouldBe mapOf("sp_id" to sp.toString())
                    }
                }
            }

            test("revoking twice removes once and records once") {
                Federation().run {
                    val tenant = tenant()
                    val sp = tenant.register()
                    val user = tenant.user()
                    tenant.acting { assign(sp, listOf(user), ADMIN) }

                    tenant.acting { unassign(sp, listOf(user), ADMIN, IP) }
                    tenant.acting { unassign(sp, listOf(user), ADMIN, IP) }

                    tenant.acting { isAssigned(sp, user) } shouldBe false
                    audit.of("sp.user_unassigned").single().run {
                        target shouldBe AuditTarget.user(user)
                        details shouldBe mapOf("sp_id" to sp.toString())
                    }
                }
            }

            test("revoking someone never assigned records nothing") {
                Federation().run {
                    val tenant = tenant()
                    val sp = tenant.register()

                    tenant.acting { unassign(sp, listOf(tenant.user()), ADMIN) }

                    audit.of("sp.user_unassigned").shouldBeEmpty()
                }
            }

            test("a pair of user and SP is unique in the table") {
                Federation().run {
                    val tenant = tenant()
                    val sp = tenant.register()
                    val user = tenant.user()
                    tenant.acting { assign(sp, listOf(user), ADMIN) }

                    shouldThrow<DataIntegrityViolationException> {
                        sql.update(
                            "INSERT INTO ${tenant.table("sp_assignments")} (sp_id, user_id, assigned_at) VALUES (?, ?, now())",
                            sp,
                            user,
                        )
                    }
                }
            }

            test("an unknown user is refused and nobody of the list is assigned") {
                Federation().run {
                    val tenant = tenant()
                    val sp = tenant.register()
                    val user = tenant.user()

                    shouldThrow<UserNotFound> { tenant.acting { assign(sp, listOf(user, UUID.randomUUID()), ADMIN) } }

                    tenant.count("sp_assignments") shouldBe 0
                }
            }

            test("an unknown SP is refused") {
                Federation().run {
                    val tenant = tenant()
                    val user = tenant.user()

                    shouldThrow<ServiceProviderNotFound> { tenant.acting { assign(UUID.randomUUID(), listOf(user), ADMIN) } }
                    shouldThrow<ServiceProviderNotFound> { tenant.acting { unassign(UUID.randomUUID(), listOf(user), ADMIN) } }
                }
            }
        }

        context("removal") {
            test("of an SP takes its assignments and endpoints along, and is audited") {
                Federation().run {
                    val tenant = tenant()
                    val sp = tenant.register()
                    val other = tenant.register(spMetadata(entityId = "https://crm.example/saml"))
                    val user = tenant.user()
                    tenant.acting { assign(sp, listOf(user), ADMIN) }
                    tenant.acting { assign(other, listOf(user), ADMIN) }

                    tenant.acting { removeServiceProvider(sp, ADMIN, IP) }

                    tenant.acting { serviceProvider(sp) }.shouldBeNull()
                    tenant.acting { assignmentsOf(user) }.shouldContainExactly(other)
                    tenant.count("sp_acs_endpoints") shouldBe 1
                    audit.of("sp.removed").single().run {
                        target shouldBe AuditTarget.serviceProvider(sp)
                        details shouldBe mapOf("entity_id" to "https://sp.example/saml")
                    }
                }
            }

            test("of an SP lets the EntityID come back without the old assignments") {
                Federation().run {
                    val tenant = tenant()
                    val sp = tenant.register()
                    val user = tenant.user()
                    tenant.acting { assign(sp, listOf(user), ADMIN) }
                    tenant.acting { removeServiceProvider(sp, ADMIN) }

                    val again = tenant.register()

                    tenant.acting { assignedUsers(again) }.shouldBeEmpty()
                }
            }

            test("of a missing SP fails") {
                Federation().run {
                    shouldThrow<ServiceProviderNotFound> { tenant().acting { removeServiceProvider(UUID.randomUUID(), ADMIN) } }
                }
            }

            test("of a user takes their assignments along") {
                Federation().run {
                    val tenant = tenant()
                    val sp = tenant.register()
                    val ada = tenant.user("ada@acme.test")
                    val grace = tenant.user("grace@acme.test")
                    tenant.acting { assign(sp, listOf(ada, grace), ADMIN) }

                    TenantContext.with(tenant) { identity.eraseUser(ada, ADMIN) }

                    tenant.acting { assignedUsers(sp) }.shouldContainExactly(grace)
                }
            }

            test("of a user erased again after a restore takes their assignments along") {
                Federation().run {
                    val tenant = tenant()
                    val sp = tenant.register()
                    val user = tenant.user()
                    tenant.acting { assign(sp, listOf(user), ADMIN) }

                    TenantContext.with(tenant) { identity.eraseAgain(user) } shouldBe true

                    tenant.count("sp_assignments") shouldBe 0
                }
            }
        }
    })
