dependencies {
    implementation(project(":shared-kernel"))
    implementation(project(":modules:tenancy"))
    implementation(project(":modules:identity"))
    implementation(project(":modules:authentication"))
    implementation(project(":modules:federation"))
    implementation(project(":modules:audit"))
    implementation(project(":audience:login"))
    implementation(project(":audience:account"))
    implementation(project(":audience:admin"))
}
