dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(project(":shared-kernel"))
    implementation(project(":modules:authentication"))
    implementation(project(":modules:identity"))
    implementation(project(":modules:federation"))
    implementation(project(":modules:audit"))
    compileOnly(libs.spring.web)
}
