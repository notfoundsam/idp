plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.kotlin.spring)
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(project(":shared-kernel"))
    implementation(project(":modules:tenancy"))
    implementation(project(":modules:identity"))
    implementation(project(":modules:audit"))
    runtimeOnly(libs.postgresql)

    testImplementation(testFixtures(project(":shared-kernel")))
    testImplementation(libs.testcontainers)
    testImplementation(libs.bouncycastle)
    testImplementation(libs.bouncycastle.pkix)
}

tasks.bootJar {
    archiveFileName = "migrator.jar"
}

tasks.test {
    val jar = tasks.bootJar.flatMap { it.archiveFile }
    inputs.file(jar).withPathSensitivity(PathSensitivity.NONE)
    jvmArgumentProviders.add { listOf("-Didp.migrator.jar=${jar.get().asFile}") }
    // ProductionDatabaseTest builds the images of the database in AWS from these folders.
    val infra = rootDir.resolve("../infra").canonicalFile
    inputs.dir(infra.resolve("db-bootstrap")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(infra.resolve("pgbouncer")).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("idp.infra.dir", infra.path)
}
