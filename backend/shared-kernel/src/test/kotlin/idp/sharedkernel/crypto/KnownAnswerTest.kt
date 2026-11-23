package idp.sharedkernel.crypto

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.HexFormat
import java.util.UUID

private val HEX = HexFormat.of()
private val MATERIAL = ByteArray(32) { it.toByte() }
private val TENANT = UUID.fromString("00000000-0000-4000-8000-000000000001")
private val ROW = UUID.fromString("00000000-0000-4000-8000-000000000002")

/** Values computed once and kept: stored ciphertexts were sealed this way, so any change to the derivation fails here first. */
class KnownAnswerTest :
    FunSpec({
        val key = TenantKey(MATERIAL, TENANT)

        test("a derived key comes from the info prefix, the purpose and the tenant as they were") {
            mapOf(
                "flow" to "e746350f4435568b3430eb1af7a1d248625c5203080094afae308c70da7a584c",
                "audit" to "bc763a307889aeb68f8e2dc2794f4c1cc0f1e29368c3caffbeaf9e9773c543e7",
                "saml-signing" to "097a7d8cdbdbf6e7e0d9d182da93d01d296166a8199d40de19a1ed21bbedfc9e",
                "blind-index" to "5e67a5d5be3d1c3ca6fc4028fe999af30ca6200d2ddf2ec3dfd4b380d035f790",
                "user-keys" to "156372cec422f70ccd8b414664b21357c4f0499c6247342c85a079b9f22cf17a",
            ).forEach { (purpose, derived) -> HEX.formatHex(key.derive(purpose)) shouldBe derived }
        }

        test("a value sealed under each derived key, with its key version, still opens") {
            mapOf(
                DerivedKey.FLOW to "0100000001a6aaa44e1e6a6dbdc9290309ac427a49ccb13177cbb58eccaba05264aa6973144a6a4951f25884067d",
                DerivedKey.AUDIT to "0100000001cb5c0691f23a523b71eb78934d34f069f48e390df8b8221cee795ec6afbae15793291b24500f5da847",
                DerivedKey.SAML_SIGNING to "0100000001fff5d92e8d62565471487e9fa4262a74ce599f045d741c29673d1228091acf3d75c7a11a7b89aa1c4f",
            ).forEach { (derived, sealed) ->
                val opened = FieldCipher.of(key.keyset(derived.purpose)).decrypt(HEX.parseHex(sealed), FieldContext("f", ROW))

                opened.decodeToString() shouldBe "ada@acme.test"
            }
        }

        test("a user keyset wrapped by the tenant key still opens in its context") {
            val sealed = "01000000012f7fd621041f8457ab8b05264e0da78892dbe8cacf8b6e7a90186597f263f6009a47"

            String(key.userKeyContext(ROW)) shouldBe "user-keys/$TENANT/$ROW"
            key.userKeys.decrypt(HEX.parseHex(sealed), key.userKeyContext(ROW)).decodeToString() shouldBe "keyset"
        }

        test("the associated data of a field holds the row, then the name") {
            val associated = FieldContext("users.email_ciphertext", ROW).associatedData()

            HEX.formatHex(associated) shouldBe "0000000000004000800000000000000275736572732e656d61696c5f63697068657274657874"
        }

        test("the blind index of an address stays the same after folding, trimming and lowering") {
            val index = BlindIndex(ByteArray(32) { 7 }).email(" Ada@ＡＣＭＥ.test ")

            HEX.formatHex(index) shouldBe "3408b210c9d715a6d9bf9b0b841502dbde1209f5a04040497f2f0a1c35f31650"
        }
    })
