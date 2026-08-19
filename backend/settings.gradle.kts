plugins {
    // lets Gradle auto-provision the JDK 17 toolchain build.gradle.kts requires,
    // instead of depending on whatever JDK happens to be on the build machine
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "enroute-backend"
