dependencies {
    implementation(project(":shared-kernel"))
    implementation(project(":modules:authentication"))
    implementation(project(":modules:identity"))
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.opensaml.saml.impl)
    implementation(libs.bouncycastle)
    implementation(libs.bouncycastle.pkix)
    compileOnly(libs.spring.web)

    testImplementation(testFixtures(project(":shared-kernel")))
    testImplementation(libs.spring.jdbc)
    testImplementation(libs.spring.web)
}
