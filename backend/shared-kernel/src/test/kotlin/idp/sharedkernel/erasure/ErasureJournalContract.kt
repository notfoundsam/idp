package idp.sharedkernel.erasure

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.funSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import java.util.UUID
import java.util.concurrent.Executors

/** [storage] makes an empty place for a journal, [open] reaches it the way a second process would. */
fun erasureJournalContract(
    storage: () -> String,
    open: (String) -> ErasureJournal,
    unreachable: () -> ErasureJournal,
) = funSpec {
    val tenant = Erasure.Tenant(UUID.randomUUID())
    val user = Erasure.User(UUID.randomUUID(), UUID.randomUUID())

    test("an empty journal has no entries") {
        open(storage()).entries().shouldBeEmpty()
    }

    test("recorded erasures of a tenant and of a user come back") {
        val journal = open(storage())

        journal.record(tenant)
        journal.record(user)

        journal.entries().shouldContainExactlyInAnyOrder(tenant, user)
    }

    test("a repeated record appears once") {
        val journal = open(storage())

        journal.record(user)
        journal.record(user)

        journal.entries().shouldContainExactlyInAnyOrder(user)
    }

    test("another process on the same storage reads every entry") {
        val place = storage()
        open(place).record(tenant)
        open(place).record(user)

        open(place).entries().shouldContainExactlyInAnyOrder(tenant, user)
    }

    test("concurrent records lose nothing") {
        val place = storage()
        val erasures = List(16) { Erasure.User(user.tenantId, UUID.randomUUID()) }

        Executors.newFixedThreadPool(4).use { pool ->
            erasures.map { erasure -> pool.submit { open(place).record(erasure) } }.forEach { it.get() }
        }

        open(place).entries() shouldBe erasures.toSet()
    }

    test("an unreachable journal is reported on both operations") {
        shouldThrow<ErasureJournalUnavailableException> { unreachable().record(user) }
        shouldThrow<ErasureJournalUnavailableException> { unreachable().entries() }
    }
}
