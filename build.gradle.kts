import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.1.20"
    `maven-publish`
}

group = "dev.freya02"
version = "3.0.0-beta.2_DEV"

repositories {
    mavenCentral()
    mavenLocal()
}

dependencies {
    implementation("io.github.freya022:BotCommands:3.0.0-beta.2_DEV")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21

    withSourcesJar()
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}