package idp.federation.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FieldNamesTest :
    FunSpec({
        test("the name of every encrypted field stays as the stored ciphertext was sealed with") {
            listOf(
                PRIVATE_KEY_FIELD to "saml_signing_keys.private_key_ciphertext",
                REQUEST_FIELD to "saml_requests.request_ciphertext",
                RELAY_STATE_FIELD to "saml_requests.relay_state_ciphertext",
            ).forEach { (name, sealed) -> name shouldBe sealed }
        }
    })
