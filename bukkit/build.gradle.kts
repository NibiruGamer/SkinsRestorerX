plugins {
    alias(libs.plugins.runpaper)
}

dependencies {
    implementation(projects.skinsrestorerApi)
    implementation(projects.skinsrestorerShared)
    implementation(projects.mappings.mcShared)
    implementation(projects.multiver.bukkit.shared)
    implementation(projects.multiver.bukkit.spigot)
    implementation(projects.multiver.bukkit.paper)
    implementation(projects.multiver.bukkit.multipaper)
    implementation(projects.multiver.bukkit.v17)
    implementation(projects.multiver.bukkit.folia)

    implementation("net.kyori:adventure-platform-bukkit:4.3.2")

    rootProject.subprojects.forEach {
        if (!it.name.startsWith("mc-") || it.name.contains("shared")) return@forEach

        compileOnly(project(":mappings:${it.name}"))
        runtimeOnly(project(":mappings:${it.name}", "remapped"))
    }
    testImplementation(testFixtures(projects.skinsrestorerShared))

    compileOnly("org.spigotmc:spigot-api:1.19.3-R0.1-SNAPSHOT") {
        isTransitive = false
    }

    implementation("org.bstats:bstats-bukkit:3.0.2")
    implementation("com.github.cryptomorin:XSeries:9.9.0") {
        isTransitive = false
    }

    // PAPI API hook
    compileOnly("me.clip:placeholderapi:2.11.5") {
        isTransitive = false
    }

    compileOnly("com.viaversion:viabackwards-common:4.9.1") {
        isTransitive = false
    }
    compileOnly("com.viaversion:viaversion:4.4.1") {
        isTransitive = false
    }

    compileOnly("com.mojang:authlib:2.0.27")

    testImplementation(projects.skinsrestorerBuildData)
    testImplementation("org.spigotmc:spigot-api:1.19-R0.1-SNAPSHOT") {
        isTransitive = false
    }
    testRuntimeOnly("com.mojang:authlib:2.0.27")
}

tasks {
    shadowJar {
        configureKyoriRelocations()
    }
    runServer {
        minecraftVersion(libs.versions.runpaperversion.get())
    }
}
