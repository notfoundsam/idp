package idp.sharedkernel.erasure.adapter

import idp.sharedkernel.erasure.Erasure
import idp.sharedkernel.erasure.ErasureJournalUnavailableException
import idp.sharedkernel.erasure.adapter.aws.S3ErasureJournal
import idp.sharedkernel.erasure.erasureJournalContract
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.appendText
import kotlin.io.path.readText
import kotlin.io.path.writeText

private fun storage(): String = Files.createTempDirectory("erasures").resolve("journal").toString()

class FileErasureJournalTest :
    FunSpec({
        include(
            erasureJournalContract(
                storage = ::storage,
                open = { FileErasureJournal(Path.of(it)) },
                unreachable = { FileErasureJournal(Files.createTempFile("not-a-directory", "").resolve("journal")) },
            ),
        )

        test("a line holds the IDs and nothing else") {
            val file = Path.of(storage())
            val tenant = UUID.randomUUID()
            val user = UUID.randomUUID()

            FileErasureJournal(file).record(Erasure.User(tenant, user))
            FileErasureJournal(file).record(Erasure.Tenant(tenant))

            file.readText() shouldBe "users/$tenant/$user\ntenants/$tenant\n"
        }

        test("a missing directory is an error and not an empty journal") {
            val journal = FileErasureJournal(Path.of(storage()).resolveSibling("missing").resolve("journal"))

            shouldThrow<ErasureJournalUnavailableException> { journal.entries() }
        }

        test("a missing file in its directory is an empty journal") {
            FileErasureJournal(Path.of(storage())).entries().shouldBeEmpty()
        }

        test("a last line torn by a crash is left out, and the entries before it stay") {
            val file = Path.of(storage())
            val user = Erasure.User(UUID.randomUUID(), UUID.randomUUID())
            FileErasureJournal(file).record(user)

            file.appendText("users/${UUID.randomUUID()}/")

            FileErasureJournal(file).entries().shouldContainExactlyInAnyOrder(user)
        }

        listOf(
            "users/${UUID.randomUUID()}",
            "tenants/1-1-1-1-1",
            "tenants/${UUID.randomUUID()}/extra",
            "groups/${UUID.randomUUID()}",
        ).forEach { line ->
            test("a line that is no erasure fails the read: $line") {
                val file = Path.of(storage())
                file.writeText("$line\n")

                shouldThrow<IllegalStateException> { FileErasureJournal(file).entries() }
            }
        }

        context("settings") {
            test("a file gives the file journal") {
                ErasureJournalSettings(bucket = "", region = "", file = storage()).journal().shouldBeInstanceOf<FileErasureJournal>()
            }

            test("a bucket gives the S3 journal") {
                ErasureJournalSettings(
                    bucket = "erasures",
                    region = "eu-west-1",
                    file = "",
                ).journal().shouldBeInstanceOf<S3ErasureJournal>()
            }

            test("neither or both are refused") {
                shouldThrow<IllegalArgumentException> { ErasureJournalSettings("", "", "").journal() }
                shouldThrow<IllegalArgumentException> { ErasureJournalSettings("erasures", "eu-west-1", storage()).journal() }
            }
        }
    })
