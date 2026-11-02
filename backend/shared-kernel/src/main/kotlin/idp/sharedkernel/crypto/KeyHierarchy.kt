package idp.sharedkernel.crypto

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Ticker
import com.google.crypto.tink.Aead
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig2026
import com.google.crypto.tink.aead.AesGcmKey
import com.google.crypto.tink.aead.PredefinedAeadParameters
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.util.SecretBytes
import idp.db.jooq.platform.tables.Tenants.TENANTS
import idp.db.jooq.tenant.tables.TenantKeys.TENANT_KEYS
import idp.db.jooq.tenant.tables.UserKeys.USER_KEYS
import idp.sharedkernel.tenant.PlatformTransaction
import idp.sharedkernel.tenant.TenantContext
import idp.sharedkernel.tenant.TenantTransaction
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

private const val TENANT_KEY_VERSION = 1
private const val KEY_SIZE = 32
private const val KEY_INFO = "idp/v1/"
private const val HKDF_MAC = "HMACSHA256"
private const val USER_KEYS_PURPOSE = "user-keys"
private const val BLIND_INDEX_PURPOSE = "blind-index"
private const val MAX_CACHED_TENANTS = 10_000L

enum class DerivedKey(
    internal val purpose: String,
) {
    FLOW("flow"),
    AUDIT("audit"),
}

class UserKeyNotFoundException(
    userId: UUID,
) : RuntimeException("No key for user $userId")

/** Every use takes its own HKDF output, so the tenant key itself encrypts nothing. */
private class TenantKey(
    private val material: ByteArray,
    private val tenantId: UUID,
) {
    fun derive(purpose: String): ByteArray =
        Hkdf.computeHkdf(HKDF_MAC, material, null, "$KEY_INFO$purpose/$tenantId".toByteArray(), KEY_SIZE)

    fun keyset(purpose: String): KeysetHandle {
        val key =
            AesGcmKey
                .builder()
                .setParameters(PredefinedAeadParameters.AES256_GCM)
                .setKeyBytes(SecretBytes.copyFrom(derive(purpose), InsecureSecretKeyAccess.get()))
                .setIdRequirement(TENANT_KEY_VERSION)
                .build()
        return KeysetHandle.newBuilder().addEntry(KeysetHandle.importKey(key).makePrimary()).build()
    }

    val userKeys: Aead get() = keyset(USER_KEYS_PURPOSE).getPrimitive(AeadConfig2026.get(), Aead::class.java)

    fun userKeyContext(userId: UUID): ByteArray = "$USER_KEYS_PURPOSE/$tenantId/$userId".toByteArray()
}

/**
 * KEK in KMS → tenant key → user keys and derived keys. The TTL is how long a destroyed KEK keeps working. [platform] names the
 * KEKs of a tenant when the URI in its context is out of date.
 */
