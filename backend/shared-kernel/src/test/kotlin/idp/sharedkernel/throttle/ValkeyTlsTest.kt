package idp.sharedkernel.throttle

import idp.sharedkernel.testing.TestCa
import idp.sharedkernel.throttle.adapter.ValkeyRateLimiter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import java.time.Duration
import java.util.UUID

private const val PORT = 6379
private const val PASSWORD = "valkey-secret"
private val TIMEOUT: Duration = Duration.ofSeconds(5)
private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
private val COOLDOWN: Duration = Duration.ofSeconds(30)
private val MINUTE: Duration = Duration.ofMinutes(1)

/** Valkey as ElastiCache serves it: TLS alone, no plain port, and a password. */
private fun tlsValkey(
    certificate: Pair<String, String>,
    ca: String,
): GenericContainer<*> =
    GenericContainer(System.getProperty("idp.valkey.image"))
        .withExposedPorts(PORT)
        .withCopyToContainer(Transferable.of(certificate.first), "/tls/server.crt")
        .withCopyToContainer(Transferable.of(certificate.second), "/tls/server.key")
        .withCopyToContainer(Transferable.of(ca), "/tls/ca.crt")
        .withCommand(
            "valkey-server",
            "--port",
            "0",
            "--tls-port",
            "$PORT",
            "--tls-cert-file",
            "/tls/server.crt",
            "--tls-key-file",
            "/tls/server.key",
            "--tls-ca-cert-file",
            "/tls/ca.crt",
            "--tls-auth-clients",
            "no",
            "--requirepass",
            PASSWORD,
        ).waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1))
        .also { it.start() }

private class Probe(
    url: String,
    tlsCa: String,
    password: String = PASSWORD,
) {
    private val registry = SimpleMeterRegistry()
    private val limiter =
        ValkeyRateLimiter(url, TIMEOUT, CONNECT_TIMEOUT, COOLDOWN, registry, password = password, tlsCa = tlsCa)

    /** Two requests against a limit of one: only a limiter that reached Valkey refuses the second. */
    fun twice(): List<RateDecision> {
        val key = "throttle:tls:${UUID.randomUUID()}"
        return List(2) { limiter.acquire(key, ThrottleLimit(1, MINUTE)) }.also { limiter.close() }
    }

    val failures: Double get() = registry.counter("idp.throttle.failures").count()
}

class ValkeyTlsTest :
    FunSpec({
        val valkeyCa = TestCa("Test ElastiCache CA")
        val otherCa = TestCa("Test other CA")
        val valkey = tlsValkey(valkeyCa.issue("localhost"), valkeyCa.pem)
        val misnamed = tlsValkey(valkeyCa.issue("valkey.other.test"), valkeyCa.pem)
        val url = "rediss://localhost:${valkey.getMappedPort(PORT)}"

        afterSpec {
            valkey.stop()
            misnamed.stop()
        }

        fun refused(probe: Probe) {
            probe.twice() shouldBe listOf(RateDecision.Allowed, RateDecision.Allowed)
            probe.failures shouldBe 1.0
        }

        test("a server certified by the given CA for its name answers") {
            val probe = Probe(url, valkeyCa.pem)

            val (first, second) = probe.twice()

            first shouldBe RateDecision.Allowed
            second.shouldBeInstanceOf<RateDecision.Rejected>()
            probe.failures shouldBe 0.0
        }

        test("each CA of a bundle is trusted") {
            val (_, second) = Probe(url, otherCa.pem + valkeyCa.pem).twice()

            second.shouldBeInstanceOf<RateDecision.Rejected>()
        }

        test("a certificate of another CA is refused") {
            refused(Probe(url, otherCa.pem))
        }

        test("a certificate for another name is refused") {
            refused(Probe("rediss://localhost:${misnamed.getMappedPort(PORT)}", valkeyCa.pem))
        }

        test("verifyPeer in the URL cannot turn the check off") {
            refused(Probe("$url?verifyPeer=NONE", otherCa.pem))
        }

        test("a wrong password is refused") {
            refused(Probe(url, valkeyCa.pem, password = "wrong"))
        }

        test("no password is refused") {
            refused(Probe(url, valkeyCa.pem, password = ""))
        }

        test("a rediss:// URL without a CA does not start") {
            shouldThrow<IllegalArgumentException> { Probe(url, "") }.message shouldContain "VALKEY_TLS_CA"
        }

        test("a CA beside a plain URL does not start") {
            shouldThrow<IllegalArgumentException> { Probe("redis://localhost:1", valkeyCa.pem) }.message shouldContain "rediss://"
        }

        test("a CA without a certificate does not start") {
            shouldThrow<IllegalArgumentException> { Probe(url, "no certificate here\n") }.message shouldContain "no certificate"
        }
    })
