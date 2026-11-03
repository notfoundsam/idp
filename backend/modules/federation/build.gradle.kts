plugins {
    `java-test-fixtures`
}

dependencies {
    implementation(project(":shared-kernel"))
    implementation(project(":modules:authentication"))
    implementation(project(":modules:identity"))
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.opensaml.saml.impl)
    implementation(libs.bouncycastle)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.slf4j.api)
    compileOnly(libs.spring.web)
    compileOnly(libs.jakarta.servlet.api)

    testImplementation(testFixtures(project(":shared-kernel")))
    testImplementation(libs.spring.jdbc)
    testImplementation(libs.spring.web)
    testImplementation(libs.logback.classic)

    testFixturesImplementation(libs.opensaml.saml.impl)
    testFixturesImplementation(libs.bouncycastle)
    testFixturesImplementation(libs.bouncycastle.pkix)
}