class KeyHierarchy(
    private val kms: KeyManagementService,
    private val transaction: TenantTransaction,
    private val platform: PlatformTransaction,
    ttl: Duration,
    ticker: Ticker = Ticker.systemTicker(),
) {
    private val random = SecureRandom()
    private val tenantKeys =
        Caffeine
            .newBuilder()
            .maximumSize(MAX_CACHED_TENANTS)
            .expireAfterWrite(ttl)
            .ticker(ticker)
            .buildAsync<UUID, TenantKey>()

    /** A repeated provisioning step keeps the first key, because a second one would orphan everything under it. */
    fun createTenantKey() {
        val material = ByteArray(KEY_SIZE).also(random::nextBytes)
        val wrapped = kms.wrap(kekUri(TenantContext.current()), material)
        transaction.execute { dsl ->
            dsl
                .insertInto(TENANT_KEYS)
                .set(TENANT_KEYS.VERSION, TENANT_KEY_VERSION)
                .set(TENANT_KEYS.WRAPPED_KEY, wrapped)
                .onConflictDoNothing()
                .execute()
        }
    }

    /** Returns false when [to] already wraps the key, so a run cut short after the update repeats harmlessly. */
    fun rewrapTenantKey(
        from: String,
        to: String,
    ): Boolean {
        val wrapped = wrappedTenantKey(TenantContext.current())
        val material =
            try {
                kms.unwrap(from, wrapped)
            } catch (e: KeyManagementException) {
                if (e is KeyManagementUnavailableException) throw e
                kms.unwrap(to, wrapped)
                return false
            }
        val rewrapped = kms.wrap(to, material)
        // The previous KEK goes once its backups expire, and a bad wrap found after that would cost the tenant.
        check(kms.unwrap(to, rewrapped).contentEquals(material)) { "$to does not unwrap what it wrapped" }
        transaction.execute { dsl ->
            dsl
                .update(TENANT_KEYS)
                .set(TENANT_KEYS.WRAPPED_KEY, rewrapped)
                .where(TENANT_KEYS.VERSION.eq(TENANT_KEY_VERSION))
                .execute()
        }
        return true
    }

    fun createUserKey(userId: UUID) {
        val tenantKey = tenantKey()
        val keyset = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM, AeadConfig2026.get())
        val wrapped =
            TinkProtoKeysetFormat.serializeEncryptedKeyset(
                keyset,
                tenantKey.userKeys,
                tenantKey.userKeyContext(userId),
                AeadConfig2026.get(),
            )
        transaction.execute { dsl ->
            dsl
                .insertInto(USER_KEYS)
                .set(USER_KEYS.USER_ID, userId)
                .set(USER_KEYS.KEYSET, wrapped)
                .onConflictDoNothing()
                .execute()
        }
    }

    fun userCipher(userId: UUID): FieldCipher = userCiphers(listOf(userId))[userId] ?: throw UserKeyNotFoundException(userId)

    /** Leaves out users without a key, because an erased user must not fail a list that mentions them. */
    fun userCiphers(userIds: Collection<UUID>): Map<UUID, FieldCipher> {
        val tenantKey = tenantKey()
        val wrapping = tenantKey.userKeys
        val keysets =
            transaction.execute { dsl ->
                dsl
                    .select(USER_KEYS.USER_ID, USER_KEYS.KEYSET)
                    .from(USER_KEYS)
                    .where(USER_KEYS.USER_ID.`in`(userIds))
                    .fetchMap(USER_KEYS.USER_ID, USER_KEYS.KEYSET)
            }
        return keysets.mapValues { (userId, keyset) ->
            try {
                FieldCipher.of(
                    TinkProtoKeysetFormat.parseEncryptedKeyset(
                        keyset,
                        wrapping,
                        tenantKey.userKeyContext(userId),
                        AeadConfig2026.get(),
                    ),
                )
            } catch (e: GeneralSecurityException) {
                throw FieldDecryptionException(e)
            }
        }
    }

    fun derivedCipher(key: DerivedKey): FieldCipher = FieldCipher.of(tenantKey().keyset(key.purpose))

    fun blindIndex() = BlindIndex(tenantKey().derive(BLIND_INDEX_PURPOSE))

    /** The loader needs this thread's transaction and tenant scope, and a KMS call must not hold a cache lock. */
    private fun tenantKey(): TenantKey {
        val context = TenantContext.current()
        val loading = CompletableFuture<TenantKey>()
        val running = tenantKeys.asMap().putIfAbsent(context.tenantId, loading)
        if (running != null) return awaited(running)
        return try {
            load(context).also(loading::complete)
        } catch (e: Throwable) {
            loading.completeExceptionally(e)
            throw e
        }
    }

    private fun awaited(running: CompletableFuture<TenantKey>): TenantKey =
        try {
            running.join()
        } catch (e: CompletionException) {
            throw e.cause ?: e
        }

    private fun load(context: TenantContext): TenantKey {
        val kekUri = kekUri(context)
        val wrapped = wrappedTenantKey(context)
        val material =
            try {
                kms.unwrap(kekUri, wrapped)
            } catch (e: KeyManagementException) {
                if (e is KeyManagementUnavailableException) throw e
                unwrapWithStoredKeks(context.tenantId, kekUri, wrapped, e)
            }
        return TenantKey(material, context.tenantId)
    }

    /**
     * A context made before a move carries the KEK of then, and one made during it may meet a key the job has not rewrapped
     * yet. `platform.tenants` names both KEKs, and one read of it settles either case.
     */
    private fun unwrapWithStoredKeks(
        tenantId: UUID,
        tried: String,
        wrapped: ByteArray,
        failure: KeyManagementException,
    ): ByteArray {
        val stored =
            platform.executeSeparately { dsl ->
                dsl
                    .select(TENANTS.KEK_URI, TENANTS.PREVIOUS_KEK_URI)
                    .from(TENANTS)
                    .where(TENANTS.ID.eq(tenantId))
                    .fetchOne { listOfNotNull(it.value1(), it.value2()) }
                    .orEmpty()
            }
        for (kekUri in stored - tried) {
            try {
                return kms.unwrap(kekUri, wrapped)
            } catch (e: KeyManagementException) {
                if (e is KeyManagementUnavailableException) throw e
                failure.addSuppressed(e)
            }
        }
        throw failure
    }

    private fun wrappedTenantKey(context: TenantContext): ByteArray {
        val wrapped =
            transaction.execute { dsl ->
                dsl
                    .select(TENANT_KEYS.WRAPPED_KEY)
                    .from(TENANT_KEYS)
                    .where(TENANT_KEYS.VERSION.eq(TENANT_KEY_VERSION))
                    .fetchOne(TENANT_KEYS.WRAPPED_KEY)
            }
        return checkNotNull(wrapped) { "Tenant ${context.tenantId} has no key" }
    }

    private fun kekUri(context: TenantContext): String = checkNotNull(context.kekUri) { "Tenant ${context.tenantId} has no KEK" }
}
