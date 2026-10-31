package idp.sharedkernel.crypto

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.util.Locale

private val KEY = ByteArray(32) { it.toByte() }

class BlindIndexTest :
    FunSpec({
        val index = BlindIndex(KEY)

        test("spelling variants of one address share an index") {
            val expected = index.email("alice@example.com")

            listOf(
                "Alice@Example.COM",
                "  alice@example.com\n",
                "ａｌｉｃｅ＠ｅｘａｍｐｌｅ．ｃｏｍ",
            ).forEach { index.email(it) shouldBe expected }
        }

        test("the JVM locale does not change an index") {
            val expected = index.email("TITLE@EXAMPLE.COM")
            val default = Locale.getDefault()
            Locale.setDefault(Locale.forLanguageTag("tr"))
            try {
                index.email("TITLE@EXAMPLE.COM") shouldBe expected
            } finally {
                Locale.setDefault(default)
            }
        }

        test("different addresses have different indexes") {
            index.email("alice@example.com") shouldNotBe index.email("bob@example.com")
        }

        test("the index depends on the key") {
            BlindIndex(ByteArray(32)).email("alice@example.com") shouldNotBe index.email("alice@example.com")
        }
    })
