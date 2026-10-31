package idp.db.codegen

import org.flywaydb.core.Flyway
import org.jooq.codegen.GenerationTool
import org.jooq.meta.jaxb.Configuration
import org.jooq.meta.jaxb.Database
import org.jooq.meta.jaxb.Generate
import org.jooq.meta.jaxb.Generator
import org.jooq.meta.jaxb.Jdbc
import org.jooq.meta.jaxb.Target
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.MountableFile
import java.sql.DriverManager

private const val TEMPLATE = "tenant_template"

fun main(args: Array<String>) {
    val (migrations, output, image, initScript) = args
    PostgreSQLContainer(image)
        .withDatabaseName("idp")
        .withUsername("postgres")
        .withCopyFileToContainer(MountableFile.forHostPath(initScript), "/docker-entrypoint-initdb.d/init.sql")
        .use { postgres ->
            postgres.start()
            val url = postgres.jdbcUrl

            fun migrate(
                schema: String,
                location: String,
            ) = Flyway
                .configure()
                .dataSource(url, "idp_owner", "idp_owner")
                .locations("filesystem:$migrations/$location")
                .defaultSchema(schema)
                .schemas(schema)
                .load()
                .migrate()

            fun generate(
                schema: String,
                packageName: String,
                outputToDefault: Boolean,
            ) = GenerationTool.generate(
                Configuration()
                    .withJdbc(
                        Jdbc()
                            .withUrl(url)
                            .withUser(postgres.username)
                            .withPassword(postgres.password),
                    ).withGenerator(
                        Generator()
                            .withDatabase(
                                Database()
                                    .withName("org.jooq.meta.postgres.PostgresDatabase")
                                    .withInputSchema(schema)
                                    .withOutputSchemaToDefault(outputToDefault)
                                    .withExcludes("flyway_schema_history"),
                            ).withGenerate(
                                // Global references would let code reach a table without naming its class,
                                // and the table owner architecture rule looks for that class.
                                Generate()
                                    .withGlobalTableReferences(false)
                                    .withGlobalKeyReferences(false)
                                    .withGlobalRoutineReferences(false)
                                    .withGlobalSequenceReferences(false),
                            ).withTarget(Target().withPackageName(packageName).withDirectory(output)),
                    ),
            )

            migrate("platform", "platform")
            DriverManager.getConnection(url, postgres.username, postgres.password).use {
                it.createStatement().use { statement ->
                    statement.execute("CREATE ROLE $TEMPLATE NOLOGIN")
                    statement.execute("CREATE SCHEMA $TEMPLATE AUTHORIZATION idp_owner")
                }
            }
            migrate(TEMPLATE, "tenant")

            generate("platform", "idp.db.jooq.platform", outputToDefault = false)
            // Without a schema name the SQL resolves through search_path, set per transaction.
            generate(TEMPLATE, "idp.db.jooq.tenant", outputToDefault = true)
        }
}
