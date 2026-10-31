plugins {
    `java-test-fixtures`
}

dependencies {
    api(project(":db"))
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.spring.jdbc)
    implementation(libs.caffeine)

    testFixturesApi(platform(libs.spring.boot.bom))
    testFixturesApi(libs.postgresql)
    testFixturesImplementation(libs.testcontainers.postgresql)
}
