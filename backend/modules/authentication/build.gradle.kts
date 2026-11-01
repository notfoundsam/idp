dependencies {
    implementation(project(":shared-kernel"))
    implementation(project(":modules:identity"))

    testImplementation(testFixtures(project(":shared-kernel")))
    testImplementation(libs.spring.jdbc)
}
