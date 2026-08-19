plugins {
    kotlin("jvm") version "1.9.24"
    kotlin("plugin.serialization") version "1.9.24"
    application
}

group = "com.enroute"
version = "0.1.0"

repositories {
    mavenCentral()
}

val ktorVersion = "2.3.12"

dependencies {
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")

    implementation("org.xerial:sqlite-jdbc:3.45.1.0")
    implementation("org.commonmark:commonmark:0.21.0")
    implementation("org.jsoup:jsoup:1.17.2")

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("ApplicationKt")
}

kotlin {
    jvmToolchain(17)
}
