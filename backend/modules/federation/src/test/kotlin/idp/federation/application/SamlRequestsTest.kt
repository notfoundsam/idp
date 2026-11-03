package idp.federation.application

import idp.federation.ADMIN
import idp.federation.Federation
import idp.federation.SP_ACS
import idp.federation.authnRequest
import idp.federation.spMetadata
import idp.sharedkernel.crypto.FieldDecryptionException
import idp.sharedkernel.tenant.TenantContext
import idp.sharedkernel.throttle.TooManyRequestsException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

private const val RELAY_STATE = "https://sp.example/wiki/secret-page"

private fun verified(
    spId: UUID,
    requestId: String = "_${UUID.randomUUID()}",
    relayState: String? = RELAY_STATE,
    idValidUntil: Instant = Instant.now().plus(Duration.ofMinutes(6)),
) = VerifiedRequest(spId, requestId, SP_ACS, authnRequest(id = requestId).toByteArray(), relayState, idValidUntil)

private fun Federation.rows(
    tenant: TenantContext,
    table: String,
): Int = tenant.count(table)!!

class SamlRequestsTest :
    FunSpec({
        context("an accepted request") {
            test("waits for the browser that comes back first, with its XML and RelayState") {
                Federation().run {
                    val tenant = tenant()
                    val spId = tenant.register()
                    val request = verified(spId)

                    val pending = tenant.acting { claimRequest(acceptRequest(request), "browser-secret") }.shouldNotBeNull()

                    pending.spId shouldBe spId
                    pending.acsUrl shouldBe SP_ACS
                    String(pending.xml) shouldBe String(request.xml)
                    pending.relayState shouldBe RELAY_STATE
                }
            }

            test("is claimed once: another browser, or the same one again, finds nothing") {
                Federation().run {
                    val tenant = tenant()
                    val id = tenant.acting { acceptRequest(verified(tenant.register())) }

                    tenant.acting { claimRequest(id, "first") }.shouldNotBeNull()

                    tenant.acting { claimRequest(id, "second") }.shouldBeNull()
                    tenant.acting { claimRequest(id, "first") }.shouldBeNull()
                }
            }

            test("expires with the flow it would start") {
                Federation().run {
                    val tenant = tenant()
                    val id = tenant.acting { acceptRequest(verified(tenant.register())) }
                    clock.offset = Duration.ofMinutes(16)

                    tenant.acting { claimRequest(id, "browser") }.shouldBeNull()
                }
            }

            test("is gone once discarded") {
                Federation().run {
                    val tenant = tenant()
                    val id = tenant.acting { acceptRequest(verified(tenant.register())) }

                    tenant.acting { discardRequest(id) }

                    tenant.acting { claimRequest(id, "browser") }.shouldBeNull()
                }
            }

            test("binds the browser by a hash of its secret only") {
                Federation().run {
                    val tenant = tenant()
                    val id = tenant.acting { acceptRequest(verified(tenant.register())) }
                    tenant.acting { claimRequest(id, "browser-secret") }

                    val stored = sql.queryForObject("SELECT browser_hash FROM ${tenant.table("saml_requests")}", ByteArray::class.java)!!

                    String(stored) shouldNotBe "browser-secret"
                    stored.size shouldBe 32
                }
            }

            test("of another tenant is not found") {
                Federation().run {
                    val acme = tenant()
                    val globex = tenant("globex.idp.test")
                    val id = acme.acting { acceptRequest(verified(acme.register())) }

                    globex.acting { claimRequest(id, "browser") }.shouldBeNull()
                    acme.acting { claimRequest(id, "browser") }.shouldNotBeNull()
                }
            }

            test("goes with its SP") {
                Federation().run {
                    val tenant = tenant()
                    val spId = tenant.register()
                    tenant.acting { acceptRequest(verified(spId)) }

                    tenant.acting { removeServiceProvider(spId, ADMIN) }

                    rows(tenant, "saml_requests") shouldBe 0
                    rows(tenant, "saml_request_ids") shouldBe 0
                }
            }
        }

        context("the stored request and RelayState") {
            test("cannot be read in the table") {
                Federation().run {
                    val tenant = tenant()
                    val requestId = "_readable-${UUID.randomUUID()}"
                    tenant.acting { acceptRequest(verified(tenant.register(), requestId)) }

                    val row =
                        sql.queryForMap("SELECT request_ciphertext, relay_state_ciphertext FROM ${tenant.table("saml_requests")}")
                    val stored = row.values.joinToString { String(it as ByteArray, Charsets.ISO_8859_1) }
                    val ids = sql.queryForList("SELECT id_hash FROM ${tenant.table("saml_request_ids")}", ByteArray::class.java)

                    listOf(requestId, "https://sp.example/saml", "AuthnRequest", "secret-page").forEach { plain ->
                        stored.contains(plain) shouldBe false
                        ids.any { String(it!!, Charsets.ISO_8859_1).contains(plain) } shouldBe false
                    }
                }
            }

            test("does not open as another row: the row ID is bound into the ciphertext") {
                Federation().run {
                    val tenant = tenant()
                    val spId = tenant.register()
                    val first = tenant.acting { acceptRequest(verified(spId)) }
                    val second = tenant.acting { acceptRequest(verified(spId)) }
                    sql.update(
                        "UPDATE ${tenant.table("saml_requests")} SET request_ciphertext = " +
                            "(SELECT request_ciphertext FROM ${tenant.table("saml_requests")} WHERE id = ?) WHERE id = ?",
                        first,
                        second,
                    )

                    shouldThrow<FieldDecryptionException> { tenant.acting { claimRequest(second, "browser") } }
                }
            }
        }

        context("a replay") {
            test("of an ID the SP sent before is refused") {
                Federation().run {
                    val tenant = tenant()
                    val spId = tenant.register()
                    tenant.acting { acceptRequest(verified(spId, "_once")) }

                    shouldThrow<RequestReplayed> { tenant.acting { acceptRequest(verified(spId, "_once")) } }
                    rows(tenant, "saml_requests") shouldBe 1
                }
            }

            test("is told by the SP: another SP may use the same ID") {
                Federation().run {
                    val tenant = tenant()
                    val wiki = tenant.register()
                    val crm = tenant.register(spMetadata(entityId = "https://crm.example/saml"), "CRM")
                    tenant.acting { acceptRequest(verified(wiki, "_shared")) }

                    tenant.acting { acceptRequest(verified(crm, "_shared")) }

                    rows(tenant, "saml_requests") shouldBe 2
                }
            }

            test("is refused even after the request itself was claimed") {
                Federation().run {
                    val tenant = tenant()
                    val spId = tenant.register()
                    tenant.acting { claimRequest(acceptRequest(verified(spId, "_claimed")), "browser") }

                    shouldThrow<RequestReplayed> { tenant.acting { acceptRequest(verified(spId, "_claimed")) } }
                }
            }
        }

        context("the cleanup") {
            test("removes requests and IDs past their time and keeps the rest") {
                Federation().run {
                    val tenant = tenant()
                    val spId = tenant.register()
                    tenant.acting { acceptRequest(verified(spId, "_old", idValidUntil = Instant.now().plus(Duration.ofMinutes(1)))) }
                    clock.offset = Duration.ofMinutes(10)
                    tenant.acting { acceptRequest(verified(spId, "_new", idValidUntil = clock.instant().plus(Duration.ofMinutes(6)))) }
                    clock.offset = Duration.ofMinutes(20)

                    TenantContext.with(tenant) {
                        transaction.execute { service.requestCleanup.delete(it, clock.instant().atOffset(ZoneOffset.UTC)) }
                    }

                    rows(tenant, "saml_requests") shouldBe 1
                    rows(tenant, "saml_request_ids") shouldBe 0
                    service.requestCleanup.name shouldBe "saml-requests"
                }
            }
        }

        context("the limit on open requests") {
            test("refuses beyond it, as an anonymous caller can send them") {
                Federation(maxOpenRequests = 1).run {
                    val tenant = tenant()
                    val spId = tenant.register()
                    tenant.acting { acceptRequest(verified(spId)) }

                    shouldThrow<TooManyRequestsException> { tenant.acting { acceptRequest(verified(spId)) } }
                    rows(tenant, "saml_request_ids") shouldBe 1
                }
            }
        }
    })
