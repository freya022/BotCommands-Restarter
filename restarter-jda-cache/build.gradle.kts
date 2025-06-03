import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("BotCommands-Restarter-conventions")
    `java-library`
    `maven-publish`
}

repositories {
    maven("https://jitpack.io")
}

dependencies {
    implementation(projects.restarter)
    implementation(libs.kotlin.logging)
    implementation(libs.jda)

    testImplementation(kotlin("test"))
    testImplementation(libs.mockk)
    testImplementation(libs.bytebuddy)
    testImplementation(libs.logback.classic)
    testImplementation(libs.botcommands)
}

java {
    sourceCompatibility = JavaVersion.VERSION_24
    targetCompatibility = JavaVersion.VERSION_24
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_24
    }
}

val jar by tasks.getting(Jar::class) {
    manifest {
        attributes(
            "Premain-Class" to "dev.freya02.botcommands.restart.jda.cache.Agent",
        )
    }
}

tasks.withType<Test> {
    useJUnitPlatform()

    jvmArgs("-javaagent:${jar.archiveFile.get().asFile.absolutePath}")
}

//val copyForAgent by tasks.registering(Copy::class) {
//    from(jar)
//    into(layout.buildDirectory.dir("libs"))
//    rename { "BotCommands-Restarter-JDA-Cache.jar" }
//}
//
//jar.finalizedBy(copyForAgent)

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}