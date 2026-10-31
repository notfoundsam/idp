package idp.sharedkernel.web

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

private const val HEADER = "CloudFront-Viewer-Address"
private const val PEER = "10.0.0.5"

class ClientIpTest :
    FunSpec({
        fun resolve(
            trusted: String?,
            vararg headers: Pair<String, String>,
        ): ClientIp = ClientIpResolver(trusted).resolve(PEER) { name -> headers.toMap()[name] }

        test("without a trusted header the address is the peer of the socket") {
            resolve(null, HEADER to "203.0.113.7:50000").address shouldBe PEER
        }

        test("a forged X-Forwarded-For changes nothing") {
            resolve(HEADER, "X-Forwarded-For" to "198.51.100.9", HEADER to "203.0.113.7:50000").address shouldBe "203.0.113.7"
            resolve(null, "X-Forwarded-For" to "198.51.100.9").address shouldBe PEER
        }

        test("the trusted header is whatever the setting names") {
            resolve("X-Edge-Address", "X-Edge-Address" to "203.0.113.7:50000", HEADER to "198.51.100.9:50000").address shouldBe
                "203.0.113.7"
        }

        test("an IPv6 address comes with or without brackets") {
            resolve(HEADER, HEADER to "[2001:db8::1]:50000").address shouldBe "2001:db8:0:0:0:0:0:1"
            resolve(HEADER, HEADER to "2001:db8::1:50000").address shouldBe "2001:db8:0:0:0:0:0:1"
        }

        test("a broken header falls back to the peer, so it cannot name an arbitrary bucket") {
            listOf("", "not-an-address", "evil.example.com:443", "203.0.113.7", "203.0.113.7:50000, 198.51.100.9:1").forEach {
                resolve(HEADER, HEADER to it).address shouldBe PEER
            }
        }

        test("a missing header falls back to the peer") {
            resolve(HEADER).address shouldBe PEER
        }

        test("the address ends with its scope") {
            ClientIp.with(ClientIp(PEER)) { ClientIp.currentOrNull()?.address } shouldBe PEER

            ClientIp.currentOrNull().shouldBeNull()
        }
    })
