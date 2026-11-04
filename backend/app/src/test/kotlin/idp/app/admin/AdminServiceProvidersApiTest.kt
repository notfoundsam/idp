package idp.app.admin

import idp.app.login.ACME
import idp.app.login.Browser
import idp.app.login.LoginEnvironment
import idp.app.login.MultipartForm
import idp.app.login.Reply
import idp.app.login.TestTenant
import idp.app.login.text
import idp.federation.TestSp
import idp.federation.adapters.saml.MAX_METADATA_BYTES
import idp.federation.application.FederationService
import idp.federation.application.NameIdFormat
import idp.federation.application.SamlAttribute
import idp.federation.application.ServiceProviderView
import idp.federation.application.SignatureAlgorithm
import idp.federation.application.SignedPart
import idp.federation.application.SpSettings
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.audit.AuditTarget
import idp.sharedkernel.security.UserRole
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldBeEmpty
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

private const val SPS = "/api/admin/service-providers"
private const val KEYS = "/api/admin/signing-keys"
private const val ROTATION = "/api/admin/signing-keys/rotation"
private const val COMPLETION = "/api/admin/signing-keys/rotation/completion"
private const val SEARCH = "/api/admin/users/search"
private const val ACCESS_DENIED = "access.denied"
private const val IF_MATCH = "If-Match"
private const val ETAG = "ETag"
private val CERTIFICATE = Regex("<(\\w+:)?X509Certificate>")
private val HEX_PAIRS = HexFormat.ofDelimiter(":").withUpperCase()

private fun fingerprint(certificate: X509Certificate): String =
    HEX_PAIRS.formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.encoded))

private fun spPath(id: Any) = "$SPS/$id"

private fun settings(id: Any) = "$SPS/$id/settings"

private fun users(id: Any) = "$SPS/$id/users"

private fun removal(id: Any) = "$SPS/$id/users/removal"

private fun metadata(entityId: String = "https://${UUID.randomUUID()}.example/saml") = TestSp(entityId).metadata().toByteArray()

/** Valid metadata of exactly [size] bytes: whitespace may follow the root element. */
private fun metadataOf(size: Int): ByteArray {
    val xml = metadata()
    return xml + ByteArray(size - xml.size) { ' '.code.toByte() }
}

