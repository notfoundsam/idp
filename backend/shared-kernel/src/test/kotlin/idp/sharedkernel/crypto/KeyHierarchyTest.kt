package idp.sharedkernel.crypto

import com.github.benmanes.caffeine.cache.Ticker
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig2026
import idp.sharedkernel.crypto.adapter.OpenBaoKeyManagementService
import idp.sharedkernel.migration.TenantMigrationRunner
import idp.sharedkernel.migration.TenantSchema
import idp.sharedkernel.migration.TenantSchemaFactory
import idp.sharedkernel.migration.update
import idp.sharedkernel.tenant.TenantContext
import idp.sharedkernel.tenant.TenantContextRequired
import idp.sharedkernel.tenant.TenantTransaction
import idp.sharedkernel.testing.TestDatabase
import idp.sharedkernel.testing.TestOpenBao
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.jdbc.core.JdbcTemplate
import java.security.GeneralSecurityException
import java.time.Duration
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val HMAC = "HmacSHA256"
private const val HASH_SIZE = 32
private const val KEY_INFO = "idp/v1/"
private const val PARALLEL_REQUESTS = 16
private const val QUEUE_TIME_MILLIS = 200L
private val TTL: Duration = Duration.ofMinutes(1)
private val PLAINTEXT = "alice@example.com".toByteArray()

private class ObservedKms(
    private val delegate: KeyManagementService,
) : KeyManagementService by delegate {
    val unwraps = AtomicInteger()
    val wrapped: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())

    @Volatile var down = false

    @Volatile var release: CountDownLatch? = null

    override fun wrap(
        kekUri: String,
        plaintext: ByteArray,
    ): ByteArray {
        wrapped += plaintext
        return delegate.wrap(kekUri, plaintext)
    }

    override fun unwrap(
        kekUri: String,
        wrapped: ByteArray,
    ): ByteArray {
        unwraps.incrementAndGet()
        release?.await(10, TimeUnit.SECONDS)
        if (down) throw KeyManagementUnavailableException("down")
        return delegate.unwrap(kekUri, wrapped)
    }
}

private class Hierarchy {
    val database = TestDatabase.create()
    val owner = database.owner()
    val nanos = AtomicLong()
    val kms = ObservedKms(OpenBaoKeyManagementService(TestOpenBao.address, TestOpenBao.TOKEN))

    init {
        TenantMigrationRunner(owner).migrateAll()
    }

    val runtime = database.role("app_runtime")
    val keys = newKeys()

    fun newKeys() = KeyHierarchy(kms, TenantTransaction(runtime), TTL, Ticker(nanos::get))

    fun tenant(): TenantContext {
        val id = UUID.randomUUID()
        owner.update("INSERT INTO platform.tenants (id, name, status) VALUES ('$id', 'tenant', 'ACTIVE')")
        TenantSchemaFactory(owner).create(id)
        TenantMigrationRunner(owner).migrateTenant(id)
        val context = TenantContext(id, kms.createKey("tenant-$id"))
        TenantContext.with(context) { keys.createTenantKey() }
        return context
    }

    fun expireCache() {
        nanos.addAndGet(TTL.plusSeconds(1).toNanos())
    }

    fun table(
        context: TenantContext,
        name: String,
    ) = "\"${TenantSchema(context.tenantId).name}\".$name"

    fun keyset(
        context: TenantContext,
        userId: UUID,
    ): ByteArray =
        JdbcTemplate(owner).queryForObject(
            "SELECT keyset FROM ${table(context, "user_keys")} WHERE user_id = ?",
            ByteArray::class.java,
            userId,
        )!!

    /** `user_keys` references `users`, and these tests care only about the key. */
    fun addUsers(
        context: TenantContext,
        users: List<UUID>,
    ) {
        JdbcTemplate(owner).batchUpdate(
            """
            INSERT INTO ${table(context, "users")} (id, email_index, email_ciphertext, role, status)
            VALUES (?, ?, '\x00', 'USER', 'ACTIVE') ON CONFLICT DO NOTHING
            """,
            users.map { arrayOf(it, it.toString().toByteArray()) },
        )
    }

