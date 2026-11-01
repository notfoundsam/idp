plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.kotlin.spring)
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.jackson.module.kotlin)
    implementation(project(":shared-kernel"))
    implementation(project(":modules:tenancy"))
    implementation(project(":modules:audit"))
    implementation(project(":audience:operator"))
    runtimeOnly(libs.postgresql)

    testImplementation(testFixtures(project(":shared-kernel")))
}

tasks.bootJar {
    archiveFileName = "ops.jar"
}

// OpsContractTest reads the contract, so Gradle must rerun it when the contract changes.
tasks.test {
    inputs.file(rootProject.file("../contracts/openapi/ops.yaml")).withPathSensitivity(PathSensitivity.RELATIVE)
}
