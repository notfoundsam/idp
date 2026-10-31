dependencies {
    implementation(project(":shared-kernel"))
    implementation(libs.jackson.databind)

    testImplementation(testFixtures(project(":shared-kernel")))
    testImplementation(libs.caffeine)
    testImplementation(libs.spring.jdbc)
}
