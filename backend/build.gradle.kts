import org.cyclonedx.gradle.CyclonedxDirectTask
import org.cyclonedx.model.Component
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.cyclonedx)
}

group = "idp"

// The SBOM of a release lists what the jars carry, see docs/security/dependencies.md: the runtime classpath of each deployable as
// Gradle resolves it for that jar, so neither tools and tests nor a version that only a library would pick on its own.
val deployables = setOf(":app", ":worker", ":ops", ":migrator")

allprojects {
    val deployable = path in deployables
    tasks.named<CyclonedxDirectTask>("cyclonedxDirectBom") {
        enabled = deployable
        includeConfigs = listOf("runtimeClasspath")
        includeMetadataResolution = false
        xmlOutput.convention(null as RegularFile?)
    }
}

tasks.cyclonedxBom {
    projectType = Component.Type.APPLICATION
    xmlOutput.convention(null as RegularFile?)
}

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

val simpleSamlPhpImage: String =
    Regex("""image: (cirrusid/simplesamlphp:\S+)""")
        .find(rootDir.resolve("../docker-compose.yml").readText())!!
        .groupValues[1]
val simpleSamlPhpConfig: String =
    rootDir
        .resolve("../dev-tools/simplesamlphp")
        .canonicalPath

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
        // OpenSAML asks for versions with vulnerabilities past their time frame, see docs/security/dependencies.md.
        constraints {
            "implementation"(rootProject.libs.bouncycastle)
            "implementation"(rootProject.libs.bouncycastle.pkix)
            "implementation"(rootProject.libs.guava)
        }
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
        systemProperty("idp.simplesamlphp.image", simpleSamlPhpImage)
        systemProperty("idp.simplesamlphp.config", simpleSamlPhpConfig)
        inputs.dir(pgbouncerConfig).withPathSensitivity(PathSensitivity.RELATIVE)
        inputs
            .files(fileTree(simpleSamlPhpConfig) { exclude("metadata/**") })
            .withPathSensitivity(PathSensitivity.RELATIVE)
    }
}
