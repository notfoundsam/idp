plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.kotlin.spring)
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(project(":shared-kernel"))
    implementation(project(":modules:tenancy"))
    implementation(project(":modules:identity"))
    implementation(project(":modules:authentication"))
    implementation(project(":modules:federation"))
    implementation(project(":modules:audit"))
    implementation(project(":audience:login"))
    implementation(project(":audience:account"))
    implementation(project(":audience:admin"))
    runtimeOnly(libs.postgresql)

    testImplementation(testFixtures(project(":shared-kernel")))
}

tasks.bootJar {
    archiveFileName = "app.jar"
}

tasks.test {
    // The JDK HTTP client refuses to set Host otherwise.
    systemProperty("jdk.httpclient.allowRestrictedHeaders", "host")
}
