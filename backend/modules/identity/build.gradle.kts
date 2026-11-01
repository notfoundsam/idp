dependencies {
    implementation(project(":shared-kernel"))
    implementation(libs.bouncycastle)
    implementation(libs.webauthn4j.core)

    testImplementation(testFixtures(project(":shared-kernel")))
    testImplementation(libs.spring.jdbc)
    testImplementation(libs.webauthn4j.test)
}
