plugins {
    id("BotCommands-Restarter-conventions")
    `maven-publish`
}

repositories {
    maven("https://jitpack.io")
}

dependencies {
    implementation(libs.botcommands)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            artifactId = "BotCommands-Restarter"
        }
    }
}