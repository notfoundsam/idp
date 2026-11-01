package idp.app.web

import idp.app.AppApplication
import idp.authentication.application.SessionCookie
import idp.sharedkernel.audit.Actor
import idp.sharedkernel.migration.TenantMigrationRunner
import idp.sharedkernel.migration.TenantSchema
import idp.sharedkernel.migration.TenantSchemaFactory
import idp.sharedkernel.security.SecurityContext
import idp.sharedkernel.testing.TestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldBeEmpty
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DelegatingDataSource
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

private const val ANONYMOUS = "anonymous"
private const val ACME = "acme.idp.test"
private const val SUSPENDED = "suspended.idp.test"

@RestController
private class SessionProbeController {
    @GetMapping("/api/probe", "/saml/probe", "/open/probe")
    fun probe(): String = SecurityContext.currentOrNull()?.let { "${(it.actor as Actor.User).id} ${it.role.name}" } ?: ANONYMOUS
}

private object Connections {
    val taken = AtomicInteger()
}

private class CountedDataSource(
    target: DataSource,
) : DelegatingDataSource(target) {
    override fun getConnection(): Connection {
        Connections.taken.incrementAndGet()
        return super.getConnection()
    }
}

private class CountedDataSources : BeanPostProcessor {
    override fun postProcessAfterInitialization(
        bean: Any,
        beanName: String,
    ): Any = if (bean is DataSource) CountedDataSource(bean) else bean
}

