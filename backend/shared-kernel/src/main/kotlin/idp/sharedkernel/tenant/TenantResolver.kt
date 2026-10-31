package idp.sharedkernel.tenant

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Ticker
import idp.db.jooq.platform.tables.TenantDomains.TENANT_DOMAINS
import idp.db.jooq.platform.tables.Tenants.TENANTS
import java.time.Duration
import java.util.Optional
import java.util.UUID

private const val MAX_HOST_LENGTH = 253
private const val MAX_CACHED_HOSTS = 10_000L

enum class TenantStatus {
    REQUESTED,
    APPROVED,
    PROVISIONING,
    ACTIVE,
    SUSPENDED,
    OFFBOARDING,
    DELETED,
}

data class ResolvedTenant(
    val id: UUID,
    val status: TenantStatus,
    val kekUri: String?,
) {
    fun context() = TenantContext(id, kekUri)
}

/** The TTL is the longest a suspended tenant keeps working, and misses are cached so a host flood cannot reach the database. */
class TenantResolver(
    private val platform: PlatformTransaction,
    ttl: Duration,
    ticker: Ticker = Ticker.systemTicker(),
) {
    private val cache =
        Caffeine
            .newBuilder()
            .maximumSize(MAX_CACHED_HOSTS)
            .expireAfterWrite(ttl)
            .ticker(ticker)
            .build<String, Optional<ResolvedTenant>>()

    fun resolve(host: String): ResolvedTenant? {
        if (host.length > MAX_HOST_LENGTH) return null
        return cache.get(host.lowercase()) { Optional.ofNullable(load(it)) }.orElse(null)
    }

    private fun load(host: String): ResolvedTenant? =
        platform.execute { dsl ->
            dsl
                .select(TENANTS.ID, TENANTS.STATUS, TENANTS.KEK_URI)
                .from(TENANT_DOMAINS)
                .join(TENANTS)
                .on(TENANTS.ID.eq(TENANT_DOMAINS.TENANT_ID))
                .where(TENANT_DOMAINS.DOMAIN.eq(host))
                .fetchOne { ResolvedTenant(it.value1(), TenantStatus.valueOf(it.value2()), it.value3()) }
        }
}
