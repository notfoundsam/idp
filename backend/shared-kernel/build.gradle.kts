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

// LocalStack runs as its own CI job, so `check` needs nothing beyond Postgres, PgBouncer and OpenBao.
tasks.test {
    exclude("**/*LocalStackTest*")
}

tasks.register<Test>("localStackTest") {
    description = "Runs the KMS and erasure journal contracts against LocalStack."
    group = "verification"
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/*LocalStackTest*")
}
