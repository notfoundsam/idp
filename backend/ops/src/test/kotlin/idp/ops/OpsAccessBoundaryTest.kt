package idp.ops

import idp.sharedkernel.crypto.KeyManagementService
import idp.sharedkernel.crypto.KeyManagementUnavailableException
import idp.sharedkernel.migration.TenantMigrationRunner
import idp.sharedkernel.migration.TenantSchemaFactory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.postgresql.util.PSQLException
import org.springframework.boot.runApplication
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.SQLException
import java.util.UUID

/** A typo in a table name fails as bad SQL too, so only this state proves that a right is missing. */
private const val INSUFFICIENT_PRIVILEGE = "42501"

class OpsAccessBoundaryTest :
    FunSpec({
        val environment = OpsEnvironment()
        val runtime = JdbcTemplate(environment.database.role("ops_runtime"))
        val application = runApplication<OpsApplication>(*environment.localArguments)
        val api = OpsClient(application.environment.getProperty("local.server.port"))

        afterSpec { application.close() }

        fun denied(block: () -> Unit) {
            (shouldThrow<DataAccessException>(block).cause as SQLException).sqlState shouldBe INSUFFICIENT_PRIVILEGE
        }

        context("tenant schemas") {
            val tenant = UUID.randomUUID()
            environment.sql.update("INSERT INTO platform.tenants (id, name, status) VALUES (?, 'tenant', 'PROVISIONING')", tenant)
            val schema = TenantSchemaFactory(environment.owner).create(tenant).name
            TenantMigrationRunner(environment.owner).migrateTenant(tenant)

            test("ops_runtime cannot read a table of a tenant") {
                denied { runtime.queryForList("""SELECT * FROM "$schema".users""") }
            }

            test("ops_runtime cannot read the wrapped keys of a tenant") {
                denied { runtime.queryForList("""SELECT * FROM "$schema".tenant_keys""") }
            }

            test("ops_runtime cannot take the role of a tenant") {
                shouldThrow<PSQLException> {
                    environment.database.role("ops_runtime").connection.use {
                        it.createStatement().use { statement -> statement.execute("""SET ROLE "$schema"""") }
                    }
                }.message shouldContain "permission denied"
            }

            test("the service runs as ops_runtime") {
                application.getBean(javax.sql.DataSource::class.java).connection.use {
                    it.metaData.userName shouldBe "ops_runtime"
                }
            }
        }

        context("KMS") {
            test("ops wraps with the platform KEK and cannot unwrap, while worker can") {
                val kms = application.getBean(KeyManagementService::class.java)

                val wrapped = kms.wrap(environment.platformKekUri, "secret".toByteArray())

                shouldThrow<KeyManagementUnavailableException> { kms.unwrap(environment.platformKekUri, wrapped) }
                environment.root.unwrap(environment.platformKekUri, wrapped).decodeToString() shouldBe "secret"
            }
        }

        context("contact") {
            test("ops_runtime reads the mask of an application") {
                val id = api.submit("mask")

                runtime.queryForObject(
                    "SELECT contact_mask FROM platform.tenant_applications WHERE id = ?",
                    String::class.java,
                    id,
                ) shouldBe MASK
            }

            test("ops_runtime cannot read the ciphertext") {
                denied { runtime.queryForList("SELECT contact_ciphertext FROM platform.tenant_applications") }
                denied { runtime.queryForList("SELECT * FROM platform.tenant_applications") }
            }

            test("ops_runtime cannot overwrite the ciphertext or the mask") {
                denied { runtime.update("UPDATE platform.tenant_applications SET contact_ciphertext = NULL") }
                denied { runtime.update("UPDATE platform.tenant_applications SET contact_mask = NULL") }
            }

            test("the API answers with the mask and never with the contact") {
                val id = api.submit("answer")

                val response = api.get("/api/applications/$id")

                response.status shouldBe 200
                response.json().get("contactMask").asString() shouldBe MASK
                response.body shouldNotContain CONTACT
                response.body shouldNotContain "ciphertext"
            }

            test("the answer to submitting carries only the id") {
                val response =
                    api.post("/api/applications", """{"companyName":"Acme","subdomain":"created","contact":"$CONTACT"}""")

                response.json().propertyNames().toList() shouldBe listOf("id")
            }

            test("a tenant answer holds neither the contact nor the location of its KEK") {
                val tenant = api.tenantOf(api.submit("tenant"))

                val response = api.get("/api/tenants/$tenant")

                response.status shouldBe 200
                response.body shouldNotContain CONTACT
                response.body shouldNotContain "hcvault"
                response.body.lowercase() shouldNotContain "kek"
            }

            test("the audit keeps no contact") {
                api.submit("audit")

                environment.audit().toString() shouldNotContain CONTACT
                environment.audit().toString() shouldNotContain MASK
            }
        }
    })
