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
    runtimeOnly(libs.postgresql)

    testImplementation(testFixtures(project(":shared-kernel")))
}

tasks.bootJar {
    archiveFileName = "migrator.jar"
}

tasks.test {
    val jar = tasks.bootJar.flatMap { it.archiveFile }
    inputs.file(jar).withPathSensitivity(PathSensitivity.NONE)
    jvmArgumentProviders.add { listOf("-Didp.migrator.jar=${jar.get().asFile}") }
}
