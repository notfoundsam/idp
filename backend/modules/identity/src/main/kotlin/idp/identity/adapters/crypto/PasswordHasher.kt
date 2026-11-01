package idp.identity.adapters.crypto

import idp.sharedkernel.throttle.TooManyRequestsException
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

private const val SALT_BYTES = 16
private const val HASH_BYTES = 32
private const val QUEUE_PER_PERMIT = 2
private val QUEUE_WAIT: Duration = Duration.ofSeconds(1)
private val RETRY_AFTER: Duration = Duration.ofSeconds(1)
private val ENCODED = Regex("""\${'$'}argon2id\${'$'}v=19\${'$'}m=(\d+),t=(\d+),p=(\d+)\${'$'}([A-Za-z0-9+/]+)\${'$'}([A-Za-z0-9+/]+)""")

internal data class HashCost(
    val memoryKib: Int = 19_456,
    val iterations: Int = 2,
    val parallelism: Int = 1,
)

private class EncodedHash(
    val cost: HashCost,
    val salt: ByteArray,
    val hash: ByteArray,
)

/** Keeps the parameters in the string, so raising them leaves every stored hash verifiable. */
internal class PasswordHasher(
    private val cost: HashCost = HashCost(),
) {
    private val random = SecureRandom()

    fun hash(password: String): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val encoder = Base64.getEncoder().withoutPadding()
        val parameters = "m=${cost.memoryKib},t=${cost.iterations},p=${cost.parallelism}"
        val hash = compute(password, salt, cost, HASH_BYTES)
        return "\$argon2id\$v=19\$$parameters\$${encoder.encodeToString(salt)}\$${encoder.encodeToString(hash)}"
    }

    fun matches(
        password: String,
        encoded: String,
    ): Boolean {
        val stored = parse(encoded)
        return MessageDigest.isEqual(compute(password, stored.salt, stored.cost, stored.hash.size), stored.hash)
    }

    fun isCurrent(encoded: String): Boolean = parse(encoded).cost == cost

    private fun compute(
        password: String,
        salt: ByteArray,
        cost: HashCost,
        length: Int,
    ): ByteArray {
        val parameters =
            Argon2Parameters
                .Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(cost.memoryKib)
                .withIterations(cost.iterations)
                .withParallelism(cost.parallelism)
                .withSalt(salt)
                .build()
        return ByteArray(length).also {
            Argon2BytesGenerator().apply { init(parameters) }.generateBytes(password.toByteArray(), it)
        }
    }

    private fun parse(encoded: String): EncodedHash {
        val (memory, iterations, parallelism, salt, hash) =
            checkNotNull(ENCODED.matchEntire(encoded)) { "A stored hash is not in the Argon2id format" }.destructured
        val decoder = Base64.getDecoder()
        return EncodedHash(HashCost(memory.toInt(), iterations.toInt(), parallelism.toInt()), decoder.decode(salt), decoder.decode(hash))
    }
}

/**
 * Each hash holds its whole memory until it ends, so the number running bounds the heap, and a queue past a few seconds of work
 * only moves the failure. Queued requests hold no hash memory, and one past the queue leaves before it allocates any.
 */
internal class HashLimit(
    permits: Int = Runtime.getRuntime().availableProcessors(),
    queue: Int = permits * QUEUE_PER_PERMIT,
    private val wait: Duration = QUEUE_WAIT,
) {
    private val admitted = Semaphore(permits + queue)
    private val running = Semaphore(permits, true)

    fun <T> run(block: () -> T): T {
        if (!admitted.tryAcquire()) throw TooManyRequestsException(RETRY_AFTER)
        try {
            if (!running.tryAcquire(wait.toMillis(), TimeUnit.MILLISECONDS)) throw TooManyRequestsException(RETRY_AFTER)
            try {
                return block()
            } finally {
                running.release()
            }
        } finally {
            admitted.release()
        }
    }
}
