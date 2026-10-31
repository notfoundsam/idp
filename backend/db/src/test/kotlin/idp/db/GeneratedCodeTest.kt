package idp.db

import idp.db.jooq.platform.tables.Tenants.TENANTS
import idp.db.jooq.tenant.tables.ProcessedEvents.PROCESSED_EVENTS
import idp.db.jooq.tenant.tables.TenantKeys.TENANT_KEYS
import idp.db.jooq.tenant.tables.UserKeys.USER_KEYS
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.jooq.SQLDialect
import org.jooq.impl.DSL

class GeneratedCodeTest :
    FunSpec({
        test("platform tables are always qualified") {
            DSL.using(SQLDialect.POSTGRES).render(DSL.selectFrom(TENANTS)) shouldContain """"platform"."tenants""""
        }

        test("tenant tables render without a schema") {
            listOf(TENANT_KEYS, USER_KEYS, PROCESSED_EVENTS).forEach { table ->
                val sql = DSL.using(SQLDialect.POSTGRES).render(DSL.selectFrom(table))

                sql shouldContain table.name
                sql shouldNotContain "tenant_template"
            }
        }

        test("Tables.TENANTS does not exist, so the table owner rule sees every access") {
            runCatching { Class.forName("idp.db.jooq.platform.Tables") }.isFailure shouldBe true
        }

        test("the migration history is not generated") {
            runCatching { Class.forName("idp.db.jooq.platform.tables.FlywaySchemaHistory") }.isFailure shouldBe true
        }
    })
