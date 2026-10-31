package idp.sharedkernel.tenant

import com.github.benmanes.caffeine.cache.Ticker
import idp.sharedkernel.migration.TenantMigrationRunner
import idp.sharedkernel.migration.update
import idp.sharedkernel.testing.TestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

private class ManualTicker : Ticker {
    private val nanos = AtomicLong()

    override fun read(): Long = nanos.get()

    fun advance(duration: Duration) {
        nanos.addAndGet(duration.toNanos())
    }
}

private class Platform {
    val database = TestDatabase.create()
    val owner = database.owner()
    val ticker = ManualTicker()
    val ttl: Duration = Duration.ofSeconds(30)

    init {
        TenantMigrationRunner(owner).migrateAll()
    }

    val resolver = TenantResolver(PlatformTransaction(database.role("app_runtime")), ttl, ticker)

    fun tenant(
        host: String,
        status: String = "ACTIVE",
        kekUri: String? = null,
    ): UUID {
        val id = UUID.randomUUID()
        val kek = kekUri?.let { "'$it'" } ?: "NULL"
        owner.update("INSERT INTO platform.tenants (id, name, status, kek_uri) VALUES ('$id', 'tenant', '$status', $kek)")
        owner.update("INSERT INTO platform.tenant_domains (domain, tenant_id) VALUES ('$host', '$id')")
        return id
    }
}

class TenantResolverTest :
    FunSpec({
        test("a host resolves to its tenant with the KEK URI") {
            Platform().run {
                val id = tenant("acme.idp.test", kekUri = "hcvault://transit/acme")

                resolver.resolve("acme.idp.test") shouldBe
                    ResolvedTenant(id, TenantStatus.ACTIVE, "hcvault://transit/acme")
            }
        }

        test("an unknown host resolves to nothing") {
            Platform().run {
                resolver.resolve("nobody.idp.test").shouldBeNull()
            }
        }

        test("hosts are case-insensitive") {
            Platform().run {
                val id = tenant("acme.idp.test")

                resolver.resolve("ACME.idp.TEST")?.id shouldBe id
            }
        }

        test("a host longer than DNS allows is not looked up") {
            Platform().run {
                val host = "a".repeat(254)
                tenant(host)

                resolver.resolve(host).shouldBeNull()
            }
        }

        test("a status change takes effect within the TTL") {
            Platform().run {
                val id = tenant("acme.idp.test")
                resolver.resolve("acme.idp.test")?.status shouldBe TenantStatus.ACTIVE

                owner.update("UPDATE platform.tenants SET status = 'SUSPENDED' WHERE id = '$id'")

                ticker.advance(ttl.minusSeconds(1))
                resolver.resolve("acme.idp.test")?.status shouldBe TenantStatus.ACTIVE

                ticker.advance(Duration.ofSeconds(1))
                resolver.resolve("acme.idp.test")?.status shouldBe TenantStatus.SUSPENDED
            }
        }

        test("a new tenant appears within the TTL even after a miss") {
            Platform().run {
                resolver.resolve("acme.idp.test").shouldBeNull()
                val id = tenant("acme.idp.test")

                resolver.resolve("acme.idp.test").shouldBeNull()

                ticker.advance(ttl)
                resolver.resolve("acme.idp.test")?.id shouldBe id
            }
        }
    })
