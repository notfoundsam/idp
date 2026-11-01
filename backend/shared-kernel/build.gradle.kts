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
    implementation(libs.tink)
    implementation(libs.lettuce.core)
    implementation(libs.bucket4j.lettuce)
    implementation(libs.micrometer.core)
    compileOnly(libs.spring.web)
    compileOnly(libs.spring.context)
    compileOnly(libs.jakarta.servlet.api)

    testImplementation(libs.logback.classic)

    testFixturesApi(platform(libs.spring.boot.bom))
    testFixturesApi(libs.postgresql)
    testFixturesImplementation(libs.testcontainers.postgresql)
    testFixturesImplementation(libs.jackson.databind)
}

// LocalStack runs as its own CI job, so `check` needs nothing beyond Postgres, PgBouncer and OpenBao.
tasks.test {
    exclude("**/*LocalStackTest*")
}

tasks.register<Test>("localStackTest") {
    description = "Runs the KMS contract against LocalStack."
    group = "verification"
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/*LocalStackTest*")
}