    fun createUser(user: UUID) {
        addUsers(TenantContext.current(), listOf(user))
        keys.createUserKey(user)
    }

    fun storeKeyset(
        context: TenantContext,
        userId: UUID,
        keyset: ByteArray,
    ) {
        addUsers(context, listOf(userId))
        JdbcTemplate(owner).update("INSERT INTO ${table(context, "user_keys")} (user_id, keyset) VALUES (?, ?)", userId, keyset)
    }

    fun wrappedTenantKey(context: TenantContext): ByteArray =
        JdbcTemplate(owner).queryForObject(
            "SELECT wrapped_key FROM ${table(context, "tenant_keys")}",
            ByteArray::class.java,
        )!!
}

private fun hmac(
    key: ByteArray,
    data: ByteArray,
): ByteArray = Mac.getInstance(HMAC).apply { init(SecretKeySpec(key, HMAC)) }.doFinal(data)

/** RFC 5869 for one output block, written out so the test does not repeat the code under test. */
private fun hkdf(
    key: ByteArray,
    info: String,
): ByteArray = hmac(hmac(ByteArray(HASH_SIZE), key), info.toByteArray() + 1.toByte())

class KeyHierarchyTest :
    FunSpec({
        val email = FieldContext("users.email", UUID.randomUUID())

        test("a user key survives a restart of the service") {
            Hierarchy().run {
                val context = tenant()
                val user = UUID.randomUUID()
                val ciphertext =
                    TenantContext.with(context) {
                        createUser(user)
                        keys.userCipher(user).encrypt(PLAINTEXT, email)
                    }

                TenantContext.with(context) { newKeys().userCipher(user).decrypt(ciphertext, email) } shouldBe PLAINTEXT
            }
        }

        test("only wrapped material reaches the database") {
            Hierarchy().run {
                val context = tenant()
                val user = UUID.randomUUID()
                TenantContext.with(context) { createUser(user) }

                val tenantKey = kms.wrapped.single()
                wrappedTenantKey(context) shouldNotBe tenantKey
                kms.unwrap(context.kekUri!!, wrappedTenantKey(context)) shouldBe tenantKey
                shouldThrow<GeneralSecurityException> {
                    TinkProtoKeysetFormat.parseKeyset(keyset(context, user), InsecureSecretKeyAccess.get(), AeadConfig2026.get())
                }
            }
        }

        test("data of one tenant does not decrypt with the key of another") {
            Hierarchy().run {
                val a = tenant()
                val b = tenant()
                val user = UUID.randomUUID()
                val ciphertext =
                    TenantContext.with(a) {
                        createUser(user)
                        keys.userCipher(user).encrypt(PLAINTEXT, email)
                    }
                TenantContext.with(b) { createUser(user) }

                shouldThrow<FieldDecryptionException> {
                    TenantContext.with(b) { keys.userCipher(user).decrypt(ciphertext, email) }
                }
            }
        }

        test("a user key copied to another tenant does not open") {
            Hierarchy().run {
                val a = tenant()
                val b = tenant()
                val user = UUID.randomUUID()
                TenantContext.with(a) { createUser(user) }
                storeKeyset(b, user, keyset(a, user))

                shouldThrow<FieldDecryptionException> { TenantContext.with(b) { keys.userCipher(user) } }
            }
        }

        test("a user key copied to another user does not open") {
            Hierarchy().run {
                val context = tenant()
                val first = UUID.randomUUID()
                val other = UUID.randomUUID()
                TenantContext.with(context) { createUser(first) }
                storeKeyset(context, other, keyset(context, first))

                shouldThrow<FieldDecryptionException> { TenantContext.with(context) { keys.userCipher(other) } }
            }
        }

        test("a list of 1000 users makes one KMS call") {
            Hierarchy().run {
                val context = tenant()
                val users = List(1000) { UUID.randomUUID() }
                addUsers(context, users)
                TenantContext.with(context) { TenantTransaction(runtime).execute { users.forEach(keys::createUserKey) } }
                expireCache()
                kms.unwraps.set(0)

                val ciphers = TenantContext.with(context) { keys.userCiphers(users) }

                ciphers shouldHaveSize 1000
                kms.unwraps.get() shouldBe 1
            }
        }

        test("separate reads inside the TTL make one KMS call") {
            Hierarchy().run {
                val context = tenant()
                val users = List(20) { UUID.randomUUID() }

                TenantContext.with(context) {
                    users.forEach(::createUser)
                    users.forEach(keys::userCipher)
                }

                kms.unwraps.get() shouldBe 1
            }
        }

        test("a list leaves out erased users") {
            Hierarchy().run {
                val context = tenant()
                val kept = UUID.randomUUID()
                val erased = UUID.randomUUID()
                TenantContext.with(context) { createUser(kept) }

                val ciphers = TenantContext.with(context) { keys.userCiphers(listOf(kept, erased)) }

                ciphers.keys shouldContainExactly setOf(kept)
            }
        }

        test("a missing user key is reported") {
            Hierarchy().run {
                val context = tenant()

                shouldThrow<UserKeyNotFoundException> {
                    TenantContext.with(context) { keys.userCipher(UUID.randomUUID()) }
                }
            }
        }

        test("an expired TTL goes to the KMS again") {
            Hierarchy().run {
                val context = tenant()
                TenantContext.with(context) { keys.blindIndex() }
                kms.unwraps.get() shouldBe 1

                expireCache()
                TenantContext.with(context) { keys.blindIndex() }

                kms.unwraps.get() shouldBe 2
            }
        }

        test("each tenant caches its own key") {
            Hierarchy().run {
                val a = tenant()
                val b = tenant()

                TenantContext.with(a) { keys.blindIndex() }
                TenantContext.with(b) { keys.blindIndex() }

                kms.unwraps.get() shouldBe 2
            }
        }

        test("parallel requests make one KMS call") {
            Hierarchy().run {
                val context = tenant()
                val release = CountDownLatch(1)
                val started = CountDownLatch(PARALLEL_REQUESTS)
                kms.release = release

                Executors.newFixedThreadPool(PARALLEL_REQUESTS).use { threads ->
                    repeat(PARALLEL_REQUESTS) {
                        threads.submit {
                            started.countDown()
                            TenantContext.with(context) { keys.blindIndex() }
                        }
                    }
                    started.await()
                    Thread.sleep(QUEUE_TIME_MILLIS)
                    release.countDown()
                }

                kms.unwraps.get() shouldBe 1
            }
        }

        test("the cache keeps working through a KMS outage until the TTL ends") {
            Hierarchy().run {
                val context = tenant()
                val user = UUID.randomUUID()
                TenantContext.with(context) { createUser(user) }
                kms.down = true

                TenantContext.with(context) { keys.userCipher(user) }

                expireCache()
                shouldThrow<KeyManagementUnavailableException> {
                    TenantContext.with(context) { keys.userCipher(user) }
                }
            }
        }

        test("a failed KMS call is not remembered") {
            Hierarchy().run {
                val context = tenant()
                kms.down = true
                shouldThrow<KeyManagementUnavailableException> { TenantContext.with(context) { keys.blindIndex() } }

                kms.down = false
                TenantContext.with(context) { keys.blindIndex() }
            }
        }

        test("requests waiting for a failed KMS call fail with the same error") {
            Hierarchy().run {
                val context = tenant()
                val release = CountDownLatch(1)
                kms.release = release
                kms.down = true

                val failures = ConcurrentLinkedQueue<Throwable>()
                Executors.newFixedThreadPool(PARALLEL_REQUESTS).use { threads ->
                    repeat(PARALLEL_REQUESTS) {
                        threads.submit {
                            TenantContext.with(context) { runCatching { keys.blindIndex() } }.onFailure(failures::add)
                        }
                    }
                    Thread.sleep(QUEUE_TIME_MILLIS)
                    release.countDown()
                }

                failures.map { it::class }.distinct() shouldContainExactly listOf(KeyManagementUnavailableException::class)
                failures.size shouldBe PARALLEL_REQUESTS
            }
        }

        test("repeating provisioning keeps the tenant key") {
            Hierarchy().run {
                val context = tenant()
                val stored = wrappedTenantKey(context)

                TenantContext.with(context) { keys.createTenantKey() }

                wrappedTenantKey(context) shouldBe stored
            }
        }

        test("repeating user creation keeps the user key") {
            Hierarchy().run {
                val context = tenant()
                val user = UUID.randomUUID()
                val ciphertext =
                    TenantContext.with(context) {
                        createUser(user)
                        keys.userCipher(user).encrypt(PLAINTEXT, email)
                    }

                TenantContext.with(context) {
                    createUser(user)
                    keys.userCipher(user).decrypt(ciphertext, email)
                } shouldBe PLAINTEXT
            }
        }

        test("a tenant without a KEK has no keys") {
            Hierarchy().run {
                val context = tenant()

                shouldThrow<IllegalStateException> {
                    TenantContext.with(context.copy(kekUri = null)) { keys.createTenantKey() }
                }
                shouldThrow<IllegalStateException> {
                    TenantContext.with(context.copy(kekUri = null)) { keys.blindIndex() }
                }
            }
        }

        test("a tenant without a stored key is rejected") {
            Hierarchy().run {
                val context = tenant()
                owner.update("DELETE FROM ${table(context, "tenant_keys")}")

                shouldThrow<IllegalStateException> { TenantContext.with(context) { keys.blindIndex() } }
            }
        }

        test("keys need a tenant in scope") {
            Hierarchy().run {
                shouldThrow<TenantContextRequired> { keys.blindIndex() }
            }
        }

        test("a derived key stays the same across restarts") {
            Hierarchy().run {
                val context = tenant()
                val ciphertext = TenantContext.with(context) { keys.derivedCipher(DerivedKey.AUDIT).encrypt(PLAINTEXT, email) }

                TenantContext.with(context) {
                    newKeys().derivedCipher(DerivedKey.AUDIT).decrypt(ciphertext, email)
                } shouldBe PLAINTEXT
            }
        }

        test("derived keys differ by purpose and by tenant") {
            Hierarchy().run {
                val a = tenant()
                val b = tenant()
                val ciphertext = TenantContext.with(a) { keys.derivedCipher(DerivedKey.AUDIT).encrypt(PLAINTEXT, email) }

                shouldThrow<FieldDecryptionException> {
                    TenantContext.with(a) { keys.derivedCipher(DerivedKey.FLOW).decrypt(ciphertext, email) }
                }
                shouldThrow<FieldDecryptionException> {
                    TenantContext.with(b) { keys.derivedCipher(DerivedKey.AUDIT).decrypt(ciphertext, email) }
                }
            }
        }

        test("the blind index differs between tenants") {
            Hierarchy().run {
                val a = tenant()
                val b = tenant()

                val first = TenantContext.with(a) { keys.blindIndex().email("alice@example.com") }
                val second = TenantContext.with(b) { keys.blindIndex().email("alice@example.com") }

                first shouldNotBe second
            }
        }

        test("the blind index uses a key derived through HKDF, not the tenant key") {
            Hierarchy().run {
                val context = tenant()
                val tenantKey = kms.wrapped.single()
                val derived = hkdf(tenantKey, "${KEY_INFO}blind-index/${context.tenantId}")

                val index = TenantContext.with(context) { keys.blindIndex().email("alice@example.com") }

                index shouldBe BlindIndex(derived).email("alice@example.com")
                index shouldNotBe BlindIndex(tenantKey).email("alice@example.com")
            }
        }
    })
