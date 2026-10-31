dependencies {
    implementation(project(":shared-kernel"))

    testImplementation(testFixtures(project(":shared-kernel")))
    testImplementation(libs.spring.jdbc)
}
