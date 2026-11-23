package idp.identity.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FieldNamesTest :
    FunSpec({
        test("the name of every encrypted field stays as the stored ciphertext was sealed with") {
            listOf(
                EMAIL_FIELD to "users.email_ciphertext",
                NEW_EMAIL_FIELD to "email_changes.new_email_ciphertext",
                NAME_FIELD to "user_profiles.name_ciphertext",
                PHONE_FIELD to "user_profiles.phone_ciphertext",
                SECRET_FIELD to "totp_credentials.secret_ciphertext",
            ).forEach { (name, sealed) -> name shouldBe sealed }
        }
    })
