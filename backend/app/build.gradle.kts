plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.kotlin.spring)
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.opentelemetry)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.jackson.module.kotlin)
    implementation(project(":shared-kernel"))
    implementation(project(":modules:tenancy"))
    implementation(project(":modules:identity"))
    implementation(project(":modules:authentication"))
    implementation(project(":modules:federation"))
    implementation(project(":modules:audit"))
    implementation(project(":audience:login"))
    implementation(project(":audience:account"))
    implementation(project(":audience:admin"))
    runtimeOnly(libs.postgresql)

    testImplementation(testFixtures(project(":shared-kernel")))
    testImplementation(testFixtures(project(":modules:federation")))
    testImplementation(libs.spring.test)
    testImplementation(libs.testcontainers)
    testImplementation(libs.webauthn4j.test)
    testImplementation(libs.webauthn4j.core)
    testImplementation(libs.opentelemetry.sdk.testing)
    testImplementation(libs.micrometer.registry.otlp)
}

tasks.bootJar {
    archiveFileName = "app.jar"
}

// The contract tests read the contracts, so Gradle must rerun them when a contract changes.
tasks.test {
    // The JDK HTTP client refuses to set Host otherwise.
    systemProperty("jdk.httpclient.allowRestrictedHeaders", "host")
    inputs.file(rootProject.file("../contracts/openapi/auth.yaml")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.file("../contracts/openapi/account.yaml")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.file("../contracts/openapi/admin.yaml")).withPathSensitivity(PathSensitivity.RELATIVE)
    // ProductionTelemetryTest builds the collector of the environments in AWS from this folder.
    val collector = rootDir.resolve("../infra/otel-collector").canonicalFile
    inputs.dir(collector).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("idp.collector.dir", collector.path)
}
