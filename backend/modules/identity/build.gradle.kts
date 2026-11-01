dependencies {
    implementation(project(":shared-kernel"))
    implementation(libs.bouncycastle)

    testImplementation(testFixtures(project(":shared-kernel")))
    testImplementation(libs.spring.jdbc)
}
