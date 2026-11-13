package idp.sharedkernel.migration

import idp.sharedkernel.testing.TestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import java.util.UUID

private const val BEFORE_BACKFILL = "17"

class PlatformGuardsBackfillMigrationTest :
    FunSpec({
        val owner = TestDatabase.create().owner()
        TenantMigrationRunner(owner).migrateAll()

        fun flyway(
            schema: String,
            target: String? = null,
        ) = Flyway
            .configure(javaClass.classLoader)
            .dataSource(owner)
            .locations("classpath:db/tenant")
            .defaultSchema(schema)
            .schemas(schema)
            .createSchemas(false)
            .apply { target?.let(::target) }
            .load()

        /** A tenant at [subdomain] of a base domain of its own, whose SP at [entityId] and policy were stored before the backfill. */
        fun storedBefore(
            subdomain: String,
            entityId: (String) -> String = { "https://ops.$it/saml2/metadata" },
            passkeyRequired: Boolean = true,
        ): String {
            val id = UUID.randomUUID()
            val base = "b${id.toString().take(8)}.test"
            owner.update("INSERT INTO platform.tenants (id, name, status) VALUES ('$id', 'tenant', 'ACTIVE')")
            owner.update("INSERT INTO platform.tenant_domains (domain, tenant_id) VALUES ('$subdomain.$base', '$id')")
            val schema = TenantSchemaFactory(owner).create(id).name
            flyway(schema, BEFORE_BACKFILL).migrate()
            owner.update(
                """
                INSERT INTO "$schema".service_providers
                    (id, entity_id, name, signing_certificates, authn_requests_signed, name_id_format, signed_part, signature_algorithm,
                     created_at)
                VALUES ('${UUID.randomUUID()}', '${entityId(base)}', 'ops', '{}', false, 'EMAIL', 'ASSERTION', 'RSA_PSS_SHA256', now())
                """,
            )
            owner.update("""UPDATE "$schema".tenant_policies SET passkey_required = $passkeyRequired""")
            flyway(schema).migrate()
            return schema
        }

        fun guards(schema: String): String {
            val managed = owner.query("""SELECT managed::text FROM "$schema".service_providers""")
            return "$managed/" + owner.query("""SELECT passkeys_locked::text FROM "$schema".tenant_policies""")
        }

        test("the SP ops and the passkeys of the service tenant become managed") {
            guards(storedBefore("ops-sso")) shouldBe "true/true"
        }

        test("an SP of another EntityID at the service tenant, and a policy that never required passkeys, stay open") {
            guards(storedBefore("ops-sso", entityId = { "https://crm.example/saml" }, passkeyRequired = false)) shouldBe
                "false/false"
            guards(storedBefore("ops-sso", entityId = { "https://ops.other.test/saml2/metadata" })) shouldBe "false/true"
        }

        test("a customer tenant keeps its SP and policy open, even under the EntityID of ops") {
            guards(storedBefore("acme")) shouldBe "false/false"
        }
    })
