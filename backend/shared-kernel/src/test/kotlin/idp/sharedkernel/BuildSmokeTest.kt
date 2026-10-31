package idp.sharedkernel

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class BuildSmokeTest :
    StringSpec({
        "the build runs Kotest" {
            1 + 1 shouldBe 2
        }
    })
