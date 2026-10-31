import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.spring.boot) apply false
}

group = "idp"

// docker-compose.yml is the only place that names the Postgres image.
val postgresImage: String =
    Regex("""image: (postgres:\S+)""")
        .find(rootDir.resolve("../docker-compose.yml").readText())!!
        .groupValues[1]
val postgresInitScript: String =
    rootDir
        .resolve("../dev-tools/postgres/init.sql")
        .canonicalPath
val pgbouncerImage: String =
    Regex("""image: (edoburu/pgbouncer:\S+)""")
        .find(rootDir.resolve("../docker-compose.yml").readText())!!
        .groupValues[1]
val pgbouncerConfig: String =
    rootDir
        .resolve("../dev-tools/pgbouncer")
        .canonicalPath
val openbaoImage: String =
    Regex("""image: (openbao/openbao:\S+)""")
        .find(rootDir.resolve("../docker-compose.yml").readText())!!
        .groupValues[1]
val valkeyImage: String =
    Regex("""image: (valkey/valkey:\S+)""")
        .find(rootDir.resolve("../docker-compose.yml").readText())!!
        .groupValues[1]
val mailpitImage: String =
    Regex("""image: (axllent/mailpit:\S+)""")
        .find(rootDir.resolve("../docker-compose.yml").readText())!!
        .groupValues[1]

extra["postgresImage"] = postgresImage
extra["postgresInitScript"] = postgresInitScript

// `:modules` and `:audience` are only folders, so they get no build logic.
configure(subprojects.filter { it.childProjects.isEmpty() }) {
    apply(plugin = "java-library")
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain {
            languageVersion = JavaLanguageVersion.of(25)
            vendor = JvmVendorSpec.AMAZON
        }
    }

    dependencies {
        "testImplementation"(rootProject.libs.kotest.runner)
        "testImplementation"(rootProject.libs.kotest.assertions)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        systemProperty("idp.postgres.image", postgresImage)
        systemProperty("idp.postgres.init", postgresInitScript)
        systemProperty("idp.pgbouncer.image", pgbouncerImage)
        systemProperty("idp.pgbouncer.config", pgbouncerConfig)
        systemProperty("idp.openbao.image", openbaoImage)
        systemProperty("idp.valkey.image", valkeyImage)
        systemProperty("idp.mailpit.image", mailpitImage)
        inputs.dir(pgbouncerConfig).withPathSensitivity(PathSensitivity.RELATIVE)
    }
}