class AdminServiceProvidersApiTest :
    FunSpec({
        val environment = LoginEnvironment()
        val application = environment.start()
        val acme = environment.acme
        val globex = environment.globex
        val federation = application.getBean(FederationService::class.java)
        acme.acting { federation.createIdentity(ACME) }

        afterSpec { application.close() }

        fun email(
            name: String,
            tenant: TestTenant = acme,
        ) = "$name@${tenant.host}"

        fun admin(
            name: String,
            tenant: TestTenant = acme,
        ) = environment.signedIn(application, email(name, tenant), tenant)

        fun member(
            name: String,
            tenant: TestTenant = acme,
        ) = environment.signedIn(application, email(name, tenant), tenant, UserRole.USER)

        fun user(
            name: String,
            tenant: TestTenant = acme,
        ) = with(environment) { application.user(email(name, tenant), tenant, password = null) }

        fun audit(
            actor: UUID,
            tenant: TestTenant = acme,
        ) = with(environment) { application.audit(tenant) }.filter { it.actor == Actor.User(actor) }

        fun register(
            name: String = "Wiki",
            tenant: TestTenant = acme,
        ): UUID = tenant.acting { federation.registerServiceProvider(name, metadata(), Actor.System) }

        fun stored(
            id: UUID,
            tenant: TestTenant = acme,
        ): ServiceProviderView? = tenant.acting { federation.serviceProvider(id) }

        fun assigned(
            sp: UUID,
            tenant: TestTenant = acme,
        ): Set<UUID> = tenant.acting { federation.assignedUsers(sp) }.toSet()

        fun Browser.upload(
            name: String,
            content: ByteArray,
            fileName: String = "sp-metadata.xml",
        ) = multipart(SPS, metadataForm(name, content, fileName))

        fun Reply.id(): UUID = UUID.fromString(json().text("id"))

        fun Reply.list(name: String) = json().get(name).values()

        fun Reply.userIds(): Set<UUID> = list("users").map { UUID.fromString(it.text("id")) }.toSet()

        fun Reply.spIds(): List<String> = list("serviceProviders").map { it.text("id") }

        fun Reply.keys() = json().get("keys").values().map { UUID.fromString(it.text("id")) to it.text("status") }

        /** Every call of the Service Providers and the signing keys, each with a body and the headers it would take. */
        fun Browser.everyCall(
            sp: UUID,
            target: UUID,
        ): List<Reply> =
            listOf(
                get(SPS),
                upload("Never", metadata()),
                get(spPath(sp)),
                put(settings(sp), spSettingsBody(nameIdFormat = "EMAIL"), IF_MATCH, "\"0\""),
                delete(spPath(sp)),
                get(users(sp)),
                post(users(sp), usersBody(target)),
                post(removal(sp), usersBody(target)),
                get(KEYS),
                post(ROTATION),
                post(COMPLETION),
            )

        context("who may call") {
            test("an ordinary user is refused every call, nothing changes and each refusal is audited") {
                val (browser, user) = member("sp-ordinary")
                val target = user("sp-ordinary-target")
                val sp = register()
                val before = acme.acting { federation.serviceProviders() }.size

                browser.everyCall(sp, target).forEach { it.status shouldBe 403 }

                acme.acting { federation.serviceProviders() } shouldHaveSize before
                stored(sp).shouldNotBeNull().settings shouldBe SpSettings()
                assigned(sp).shouldBeEmpty()
                acme.acting { federation.signingKeys() } shouldHaveSize 1
                audit(user).filter { it.action == ACCESS_DENIED } shouldHaveSize 11
            }

            test("an admin of another tenant has no session here, and nobody without one gets in") {
                val (theirs, _) = admin("sp-foreign", globex)
                val target = user("sp-foreign-target")
                val sp = register()
                val foreign = Browser(application, acme.host).apply { cookies.putAll(theirs.cookies) }

                foreign.everyCall(sp, target).forEach { it.status shouldBe 401 }
                Browser(application).everyCall(sp, target).forEach { it.status shouldBe 401 }

                stored(sp).shouldNotBeNull()
                assigned(sp).shouldBeEmpty()
                acme.acting { federation.signingKeys() } shouldHaveSize 1
            }

            test("a Service Provider of another tenant reads as missing and stays as it was") {
                val (browser, _) = admin("sp-reaches-out")
                val target = user("sp-reaches-out-target")
                val theirs = register(tenant = globex)
                val theirUser = user("sp-their-user", globex)

                browser.get(spPath(theirs)).status shouldBe 404
                browser.put(settings(theirs), spSettingsBody(nameIdFormat = "EMAIL"), IF_MATCH, "\"0\"").status shouldBe 404
                browser.get(users(theirs)).status shouldBe 404
                browser.post(users(theirs), usersBody(target)).status shouldBe 404
                browser.post(users(theirs), usersBody(theirUser)).status shouldBe 404
                browser.post(removal(theirs), usersBody(theirUser)).status shouldBe 404
                browser.delete(spPath(theirs)).status shouldBe 404
                ("$theirs" in browser.get(SPS).spIds()) shouldBe false

                stored(theirs, globex).shouldNotBeNull().settings shouldBe SpSettings()
                assigned(theirs, globex).shouldBeEmpty()
            }

            test("a user of another tenant reads as missing, and nobody of the list is assigned") {
                val (browser, admin) = admin("sp-mixed-assigner")
                val ours = user("sp-ours")
                val theirs = user("sp-theirs", globex)
                val sp = register()

                browser.post(users(sp), usersBody(ours, theirs)).status shouldBe 404

                assigned(sp).shouldBeEmpty()
                audit(admin).filter { it.action == "sp.user_assigned" }.shouldBeEmpty()
            }

            test("a change without the CSRF token is refused") {
                val (browser, _) = admin("sp-no-csrf")
                val target = user("sp-no-csrf-target")
                val sp = register()

                browser.multipart(SPS, metadataForm("Forged", metadata()), echoCsrf = false).status shouldBe 403
                browser.post(users(sp), usersBody(target), echoCsrf = false).status shouldBe 403

                assigned(sp).shouldBeEmpty()
            }
        }

        context("importing") {
            test("stores the SP the file describes with the default settings, lists it, and is audited") {
                val (browser, admin) = admin("sp-importer")
                val sp = TestSp("https://import-${UUID.randomUUID()}.example/saml")

                val reply = browser.upload("Wiki – Zürich", sp.metadata().toByteArray())

                reply.status shouldBe 201
                val id = reply.id()
                val read = browser.get(spPath(id))
                read.status shouldBe 200
                read.header(ETAG) shouldBe "\"0\""
                read.json().run {
                    text("id") shouldBe "$id"
                    text("entityId") shouldBe sp.entityId
                    text("name") shouldBe "Wiki – Zürich"
                    get("authnRequestsSigned").asBoolean() shouldBe false
                    get("acs").values().map { it.text("url") } shouldBe sp.acs.map { it.location }
                    get("acs").values().map { it.get("isDefault").asBoolean() } shouldBe listOf(true, false)
                    get("signingCertificates").values().single().text("sha256Fingerprint") shouldBe fingerprint(sp.certificate)
                    get("settings").run {
                        text("nameIdFormat") shouldBe "PERSISTENT"
                        text("signedPart") shouldBe "ASSERTION"
                        text("signatureAlgorithm") shouldBe "RSA_PSS_SHA256"
                        get("attributes").values().toList().shouldBeEmpty()
                    }
                }
                browser.get(SPS).spIds() shouldContain "$id"
                audit(admin).single { it.action == "sp.registered" }.run {
                    target shouldBe AuditTarget.serviceProvider(id)
                    details shouldBe mapOf("entity_id" to sp.entityId)
                    ip.shouldNotBeNull()
                }
            }

            test("ignores the file name of the upload: it reaches neither the SP nor the journal") {
                val (browser, admin) = admin("sp-named-file")
                val fileName = "../../evil-upload<script>.xml"

                val reply = browser.upload("Portal", metadata(), fileName)

                reply.status shouldBe 201
                reply.body shouldNotContain "evil-upload"
                val id = reply.id()
                stored(id).shouldNotBeNull().name shouldBe "Portal"
                browser.get(spPath(id)).body shouldNotContain "evil-upload"
                val row =
                    environment.sql.queryForObject(
                        "SELECT row_to_json(sp)::text FROM ${acme.table("service_providers")} sp WHERE id = ?",
                        String::class.java,
                        id,
                    )
                row shouldNotContain "evil-upload"
                val records = audit(admin)
                records.map { it.action } shouldContain "sp.registered"
                records.toString() shouldNotContain "evil-upload"
            }

            test("a file that is not metadata of one SP answers 422 with no body and keeps nothing") {
                val (browser, admin) = admin("sp-hostile")
                val before = acme.acting { federation.serviceProviders() }.size
                val hostile =
                    """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><x>&e;</x>""".toByteArray()

                listOf(hostile, "not xml".toByteArray(), ByteArray(0)).forEach {
                    val reply = browser.upload("Hostile", it)
                    reply.status shouldBe 422
                    reply.body.shouldBeEmpty()
                }
                browser.upload(" ", metadata()).status shouldBe 422

                acme.acting { federation.serviceProviders() } shouldHaveSize before
                audit(admin).filter { it.action == "sp.registered" }.shouldBeEmpty()
            }

            test("a second SP with the same EntityID answers 409") {
                val (browser, admin) = admin("sp-twice")
                val content = metadata()
                browser.upload("First", content).status shouldBe 201

                browser.upload("Second", content).status shouldBe 409

                audit(admin).filter { it.action == "sp.registered" } shouldHaveSize 1
            }

            test("a form without the name or the file, and a body that is no form, are refused") {
                val (browser, _) = admin("sp-sloppy")
                val content = metadata()

                browser.multipart(SPS, MultipartForm().file("metadata", "sp.xml", content)).status shouldBe 400
                browser.multipart(SPS, MultipartForm().field("name", "Fileless")).status shouldBe 400
                browser.multipart(SPS, MultipartForm().field("name", "Inline").field("metadata", String(content))).status shouldBe 400
                browser.post(SPS, """{"name":"Json","metadata":"x"}""").status shouldBe 415
            }

            test("takes a file of the importer's limit, which refuses one byte more, and the request limit refuses a larger one") {
                val (browser, _) = admin("sp-sizes")

                browser.upload("Largest", metadataOf(MAX_METADATA_BYTES)).status shouldBe 201
                browser.upload("Too large", metadataOf(MAX_METADATA_BYTES + 1)).status shouldBe 422
                browser.upload("Far too large", metadataOf(170_000)).status shouldBe 413
                browser.post(SEARCH, searchBody("x".repeat(40_000))).status shouldBe 413
            }
        }

        context("the settings") {
            test("are saved with the version read, answer the next one and are audited with what they release") {
                val (browser, admin) = admin("sp-configurer")
                val id = register()
                val etag = browser.get(spPath(id)).header(ETAG).shouldNotBeNull()

                val saved =
                    browser.put(
                        settings(id),
                        spSettingsBody("EMAIL", "BOTH", "RSA_SHA256", listOf("PHONE", "EMAIL")),
                        IF_MATCH,
                        etag,
                    )

                saved.status shouldBe 204
                saved.header(ETAG) shouldBe "\"1\""
                stored(id).shouldNotBeNull().settings shouldBe
                    SpSettings(
                        NameIdFormat.EMAIL,
                        SignedPart.BOTH,
                        SignatureAlgorithm.RSA_SHA256,
                        setOf(SamlAttribute.EMAIL, SamlAttribute.PHONE),
                    )
                browser.get(spPath(id)).run {
                    header(ETAG) shouldBe "\"1\""
                    val attributes = json().get("settings").get("attributes")
                    attributes.values().map { it.asString() } shouldBe listOf("EMAIL", "PHONE")
                }
                audit(admin).single { it.action == "sp.settings_changed" }.run {
                    target shouldBe AuditTarget.serviceProvider(id)
                    details shouldBe
                        mapOf(
                            "name_id_format" to "EMAIL",
                            "signed_part" to "BOTH",
                            "signature_algorithm" to "RSA_SHA256",
                            "attributes" to "EMAIL,PHONE",
                        )
                }
            }

            test("saved with a version no longer current answer 412, and without one 428") {
                val (browser, admin) = admin("sp-stale")
                val id = register()
                browser.put(settings(id), spSettingsBody(nameIdFormat = "EMAIL"), IF_MATCH, "\"0\"").status shouldBe 204

                browser.put(settings(id), spSettingsBody(signedPart = "RESPONSE"), IF_MATCH, "\"0\"").status shouldBe 412
                browser.put(settings(id), spSettingsBody(signedPart = "RESPONSE"), IF_MATCH, "*").status shouldBe 412
                browser.put(settings(id), spSettingsBody(signedPart = "RESPONSE")).status shouldBe 428

                stored(id).shouldNotBeNull().settings shouldBe SpSettings(NameIdFormat.EMAIL)
                audit(admin).filter { it.action == "sp.settings_changed" } shouldHaveSize 1
            }

            test("an unknown value or field, a missing one and a null attribute answer 400") {
                val (browser, _) = admin("sp-sloppy-configurer")
                val id = register()

                listOf(
                    spSettingsBody(signatureAlgorithm = "RSA_SHA1"),
                    spSettingsBody(nameIdFormat = "TRANSIENT"),
                    spSettingsBody(attributes = listOf("SSN")),
                    """{"nameIdFormat":"EMAIL","signedPart":"BOTH","signatureAlgorithm":"RSA_SHA256"}""",
                    """{"nameIdFormat":"EMAIL","signedPart":"BOTH","signatureAlgorithm":"RSA_SHA256","attributes":[null]}""",
                    spSettingsBody().dropLast(1) + ""","acsUrl":"https://evil.example/acs"}""",
                ).forEach { browser.put(settings(id), it, IF_MATCH, "\"0\"").status shouldBe 400 }

                stored(id).shouldNotBeNull().version shouldBe 0
            }

            test("need a fresh login, and the refusal is audited") {
                val (browser, admin) = admin("sp-aged-configurer")
                val id = register()
                environment.age(admin)

                val reply = browser.put(settings(id), spSettingsBody(attributes = listOf("PHONE")), IF_MATCH, "\"0\"")

                reply.status shouldBe 403
                reply.json().text("error") shouldBe "reauthentication_required"
                stored(id).shouldNotBeNull().settings shouldBe SpSettings()
                audit(admin).map { it.action } shouldContain "access.reauthentication_required"
            }

            test("of an unknown SP answer 404, and a malformed ID 400") {
                val (browser, _) = admin("sp-vague-configurer")

                browser.get(spPath(UUID.randomUUID())).status shouldBe 404
                browser.put(settings(UUID.randomUUID()), spSettingsBody(), IF_MATCH, "\"0\"").status shouldBe 404
                browser.get(spPath("not-an-id")).status shouldBe 400
            }
        }

        context("removal") {
            test("takes the SP and its assignments away, and is audited") {
                val (browser, admin) = admin("sp-remover")
                val target = user("sp-removed-user")
                val id = register()
                browser.post(users(id), usersBody(target)).status shouldBe 204

                browser.delete(spPath(id)).status shouldBe 204

                stored(id).shouldBeNull()
                acme.acting { federation.assignmentsOf(target) }.shouldBeEmpty()
                browser.get(spPath(id)).status shouldBe 404
                browser.delete(spPath(id)).status shouldBe 404
                audit(admin).single { it.action == "sp.removed" }.target shouldBe AuditTarget.serviceProvider(id)
            }

            test("needs a fresh login, and the refusal is audited") {
                val (browser, admin) = admin("sp-aged-remover")
                val id = register()
                environment.age(admin)

                val reply = browser.delete(spPath(id))

                reply.status shouldBe 403
                reply.json().text("error") shouldBe "reauthentication_required"
                stored(id).shouldNotBeNull()
                audit(admin).map { it.action } shouldContain "access.reauthentication_required"
            }
        }

        context("assignments") {
            test("a list is assigned in one call, each user once and audited once, and a repeat changes nothing") {
                val (browser, admin) = admin("sp-assigner")
                val first = user("sp-assigned-first")
                val second = user("sp-assigned-second")
                val id = register()

                browser.post(users(id), usersBody(first, second, first)).status shouldBe 204
                browser.post(users(id), usersBody(first, second)).status shouldBe 204

                assigned(id) shouldBe setOf(first, second)
                val records = audit(admin).filter { it.action == "sp.user_assigned" }
                records shouldHaveSize 2
                records.map { it.target }.toSet() shouldBe setOf(AuditTarget.user(first), AuditTarget.user(second))
                records.forEach { it.details shouldBe mapOf("sp_id" to "$id") }
            }

            test("the users of an SP come from one query, by ID, role and status") {
                val (browser, admin) = admin("sp-lister")
                val first = user("sp-listed-first")
                val second = user("sp-listed-second")
                user("sp-not-listed")
                val id = register()
                browser.post(users(id), usersBody(first, second, admin)).status shouldBe 204

                val (reply, messages) = messagesDuring { browser.get(users(id)) }

                reply.status shouldBe 200
                messages.single() shouldContain "message=idp.audience.admin.application.query.GetAssignedUsers outcome=OK"
                reply.userIds() shouldBe setOf(first, second, admin)
                reply.json().get("users").values().single { it.text("id") == "$admin" }.run {
                    text("role") shouldBe "TENANT_ADMIN"
                    text("status") shouldBe "ACTIVE"
                    propertyNames().toSet() shouldBe setOf("id", "role", "status")
                }
                reply.body shouldNotContain email("sp-listed-first")
                browser.get(users(UUID.randomUUID())).status shouldBe 404
            }

            test("a list is revoked in one call, a user never assigned changes nothing, and each revocation is audited") {
                val (browser, admin) = admin("sp-revoker")
                val first = user("sp-revoked-first")
                val second = user("sp-revoked-second")
                val kept = user("sp-kept")
                val never = user("sp-never-assigned")
                val id = register()
                browser.post(users(id), usersBody(first, second, kept)).status shouldBe 204

                browser.post(removal(id), usersBody(first, second, never)).status shouldBe 204
                browser.post(removal(id), usersBody(first, second)).status shouldBe 204

                assigned(id) shouldBe setOf(kept)
                browser.get(users(id)).userIds() shouldBe setOf(kept)
                acme.acting { federation.isAssigned(id, first) } shouldBe false
                val records = audit(admin).filter { it.action == "sp.user_unassigned" }
                records.map { it.target }.toSet() shouldBe setOf(AuditTarget.user(first), AuditTarget.user(second))
                records shouldHaveSize 2
            }

            test("an unknown user leaves everyone of the list unassigned, and an unknown SP answers 404") {
                val (browser, _) = admin("sp-unknown-assigner")
                val known = user("sp-known")
                val id = register()

                browser.post(users(id), usersBody(known, UUID.randomUUID())).status shouldBe 404
                browser.post(users(UUID.randomUUID()), usersBody(known)).status shouldBe 404
                browser.post(removal(UUID.randomUUID()), usersBody(known)).status shouldBe 404

                assigned(id).shouldBeEmpty()
            }

            test("an empty list, one over 500, a null and a malformed ID answer 400") {
                val (browser, _) = admin("sp-sloppy-assigner")
                val known = user("sp-sloppy-known")
                val id = register()

                listOf(
                    usersBody(),
                    usersBody(*Array(501) { UUID.randomUUID() }),
                    usersBody(known, null),
                    usersBody(known, "not-an-id"),
                    """{"userIds":["$known"],"role":"TENANT_ADMIN"}""",
                    "{}",
                ).forEach {
                    browser.post(users(id), it).status shouldBe 400
                    browser.post(removal(id), it).status shouldBe 400
                }

                assigned(id).shouldBeEmpty()
            }

            test("500 IDs fit in one call") {
                val (browser, _) = admin("sp-bulk-revoker")
                val id = register()

                browser.post(removal(id), usersBody(*Array(500) { UUID.randomUUID() })).status shouldBe 204
            }
        }

        context("the signing key") {
            test("rotates in two steps: the next key is published first, then signs alone, and both steps are audited") {
                val (browser, admin) = admin("key-rotator")
                val active = browser.get(KEYS).keys().single()
                active.second shouldBe "ACTIVE"

                val started = browser.post(ROTATION)

                started.status shouldBe 201
                val next = started.id()
                browser.get(KEYS).keys() shouldBe listOf(active, next to "NEXT")
                CERTIFICATE.findAll(Browser(application).get("/saml/metadata").body).count() shouldBe 2
                browser.post(ROTATION).status shouldBe 409

                browser.post(COMPLETION).status shouldBe 204

                browser.get(KEYS).keys() shouldBe listOf(next to "ACTIVE")
                acme.acting { federation.signingKeys() }.single().id shouldBe next
                browser.post(COMPLETION).status shouldBe 409
                val records = audit(admin)
                records.single { it.action == "saml.key_rotation_started" }.target shouldBe AuditTarget.signingKey(next)
                records.single { it.action == "saml.key_rotation_completed" }.target shouldBe AuditTarget.signingKey(next)
            }

            test("shows the fingerprint and the expiry of each key") {
                val (browser, _) = admin("key-reader")

                val key = browser.get(KEYS).list("keys").single()

                val certificate = acme.acting { federation.signingKeys() }.single().certificate
                key.text("sha256Fingerprint") shouldBe fingerprint(certificate)
                Instant.parse(key.text("notAfter")) shouldBe certificate.notAfter.toInstant()
                key.propertyNames().toSet() shouldBe setOf("id", "status", "sha256Fingerprint", "notAfter", "createdAt")
            }

            test("completing needs a fresh login, and the refusal is audited") {
                val (browser, admin) = admin("key-aged-rotator")
                browser.post(ROTATION).status shouldBe 201
                environment.age(admin)

                try {
                    val reply = browser.post(COMPLETION)

                    reply.status shouldBe 403
                    reply.json().text("error") shouldBe "reauthentication_required"
                    acme.acting { federation.signingKeys() } shouldHaveSize 2
                    audit(admin).map { it.action } shouldContain "access.reauthentication_required"
                } finally {
                    acme.acting { federation.completeKeyRotation(Actor.System) }
                }
            }
        }

        context("the journal") {
            test("records every change an admin makes to Service Providers, with the admin and the address of the request") {
                val (browser, admin) = admin("sp-audited")
                val target = user("sp-audited-user")

                val id = browser.upload("Audited", metadata()).id()
                browser.put(settings(id), spSettingsBody(nameIdFormat = "EMAIL"), IF_MATCH, "\"0\"").status shouldBe 204
                browser.post(users(id), usersBody(target)).status shouldBe 204
                browser.post(removal(id), usersBody(target)).status shouldBe 204
                browser.delete(spPath(id)).status shouldBe 204

                val records = audit(admin).filter { it.action.startsWith("sp.") }.reversed()
                records.map { it.action } shouldBe
                    listOf("sp.registered", "sp.settings_changed", "sp.user_assigned", "sp.user_unassigned", "sp.removed")
                records.map { it.target } shouldBe
                    listOf(
                        AuditTarget.serviceProvider(id),
                        AuditTarget.serviceProvider(id),
                        AuditTarget.user(target),
                        AuditTarget.user(target),
                        AuditTarget.serviceProvider(id),
                    )
                records.forEach { it.ip.shouldNotBeNull() }
            }
        }
    })
