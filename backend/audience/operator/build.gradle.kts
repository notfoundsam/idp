dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(project(":shared-kernel"))
    implementation(project(":modules:tenancy"))
    compileOnly(libs.spring.web)
}
