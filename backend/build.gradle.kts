import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ktlint) apply false
}

group = "idp"

// `:modules` and `:audience` are only folders, so they get no build logic.
configure(subprojects.filter { it.childProjects.isEmpty() }) {
    apply(plugin = "java-library")
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(25)
    }

    dependencies {
        "testImplementation"(rootProject.libs.kotest.runner)
        "testImplementation"(rootProject.libs.kotest.assertions)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
