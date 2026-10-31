dependencies {
    testImplementation(project(":app"))
    testImplementation(project(":worker"))
    testImplementation(project(":ops"))
    testImplementation(project(":migrator"))
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.archunit)
    testImplementation(libs.spring.web)
}

// BuildGraphTest reads the build files, so Gradle must rerun it when they change.
tasks.test {
    inputs
        .files(
            rootProject.fileTree(".") {
                include("**/build.gradle.kts")
                exclude("**/build/**")
            },
        ).withPathSensitivity(PathSensitivity.RELATIVE)
}
