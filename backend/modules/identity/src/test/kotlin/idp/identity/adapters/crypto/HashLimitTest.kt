package idp.identity.adapters.crypto

import idp.sharedkernel.throttle.TooManyRequestsException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val WAIT_SECONDS = 30L
private val LONG_WAIT: Duration = Duration.ofSeconds(WAIT_SECONDS)

private fun CountDownLatch.expect() = await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true

private fun <T> CompletableFuture<T>.result(): T = get(WAIT_SECONDS, TimeUnit.SECONDS)

/** Holds one permit until released. */
private class Holder(
    limit: HashLimit,
) {
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val done =
        CompletableFuture.runAsync {
            limit.run {
                entered.countDown()
                release.await(WAIT_SECONDS, TimeUnit.SECONDS)
            }
        }

    fun holding() = apply { entered.expect() }

    fun free() {
        release.countDown()
        done.result()
    }
}

class HashLimitTest :
    FunSpec({
        test("no more hashes run at once than there are permits") {
            val limit = HashLimit(permits = 2, queue = 30, wait = LONG_WAIT)
            val running = AtomicInteger()
            val most = AtomicInteger()

            (1..16)
                .map {
                    CompletableFuture.runAsync {
                        limit.run {
                            most.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                            Thread.sleep(5)
                            running.decrementAndGet()
                        }
                    }
                }.forEach { it.result() }

            most.get() shouldBeLessThanOrEqual 2
        }

        test("a request past the queue leaves at once and runs nothing") {
            val limit = HashLimit(permits = 1, queue = 0, wait = LONG_WAIT)
            val holder = Holder(limit).holding()
            var ran = false

            val started = System.nanoTime()
            shouldThrow<TooManyRequestsException> { limit.run { ran = true } }
            val tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

            holder.free()
            ran shouldBe false
            tookMillis shouldBeLessThan 1_000
        }

        test("a queued request waits for a permit") {
            val limit = HashLimit(permits = 1, queue = 1, wait = LONG_WAIT)
            val holder = Holder(limit).holding()

            val queued = CompletableFuture.supplyAsync { limit.run { "done" } }
            holder.free()

            queued.result() shouldBe "done"
        }

        test("a queued request gives up after the short wait") {
            val limit = HashLimit(permits = 1, queue = 1, wait = Duration.ofMillis(10))
            val holder = Holder(limit).holding()

            shouldThrow<TooManyRequestsException> { limit.run { } }

            holder.free()
        }

        test("a failed hash frees its permit") {
            val limit = HashLimit(permits = 1, queue = 0, wait = LONG_WAIT)

            shouldThrow<IllegalStateException> { limit.run { error("hash failed") } }

            limit.run { "done" } shouldBe "done"
        }

        test("a request that gave up frees its place in the queue") {
            val limit = HashLimit(permits = 1, queue = 1, wait = Duration.ofMillis(10))
            val holder = Holder(limit).holding()

            repeat(3) { shouldThrow<TooManyRequestsException> { limit.run { } } }

            holder.free()
            limit.run { "done" } shouldBe "done"
        }
    })
