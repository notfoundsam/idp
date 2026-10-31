package idp.sharedkernel.persistence

import idp.db.jooq.platform.tables.Tenants.TENANTS
import idp.sharedkernel.migration.TenantMigrationRunner
import idp.sharedkernel.migration.query
import idp.sharedkernel.migration.update
import idp.sharedkernel.tenant.PlatformTransaction
import idp.sharedkernel.testing.TestDatabase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.util.UUID

private class Row {
    val database = TestDatabase.create()
    val owner = database.owner()
    val id: UUID = UUID.randomUUID()
    val transaction: PlatformTransaction
    val versioned = VersionedUpdate(TENANTS, TENANTS.ID, TENANTS.VERSION)

    init {
        TenantMigrationRunner(owner).migrateAll()
        owner.update("INSERT INTO platform.tenants (id, name, status) VALUES ('$id', 'first', 'ACTIVE')")
        transaction = PlatformTransaction(database.pooled("ops_runtime"))
    }

    fun read(column: String) = owner.query("SELECT $column FROM platform.tenants WHERE id = '$id'")

    fun rename(
        name: String,
        expected: Long,
    ): Long = transaction.execute { dsl -> versioned.execute(dsl, id, expected, mapOf(TENANTS.NAME to name)) }
}

class VersionedUpdateTest :
    FunSpec({
        test("a matching version applies the change and returns the next version") {
            Row().run {
                rename("second", expected = 0) shouldBe 1

                read("name") shouldBe "second"
                read("version") shouldBe "1"
            }
        }

        test("a change with no columns still moves the version") {
            Row().run {
                transaction.execute { dsl -> versioned.execute(dsl, id, 0, emptyMap()) } shouldBe 1

                read("version") shouldBe "1"
            }
        }

        test("a stale version is a conflict and changes nothing") {
            Row().run {
                rename("second", expected = 0)

                val conflict = shouldThrow<VersionConflictException> { rename("third", expected = 0) }

                conflict.message shouldContain "tenants"
                read("name") shouldBe "second"
                read("version") shouldBe "1"
            }
        }

        test("a missing row is a conflict") {
            Row().run {
                shouldThrow<VersionConflictException> {
                    transaction.execute { dsl -> versioned.execute(dsl, UUID.randomUUID(), 0, mapOf(TENANTS.NAME to "ghost")) }
                }
            }
        }

        test("a conflict rolls back the whole transaction") {
            Row().run {
                val other = UUID.randomUUID()
                owner.update("INSERT INTO platform.tenants (id, name, status) VALUES ('$other', 'other', 'ACTIVE')")

                shouldThrow<VersionConflictException> {
                    transaction.execute { dsl ->
                        versioned.execute(dsl, other, 0, mapOf(TENANTS.NAME to "changed"))
                        versioned.execute(dsl, id, 7, mapOf(TENANTS.NAME to "stale"))
                    }
                }

                owner.query("SELECT name FROM platform.tenants WHERE id = '$other'") shouldBe "other"
                owner.query("SELECT version FROM platform.tenants WHERE id = '$other'") shouldBe "0"
            }
        }

        test("a null value clears the column") {
            Row().run {
                owner.update("UPDATE platform.tenants SET kek_uri = 'hcvault://key' WHERE id = '$id'")

                transaction.execute { dsl -> versioned.execute(dsl, id, 0, mapOf(TENANTS.KEK_URI to null)) }

                read("kek_uri IS NULL") shouldBe "t"
            }
        }
    })
