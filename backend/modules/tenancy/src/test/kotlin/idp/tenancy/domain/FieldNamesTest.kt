package idp.tenancy.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FieldNamesTest :
    FunSpec({
        test("the name of every encrypted field stays as the stored ciphertext was sealed with") {
            listOf(
                APPLICATION_CONTACT_FIELD to "tenant_applications.contact_ciphertext",
                REINVITE_CONTACT_FIELD to "admin_reinvites.contact_ciphertext",
            ).forEach { (name, sealed) -> name shouldBe sealed }
        }
    })