class SessionFilterTest :
    FunSpec({
        val database = TestDatabase.create()
        val owner = database.owner()
        val sql = JdbcTemplate(owner)
        val acme: UUID = UUID.randomUUID()
        val suspended: UUID = UUID.randomUUID()

        fun tenant(
            id: UUID,
            host: String,
            status: String,
        ) {
            sql.update("INSERT INTO platform.tenants (id, name, status, kek_uri) VALUES (?, 'tenant', ?, 'hcvault://tenant')", id, status)
            sql.update("INSERT INTO platform.tenant_domains (domain, tenant_id) VALUES (?, ?)", host, id)
            TenantSchemaFactory(owner).create(id)
            TenantMigrationRunner(owner).migrateTenant(id)
        }

        fun table(
            tenant: UUID,
            name: String,
        ) = "\"${TenantSchema(tenant).name}\".$name"

        fun user(
            tenant: UUID = acme,
            role: String = "USER",
            status: String = "ACTIVE",
        ): UUID {
            val id = UUID.randomUUID()
            val index = id.toString().toByteArray()
            sql.update(
                "INSERT INTO ${table(tenant, "users")} (id, email_index, email_ciphertext, role, status) VALUES (?, ?, ?, ?, ?)",
                id,
                index,
                index,
                role,
                status,
            )
            return id
        }

        fun session(
            user: UUID,
            tenant: UUID = acme,
        ): String {
            val token = UUID.randomUUID().toString()
            val hash = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
            sql.update(
                """
                INSERT INTO ${table(tenant, "sso_sessions")} (id, user_id, token_hash, factors, authenticated_at, last_seen_at)
                VALUES (?, ?, ?, '{PASSWORD}', now(), now())
                """.trimIndent(),
                UUID.randomUUID(),
                user,
                hash,
            )
            return token
        }

        TenantMigrationRunner(owner).migrateAll()
        tenant(acme, ACME, "ACTIVE")
        tenant(suspended, SUSPENDED, "SUSPENDED")
        database.pooled("app_runtime")

        val application =
            SpringApplication(AppApplication::class.java, CountedDataSources::class.java).run(
                "--server.port=0",
                "--management.server.port=0",
                "--spring.datasource.url=${database.pooledUrl()}",
                "--spring.datasource.username=app_runtime",
                "--spring.datasource.password=app_runtime",
                "--THROTTLE_ENABLED=false",
                "--KMS_NEW_KEYS=hcvault",
                "--OPENBAO_ADDRESS=http://127.0.0.1:1",
            )

        afterSpec { application.close() }

        fun get(
            path: String,
            token: String? = null,
            host: String = ACME,
        ): HttpResponse<String> {
            val port = application.environment.getProperty("local.server.port")
            val request = HttpRequest.newBuilder(URI("http://localhost:$port$path")).header("Host", host)
            token?.let { request.header("Cookie", "theme=dark; ${SessionCookie.NAME}=$it") }
            return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString())
        }

        test("a live session fills the context on the API") {
            val user = user()

            get("/api/probe", session(user)).body() shouldBe "$user USER"
        }

        test("a live session fills the context on the SAML endpoints") {
            val user = user()

            get("/saml/probe", session(user)).body() shouldBe "$user USER"
        }

        test("other paths never see the session") {
            get("/open/probe", session(user())).body() shouldBe ANONYMOUS
        }

        test("a request without the cookie stays anonymous") {
            get("/api/probe").body() shouldBe ANONYMOUS
        }

        test("an unknown token stays anonymous") {
            get("/api/probe", "made-up").body() shouldBe ANONYMOUS
        }

        test("a session past its idle timeout stays anonymous") {
            val user = user()
            val token = session(user)

            sql.update("UPDATE ${table(acme, "sso_sessions")} SET last_seen_at = now() - interval '31 minutes' WHERE user_id = ?", user)

            get("/api/probe", token).body() shouldBe ANONYMOUS
        }

        test("a session past its absolute timeout stays anonymous") {
            val user = user()
            val token = session(user)

            sql.update("UPDATE ${table(acme, "sso_sessions")} SET authenticated_at = now() - interval '13 hours' WHERE user_id = ?", user)

            get("/api/probe", token).body() shouldBe ANONYMOUS
        }

        test("a revoked session stays anonymous") {
            val user = user()
            val token = session(user)

            sql.update("UPDATE ${table(acme, "sso_sessions")} SET revoked_at = now() WHERE user_id = ?", user)

            get("/api/probe", token).body() shouldBe ANONYMOUS
        }

        test("a lower role applies from the next request") {
            val user = user(role = "TENANT_ADMIN")
            val token = session(user)
            get("/api/probe", token).body() shouldBe "$user TENANT_ADMIN"

            sql.update("UPDATE ${table(acme, "users")} SET role = 'USER' WHERE id = ?", user)

            get("/api/probe", token).body() shouldBe "$user USER"
        }

        test("a blocked user stays anonymous from the next request") {
            val user = user()
            val token = session(user)
            get("/api/probe", token).body() shouldBe "$user USER"

            sql.update("UPDATE ${table(acme, "users")} SET status = 'BLOCKED' WHERE id = ?", user)

            get("/api/probe", token).body() shouldBe ANONYMOUS
        }

        test("a token of another tenant stays anonymous") {
            val outside = session(user(suspended), suspended)

            get("/api/probe", outside).body() shouldBe ANONYMOUS
        }

        test("the status of a suspended tenant closes the door before the session is read") {
            val token = session(user(suspended), suspended)

            val response = get("/api/probe", token, SUSPENDED)

            response.statusCode() shouldBe 403
            response.body().shouldBeEmpty()
        }

        test("an unknown host gets no further with a cookie") {
            get("/api/probe", "made-up", "nobody.idp.test").statusCode() shouldBe 404
        }

        test("the context ends with the request") {
            val user = user()
            get("/api/probe", session(user)).body() shouldBe "$user USER"

            get("/api/probe").body() shouldBe ANONYMOUS
        }

        test("without the cookie the database is not touched") {
            get("/api/probe")
            val before = Connections.taken.get()

            get("/api/probe").body() shouldBe ANONYMOUS

            Connections.taken.get() shouldBe before
        }

        test("the count above would see a request that reads a session") {
            val token = session(user())
            get("/api/probe")
            val before = Connections.taken.get()

            get("/api/probe", token)

            Connections.taken.get() shouldBeGreaterThan before
        }
    })
