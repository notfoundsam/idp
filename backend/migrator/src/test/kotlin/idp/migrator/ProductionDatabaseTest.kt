package idp.migrator

import idp.sharedkernel.testing.TestCa
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.postgresql.util.PSQLException
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.images.builder.Transferable
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.util.Properties
import kotlin.io.path.listDirectoryEntries

private const val PORT = 5432
private const val OWNER_PASSWORD = "owner-secret"
private val RUNTIME_ROLES = listOf("app_runtime", "worker_runtime", "ops_runtime")

// What RDS hands over: a master user with CREATEROLE and CREATEDB that owns the database and is no superuser.
private const val MASTER_USER =
    """
    CREATE ROLE master LOGIN CREATEROLE CREATEDB PASSWORD 'master';
    CREATE DATABASE idp OWNER master;
    """

// rds.force_ssl: every connection over the network needs TLS.
private const val HBA =
    """
    local all all trust
    hostssl all all all scram-sha-256
    host all all all reject
    """

/**
 * The database of `infra/terraform` on a Postgres that behaves like RDS: the images of `infra/db-bootstrap` and `infra/pgbouncer`
 * with a read-only root as in ECS, and the JDBC settings the services get, with `sslmode=verify-full` on every connection.
 */
class ProductionDatabaseTest :
    StringSpec({
        val infra = Path.of(System.getProperty("idp.infra.dir"))
        val network = Network.newNetwork()
        val rdsCa = TestCa("Test RDS Root CA")
        val internalCa = TestCa("Test internal CA")
        val otherCa = TestCa("Test other CA")
        val passwords = (RUNTIME_ROLES + "provisioner").associateWith { "$it-secret" }.toMutableMap()

        val postgres: GenericContainer<*> =
            rdsCa.issue("postgres", "localhost").let { (certificate, key) ->
                GenericContainer(System.getProperty("idp.postgres.image"))
                    .withNetwork(network)
                    .withNetworkAliases("postgres")
                    .withExposedPorts(PORT)
                    .withEnv("POSTGRES_PASSWORD", "postgres")
                    .withCopyToContainer(Transferable.of(certificate), "/tls/server.crt")
                    .withCopyToContainer(Transferable.of(key), "/tls/server.key")
                    .withCopyToContainer(Transferable.of(HBA.trimIndent()), "/tls/pg_hba.conf")
                    .withCopyToContainer(Transferable.of(MASTER_USER.trimIndent()), "/docker-entrypoint-initdb.d/master.sql")
                    // Postgres reads its key only when the key belongs to it, and a copied file belongs to root.
                    .withCommand(
                        "sh",
                        "-c",
                        "install -o postgres -m 0600 /tls/server.key /tmp/server.key && exec docker-entrypoint.sh postgres " +
                            "-c fsync=off -c ssl=on -c ssl_min_protocol_version=TLSv1.2 -c ssl_cert_file=/tls/server.crt " +
                            "-c ssl_key_file=/tmp/server.key -c hba_file=/tls/pg_hba.conf",
                    ).waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2))
            }
        val bootstrapImage = ImageFromDockerfile().withFileFromPath(".", infra.resolve("db-bootstrap"))
        val pgbouncerImage = ImageFromDockerfile().withFileFromPath(".", infra.resolve("pgbouncer"))

        fun bootstrap(
            vararg command: String,
            environment: Map<String, String>,
        ) = GenericContainer(bootstrapImage)
            .withNetwork(network)
            .withEnv(mapOf("PGHOST" to "postgres", "PGDATABASE" to "idp", "DB_TLS_CA" to rdsCa.pem) + environment)
            .withCommand(*command)
            .withCreateContainerCmdModifier { it.hostConfig?.withReadonlyRootfs(true) }
            .withStartupCheckStrategy(OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(1)))
            .use { it.start() }

        fun bootstrapOwner(provisionerPassword: String) =
            bootstrap(
                "/usr/local/lib/idp/bootstrap-owner.sh",
                environment =
                    mapOf(
                        "PGUSER" to "master",
                        "PGPASSWORD" to "master",
                        "IDP_OWNER_PASSWORD" to OWNER_PASSWORD,
                        "PROVISIONER_PASSWORD" to provisionerPassword,
                    ),
            )

        fun setRolePasswords(environment: Map<String, String>) =
            bootstrap(
                "/usr/local/lib/idp/set-role-passwords.sh",
                *RUNTIME_ROLES.toTypedArray(),
                environment = mapOf("PGUSER" to "idp_owner", "PGPASSWORD" to OWNER_PASSWORD) + environment,
            )

        fun pgbouncer(serverCa: String): GenericContainer<*> {
            val (certificate, key) = internalCa.issue("pgbouncer", "localhost")
            return GenericContainer(pgbouncerImage)
                .withNetwork(network)
                .withExposedPorts(PORT)
                .withEnv(
                    mapOf(
                        "DB_HOST" to "postgres",
                        "DB_NAME" to "idp",
                        "CLIENT_TLS_CERT" to certificate,
                        "CLIENT_TLS_KEY" to key,
                        "SERVER_TLS_CA" to serverCa,
                    ) + RUNTIME_ROLES.associate { it.uppercase() + "_PASSWORD" to passwords.getValue(it) },
                ).withCreateContainerCmdModifier { it.hostConfig?.withReadonlyRootfs(true) }
                .waitingFor(Wait.forListeningPort())
        }

        val pooler = pgbouncer(rdsCa.pem)

        fun connect(
            host: String,
            port: Int,
            user: String,
            password: String,
            ca: String,
            sslmode: String = "verify-full",
            options: String? = null,
        ): Connection =
            DriverManager.getConnection(
                "jdbc:postgresql://$host:$port/idp?prepareThreshold=0",
                Properties().apply {
                    this["user"] = user
                    this["password"] = password
                    this["sslmode"] = sslmode
                    this["sslfactory"] = "org.postgresql.ssl.SingleCertValidatingFactory"
                    this["sslfactoryarg"] = ca
                    this["connectTimeout"] = "5"
                    // PgBouncer keeps a query waiting for a server connection for two minutes.
                    this["socketTimeout"] = "5"
                    options?.let { this["options"] = it }
                },
            )

        fun direct(
            user: String,
            password: String,
            ca: String = rdsCa.pem,
            options: String? = null,
        ) = connect("localhost", postgres.getMappedPort(PORT), user, password, ca, options = options)

        fun pooled(
            role: String,
            container: GenericContainer<*> = pooler,
            host: String = "localhost",
            ca: String = internalCa.pem,
            sslmode: String = "verify-full",
        ) = connect(host, container.getMappedPort(PORT), role, passwords.getValue(role), ca, sslmode)

        fun Connection.single(sql: String): String =
            use {
                it.createStatement().use { statement ->
                    statement.executeQuery(sql).use { rows ->
                        rows.next()
                        rows.getString(1)
                    }
                }
            }

        // The URL form infra/terraform hands every service: the one certificate it trusts comes from a variable.
        fun migrator(
            ca: String,
            vararg settings: Pair<String, String>,
        ): Int =
            ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar",
                System.getProperty("idp.migrator.jar"),
            ).apply {
                environment() +=
                    mapOf(
                        "DB_URL" to
                            "jdbc:postgresql://localhost:${postgres.getMappedPort(PORT)}/idp?sslmode=verify-full" +
                            "&sslfactory=org.postgresql.ssl.SingleCertValidatingFactory&sslfactoryarg=env:DB_TLS_CA",
                        "DB_TLS_CA" to ca,
                        "DB_USERNAME" to "idp_owner",
                        "DB_PASSWORD" to OWNER_PASSWORD,
                    ) + settings
                redirectErrorStream(true)
                redirectOutput(ProcessBuilder.Redirect.DISCARD)
            }.start()
                .waitFor()

        beforeSpec {
            postgres.start()
            bootstrapOwner(passwords.getValue("provisioner"))
            check(migrator(rdsCa.pem) == 0) { "migrator failed on the bootstrapped database" }
            setRolePasswords(RUNTIME_ROLES.associate { it.uppercase() + "_PASSWORD" to passwords.getValue(it) })
            pooler.start()
        }

        afterSpec {
            pooler.stop()
            postgres.stop()
            network.close()
        }

        "the bootstrap hands the database to idp_owner, and migrator migrates as it over verify-full" {
            direct("master", "master").single(
                "SELECT pg_get_userbyid(datdba) || ' ' || pg_has_role('master', 'idp_owner', 'SET') " +
                    "FROM pg_database WHERE datname = 'idp'",
            ) shouldBe "idp_owner false"
            direct("idp_owner", OWNER_PASSWORD)
                .single("SELECT count(*) FROM platform.flyway_schema_history WHERE success AND version IS NOT NULL")
                .toInt() shouldBe Path.of("../db/src/main/resources/db/platform").listDirectoryEntries("V*.sql").size

            migrator(rdsCa.pem) shouldBe 0
        }

        "every runtime role reaches Postgres through PgBouncer, which speaks TLS to Postgres as well" {
            RUNTIME_ROLES.forEach { role ->
                pooled(role).single(
                    "SELECT current_user || ' ' || ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()",
                ) shouldBe "$role true"
            }
        }

        "the health check of the ECS task finds PgBouncer ready" {
            pooler.execInContainer("pg_isready", "-h", "127.0.0.1", "-p", "5432", "-q").exitCode shouldBe 0
        }

        "the provisioner connects directly and acts as idp_owner" {
            direct("provisioner", passwords.getValue("provisioner"), options = "-c role=idp_owner")
                .single("SELECT current_user") shouldBe "idp_owner"
        }

        "a repeated bootstrap replaces the passwords" {
            val old = passwords.getValue("provisioner")
            passwords["provisioner"] = "provisioner-rotated"

            bootstrapOwner(passwords.getValue("provisioner"))

            direct("provisioner", passwords.getValue("provisioner")).single("SELECT current_user") shouldBe "provisioner"
            shouldThrow<PSQLException> { direct("provisioner", old).close() }
        }

        "both steps refuse a missing password and change nothing" {
            shouldThrowAny { bootstrapOwner("") }
            shouldThrowAny { setRolePasswords(mapOf("WORKER_RUNTIME_PASSWORD" to "changed", "OPS_RUNTIME_PASSWORD" to "changed")) }

            direct("provisioner", passwords.getValue("provisioner")).single("SELECT current_user") shouldBe "provisioner"
            pooled("worker_runtime").single("SELECT current_user") shouldBe "worker_runtime"
        }

        "PgBouncer refuses a client without TLS" {
            shouldThrow<PSQLException> { pooled("app_runtime", sslmode = "disable").close() }
        }

        "clients refuse a server under another name or with a certificate from another CA" {
            shouldThrow<PSQLException> { pooled("app_runtime", host = "127.0.0.1").close() }
            shouldThrow<PSQLException> { pooled("app_runtime", ca = otherCa.pem).close() }
            shouldThrow<PSQLException> { direct("idp_owner", OWNER_PASSWORD, ca = otherCa.pem).close() }
            migrator(otherCa.pem, "SPRING_DATASOURCE_HIKARI_CONNECTIONTIMEOUT" to "250") shouldBe 1
        }

        "PgBouncer refuses Postgres when the CA it is given did not sign the certificate" {
            pgbouncer(otherCa.pem).use { untrusting ->
                untrusting.start()

                shouldThrow<PSQLException> { pooled("app_runtime", container = untrusting).single("SELECT 1") }
                untrusting.logs shouldContain "certificate verify failed"
            }
        }
    })
