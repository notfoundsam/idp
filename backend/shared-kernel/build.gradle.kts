plugins {
    `java-test-fixtures`
}

dependencies {
    api(project(":db"))
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.spring.jdbc)
    implementation(libs.caffeine)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.slf4j.api)
    implementation(libs.aws.sdk.kms)
    implementation(libs.aws.sdk.sts)
    implementation(libs.aws.sdk.s3)
    implementation(libs.tink)
    implementation(libs.lettuce.core)
    implementation(libs.bucket4j.lettuce)
    implementation(libs.micrometer.core)
    implementation(libs.opentelemetry.api)
    compileOnly(libs.logback.classic)
    compileOnly(libs.spring.web)
    compileOnly(libs.spring.context)
    compileOnly(libs.jakarta.servlet.api)

    testImplementation(libs.logback.classic)
    testImplementation(libs.testcontainers)

    testFixturesApi(platform(libs.spring.boot.bom))
    testFixturesApi(libs.postgresql)
    testFixturesImplementation(libs.testcontainers.postgresql)
    testFixturesImplementation(libs.jackson.databind)
    testFixturesImplementation(libs.kotest.assertions)
    testFixturesImplementation(libs.kotest.runner)
    testFixturesImplementation(libs.snakeyaml)
    testFixturesImplementation(libs.lettuce.core)
    testFixturesImplementation(libs.bouncycastle)
    testFixturesImplementation(libs.bouncycastle.pkix)
    // Compile only: on the runtime classpath of `worker` tests it would turn their application into a web one.
    testFixturesCompileOnly(libs.spring.webmvc)
    testFixturesCompileOnly(libs.spring.web)
    testFixturesCompileOnly(libs.spring.context)
    testFixturesCompileOnly(libs.jakarta.servlet.api)
}

// AWS Emulator runs as its own CI job, so `check` needs nothing beyond Postgres, PgBouncer and OpenBao.
tasks.test {
    exclude("**/*EmulatorTest*")
}

tasks.register<Test>("awsEmulatorTest") {
    description = "Runs the KMS, erasure journal and caller identity adapters against AWS Emulator."
    group = "verification"
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/*EmulatorTest*")
    val emulator = rootDir.resolve("../dev-tools/aws-emulator").canonicalFile
    inputs.dir(emulator).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("idp.awsEmulator.dir", emulator.path)
}
