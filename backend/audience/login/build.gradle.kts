dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(project(":shared-kernel"))
    implementation(project(":modules:authentication"))
    implementation(project(":modules:identity"))
    compileOnly(libs.spring.web)
    compileOnly(libs.jakarta.servlet.api)
}
