package idp.sharedkernel.throttle.adapter

import idp.sharedkernel.throttle.RateDecision
import idp.sharedkernel.throttle.RateLimiter
import idp.sharedkernel.throttle.ThrottleLimit
import io.github.bucket4j.Bandwidth
import io.github.bucket4j.BucketConfiguration
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy
import io.github.bucket4j.distributed.proxy.ProxyManager
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce
import io.lettuce.core.ClientOptions
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.SocketOptions
import io.lettuce.core.SslOptions
import io.lettuce.core.SslVerifyMode
import io.lettuce.core.TimeoutOptions
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.codec.ByteArrayCodec
import io.lettuce.core.codec.RedisCodec
import io.lettuce.core.codec.StringCodec
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.TrustManagerFactory

private const val FAILURES = "idp.throttle.failures"
private const val CLOSED = 0L
private const val MAX_CAS_RETRIES = 3
private val TLS_PROTOCOLS = arrayOf("TLSv1.3", "TLSv1.2")
private val KEEP_AFTER_REFILL: Duration = Duration.ofMinutes(1)

private val log = LoggerFactory.getLogger(ValkeyRateLimiter::class.java)

/** Admits one probe at a time while open, so a dead server costs one timeout per cooldown and not one per request. */
private class Breaker(
    private val cooldown: Duration,
    private val clock: Clock,
) {
    // Starts as an expired cooldown, so the first caller makes the connection and concurrent ones do not queue behind it.
    private val openUntil = AtomicLong(clock.millis())

    fun admits(): Boolean {
        val until = openUntil.get()
        if (until == CLOSED) return true
        val now = clock.millis()
        return now >= until && openUntil.compareAndSet(until, now + cooldown.toMillis())
    }

    fun open() = openUntil.set(clock.millis() + cooldown.toMillis())

    fun close() = openUntil.set(CLOSED)
}

/** A `rediss://` server is trusted through the certificates of [tlsCa] alone, and its name must match the URL. */
private fun tlsOptions(tlsCa: String): SslOptions {
    val certificates =
        try {
            CertificateFactory.getInstance("X.509").generateCertificates(tlsCa.byteInputStream())
        } catch (failure: CertificateException) {
            throw IllegalArgumentException("VALKEY_TLS_CA holds no certificate", failure)
        }
    require(certificates.isNotEmpty()) { "VALKEY_TLS_CA holds no certificate" }
    val anchors =
        KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            certificates.forEachIndexed { index, certificate -> setCertificateEntry("ca-$index", certificate) }
        }
    val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(anchors) }
    return SslOptions
        .builder()
        .jdkSslProvider()
        .trustManager(trust)
        .protocols(*TLS_PROTOCOLS)
        .build()
}

/**
 * Fails open: Postgres keeps the counters that must not be lost, and this limiter only sheds load.
 * Connecting has its own timeout: TLS and AUTH on a cold JVM outlast a command, and only one probe waits for them.
 */
class ValkeyRateLimiter(
    url: String,
    private val timeout: Duration,
    connectTimeout: Duration,
    cooldown: Duration,
    registry: MeterRegistry,
    clock: Clock = Clock.systemUTC(),
    password: String = "",
    tlsCa: String = "",
) : RateLimiter,
    AutoCloseable {
    private val uri: RedisURI =
        RedisURI.create(url).also {
            it.setTimeout(connectTimeout)
            if (password.isNotEmpty()) it.setAuthentication(password)
            if (it.isSsl) it.setVerifyPeer(SslVerifyMode.FULL)
        }

    init {
        require(uri.isSsl == tlsCa.isNotBlank()) {
            if (uri.isSsl) "A rediss:// VALKEY_URL needs VALKEY_TLS_CA" else "VALKEY_TLS_CA needs a rediss:// VALKEY_URL"
        }
    }

    private val client: RedisClient =
        RedisClient.create().also {
            it.options =
                ClientOptions
                    .builder()
                    .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                    .socketOptions(SocketOptions.builder().connectTimeout(connectTimeout).build())
                    .timeoutOptions(TimeoutOptions.enabled(timeout))
                    .apply { if (uri.isSsl) sslOptions(tlsOptions(tlsCa)) }
                    .build()
        }
    private val breaker = Breaker(cooldown, clock)
    private val failures: Counter = registry.counter(FAILURES)

    @Volatile
    private var connection: StatefulRedisConnection<String, ByteArray>? = null

    @Volatile
    private var buckets: ProxyManager<String>? = null

    override fun acquire(
        key: String,
        limit: ThrottleLimit,
    ): RateDecision {
        if (!breaker.admits()) return RateDecision.Allowed
        return try {
            // The limit is part of the key: a stored bucket keeps its first configuration for as long as traffic renews it.
            val bucket = buckets().builder().build("$key:${limit.capacity}:${limit.period.toMillis()}") { configuration(limit) }
            val probe = bucket.tryConsumeAndReturnRemaining(1)
            breaker.close()
            if (probe.isConsumed) RateDecision.Allowed else RateDecision.Rejected(Duration.ofNanos(probe.nanosToWaitForRefill))
        } catch (failure: RuntimeException) {
            log.warn("Rate limits are off: Valkey failed with {}", failure.javaClass.simpleName)
            failures.increment()
            breaker.open()
            RateDecision.Allowed
        }
    }

    /** Connects on first use, so `app` starts without Valkey. Only a breaker probe can get here unconnected. */
    private fun buckets(): ProxyManager<String> =
        buckets ?: client
            .connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE), uri)
            .also { connection = it }
            .let { connected ->
                Bucket4jLettuce
                    .casBasedBuilder(connected)
                    .requestTimeout(timeout)
                    .maxRetries(MAX_CAS_RETRIES)
                    .expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(KEEP_AFTER_REFILL))
                    .build()
            }.also { buckets = it }

    private fun configuration(limit: ThrottleLimit): BucketConfiguration =
        BucketConfiguration
            .builder()
            .addLimit(
                Bandwidth
                    .builder()
                    .capacity(limit.capacity)
                    .refillGreedy(limit.capacity, limit.period)
                    .build(),
            ).build()

    override fun close() {
        connection?.close()
        client.shutdown(Duration.ZERO, timeout)
    }
}
