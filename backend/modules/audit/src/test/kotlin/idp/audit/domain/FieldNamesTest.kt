package idp.audit.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FieldNamesTest :
    FunSpec({
        test("the name of every encrypted field stays as the stored ciphertext was sealed with") {
            listOf(
                IP_FIELD to "audit_entries.ip",
                DETAILS_FIELD to "audit_entries.details",
            ).forEach { (name, sealed) -> name shouldBe sealed }
        }
    })
