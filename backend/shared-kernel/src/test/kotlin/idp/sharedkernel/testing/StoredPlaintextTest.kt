package idp.sharedkernel.testing

import idp.sharedkernel.migration.TenantSchema
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.lettuce.core.RedisClient
import org.springframework.jdbc.core.JdbcTemplate
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

private const val EMAIL = "ada.lovelace@acme.test"
private const val TOKEN = "q7Vx2kLmN9pR4sT8wY1zB3cD"
private val SECRET = byteArrayOf(7, 1, 9, 3, 0, 4, 2, 8, 6, 5)
private const val GCM_TAG_BITS = 128
private const val GCM_NONCE_BYTES = 12

class StoredPlaintextTest :
    FunSpec({
        val email = Plaintext.text("email", EMAIL)
        val token = Plaintext.text("token", TOKEN)
        val secret = Plaintext.bytes("secret", SECRET)

        fun probe(): Pair<TestDatabase, JdbcTemplate> {
            val database = TestDatabase.migrated()
            val sql = JdbcTemplate(database.superuser())
            sql.execute("CREATE TABLE public.probe (id int, t text, j jsonb, b bytea, a text[], ba bytea[])")
            return database to sql
        }

        test("finds a value in clear in a column of every type, in any case, also beside other text") {
            val (database, sql) = probe()
            sql.update("INSERT INTO public.probe (id, t) VALUES (1, ?)", "mail to ${EMAIL.uppercase()} today")
            sql.update("INSERT INTO public.probe (id, j) VALUES (2, ?::jsonb)", """{"login":"$EMAIL"}""")
            sql.update("INSERT INTO public.probe (id, b) VALUES (3, ?)", "x$EMAIL".toByteArray())
            sql.update("INSERT INTO public.probe (id, a) VALUES (4, ARRAY['other', ?])", EMAIL)
            sql.update("INSERT INTO public.probe (id, ba) VALUES (5, ARRAY[?::bytea])", byteArrayOf(1) + SECRET)
            sql.update("INSERT INTO public.probe (id, b) VALUES (6, ?)", byteArrayOf(0) + SECRET + byteArrayOf(0))

            database.scan(listOf(email, secret)).findings shouldContainExactlyInAnyOrder
                listOf(
                    Finding("public.probe.t", "email"),
                    Finding("public.probe.j", "email"),
                    Finding("public.probe.b", "email"),
                    Finding("public.probe.a", "email"),
                    Finding("public.probe.ba", "secret"),
                    Finding("public.probe.b", "secret"),
                )
        }

        test("finds a value kept in base64 or hex, as text or as bytes") {
            val (database, sql) = probe()
            sql.update("INSERT INTO public.probe (id, t) VALUES (1, ?)", Base64.getEncoder().encodeToString(TOKEN.toByteArray()))
            sql.update(
                "INSERT INTO public.probe (id, b) VALUES (2, ?)",
                HexFormat
                    .of()
                    .formatHex(SECRET)
                    .uppercase()
                    .toByteArray(),
            )
            sql.update(
                "INSERT INTO public.probe (id, j) VALUES (3, ?::jsonb)",
                """{"s":"${Base64.getUrlEncoder().encodeToString(SECRET)}"}""",
            )

            database.scan(listOf(token, secret)).findings shouldContainExactlyInAnyOrder
                listOf(
                    Finding("public.probe.t", "token"),
                    Finding("public.probe.b", "secret"),
                    Finding("public.probe.j", "secret"),
                )
        }

        test("a hash or a ciphertext of the value is no finding") {
            val (database, sql) = probe()
            val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, ByteArray(GCM_NONCE_BYTES)))
            sql.update("INSERT INTO public.probe (id, b) VALUES (1, ?)", MessageDigest.getInstance("SHA-256").digest(TOKEN.toByteArray()))
            sql.update("INSERT INTO public.probe (id, b) VALUES (2, ?)", cipher.doFinal(EMAIL.toByteArray()))
            sql.update("INSERT INTO public.probe (id, t) VALUES (3, 'ada.lovelace@')")

            database.scan(listOf(email, token, secret)).findings.shouldBeEmpty()
        }

        test("reads the tables of the platform and of every tenant schema, with their rows") {
            val database = TestDatabase.migrated()
            val tenant = database.tenantSchema()
            val sql = JdbcTemplate(database.superuser())
            sql.update("INSERT INTO platform.tenants (id, name, status) VALUES (?, ?, 'ACTIVE')", tenant, EMAIL)

            val stored = database.scan(listOf(email))

            stored.findings shouldBe setOf(Finding("platform.tenants.name", "email"))
            stored.rows shouldContain ("platform.tenants" to 1)
            stored.rows shouldContain ("${TenantSchema(tenant).name}.users" to 0)
        }

        test("refuses a value too short to tell a leak from chance") {
            shouldThrow<IllegalArgumentException> { Plaintext.text("code", "123456") }.message shouldContain "code"
        }

        test("Valkey hands every key with its value, and refuses a type it cannot read") {
            TestValkey.start().use { valkey ->
                val key = "throttle:${UUID.randomUUID()}"
                val client = RedisClient.create(valkey.url)
                try {
                    client.connect().use { it.sync().set(key, EMAIL) }
                    valkey.entries().map { (name, value) -> name.decodeToString() to value.decodeToString() } shouldBe listOf(key to EMAIL)
                    email.foundIn(valkey.entries().single().second) shouldBe true

                    client.connect().use { it.sync().hset("other", "field", "value") }
                    shouldThrow<IllegalStateException> { valkey.entries() }
                } finally {
                    client.shutdown()
                }
            }
        }
    })
