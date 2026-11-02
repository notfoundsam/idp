pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        // OpenSAML is not published to Maven Central.
        exclusiveContent {
            forRepository { maven("https://build.shibboleth.net/maven/releases") { name = "shibboleth" } }
            filter {
                includeGroup("org.opensaml")
                includeGroup("net.shibboleth")
            }
        }
    }
}

rootProject.name = "idp"

include(
    ":db",
    ":shared-kernel",
    ":modules:tenancy",
    ":modules:identity",
    ":modules:authentication",
    ":modules:federation",
    ":modules:audit",
    ":audience:login",
    ":audience:account",
    ":audience:admin",
    ":audience:operator",
    ":app",
    ":worker",
    ":ops",
    ":migrator",
    ":architecture-tests",
)
