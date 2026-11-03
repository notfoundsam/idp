package idp.federation.adapters.saml

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

private const val HOSTILE = "\"><script>alert(1)</script><a href='x"

class SamlPagesTest :
    FunSpec({
        context("the form to the ACS") {
            test("escapes the RelayState, so it stays a value") {
                val page = SamlPages.post("https://sp.example/acs", "UkVTUE9OU0U=", HOSTILE)

                page.html shouldNotContain "<script>alert"
                page.html shouldContain "value=\"&quot;&gt;&lt;script&gt;alert(1)&lt;/script&gt;&lt;a href=&#39;x\""
            }

            test("leaves the RelayState out when the SP sent none") {
                SamlPages.post("https://sp.example/acs", "UkVTUE9OU0U=", null).html shouldNotContain "RelayState"
            }

            test("runs only its own script and posts only to the ACS") {
                val page = SamlPages.post("https://sp.example/saml/acs?x=1", "UkVTUE9OU0U=", null)
                val csp = page.csp.shouldNotBeNull()
                val nonce = Regex("'nonce-([^']+)'").find(csp)!!.groupValues[1]

                csp shouldContain "default-src 'none'"
                csp shouldContain "form-action https://sp.example/saml/acs;"
                csp shouldContain "frame-ancestors 'none'"
                page.html shouldContain "<script nonce=\"$nonce\">"
                page.html shouldContain "action=\"https://sp.example/saml/acs?x=1\""
            }

            test("draws a new nonce each time") {
                SamlPages.post("https://sp.example/acs", "A", null).csp shouldNotBe SamlPages.post("https://sp.example/acs", "A", null).csp
            }

            test("cannot widen its policy through the ACS URL") {
                val csp = SamlPages.post("https://sp.example/a;script-src,b", "A", null).csp.shouldNotBeNull()

                csp shouldContain "form-action https://sp.example/a%3Bscript-src%2Cb;"
                csp
                    .split(';')
                    .map { it.trim() }
                    .filter { it.startsWith("script-src") }
                    .size shouldBe 1
            }

            test("keeps a port of the ACS") {
                SamlPages.post("https://sp.example:8443/acs", "A", null).csp.shouldNotBeNull() shouldContain
                    "form-action https://sp.example:8443/acs;"
            }
        }

        context("the way back to this origin") {
            test("needs no script and keeps the closed policy") {
                val page = SamlPages.bounce("/saml/sso/1234")

                page.csp.shouldBeNull()
                page.html shouldContain "<meta http-equiv=\"refresh\" content=\"0;url=/saml/sso/1234\">"
                page.html shouldNotContain "<script"
            }
        }

        context("the error page") {
            test("is a client error that tells nothing of the request") {
                val page = SamlPages.error()

                page.status shouldBe 400
                page.csp.shouldBeNull()
            }
        }
    })
