// Owns all Flyway migrations (db/platform, db/tenant) and jOOQ generation.
val codegen: SourceSet by sourceSets.creating

dependencies {
    api(platform(libs.spring.boot.bom))
    api(libs.jooq)

    "codegenImplementation"(platform(libs.spring.boot.bom))
    "codegenImplementation"(libs.jooq.codegen)
    "codegenImplementation"(libs.flyway.core)
    "codegenImplementation"(libs.flyway.postgresql)
    "codegenImplementation"(libs.postgresql)
    "codegenImplementation"(libs.testcontainers.postgresql)
}

val migrations = layout.projectDirectory.dir("src/main/resources/db")
val jooqOutput = layout.buildDirectory.dir("generated-sources/jooq")
val postgresImage = rootProject.extra["postgresImage"] as String
val postgresInitScript = rootProject.file(rootProject.extra["postgresInitScript"] as String)

val generateJooq by tasks.registering(JavaExec::class) {
    description = "Applies the migrations to a temporary Postgres and generates jOOQ code from the result."
    classpath = codegen.runtimeClasspath
    mainClass = "idp.db.codegen.GenerateJooqKt"
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
    inputs.dir(migrations).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(postgresInitScript).withPathSensitivity(PathSensitivity.NONE)
    inputs.property("postgresImage", postgresImage)
    outputs.dir(jooqOutput)
    outputs.cacheIf { true }
    args(migrations.asFile.path, jooqOutput.get().asFile.path, postgresImage, postgresInitScript.path)
}

sourceSets.main {
    java.srcDir(generateJooq)
}
