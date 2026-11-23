package idp.authentication.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FieldNamesTest :
    FunSpec({
        test("the name of every encrypted field stays as the stored ciphertext was sealed with") {
            listOf(
                LOGIN_FIELD to "flows.login_ciphertext",
                IP_FIELD to "sso_sessions.ip_ciphertext",
                USER_AGENT_FIELD to "sso_sessions.user_agent_ciphertext",
            ).forEach { (name, sealed) -> name shouldBe sealed }
        }
    })
