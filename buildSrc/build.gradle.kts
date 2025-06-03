plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    // Change in version catalog too
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.0-RC")
}