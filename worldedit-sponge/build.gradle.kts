import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("org.spongepowered.gradle.plugin")
}

applyPlatformAndCoreConfiguration()
applyShadowConfiguration()

// I can't believe sponge sets this in a base plugin with no opt-out
convention.getPlugin(JavaPluginConvention::class.java).apply {
    setSourceCompatibility(null)
    setTargetCompatibility(null)
}

repositories {
    maven { url = uri("https://repo.codemc.org/repository/maven-public") }
}

dependencies {
    api(project(":worldedit-core"))
    api(project(":worldedit-libs:sponge"))
    api("org.spongepowered:spongeapi:7.1.0") {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
    implementation(enforcedPlatform("org.apache.logging.log4j:log4j-bom:2.8.1") {
        because("Sponge 8 provides Log4J at 2.8.1")
    })
    api("org.apache.logging.log4j:log4j-api")
    api("org.bstats:bstats-sponge:1.7")
    testImplementation("org.mockito:mockito-core:5.18.0")
}

<<<<<<< HEAD
addJarManifest(WorldEditKind.Mod, includeClasspath = true)
=======
tasks.named<Jar>("jar") {
    manifest {
        attributes("Class-Path" to CLASSPATH,
                "WorldEdit-Version" to project.version)
    }
}
>>>>>>> 18a55bc14... Add new experimental snapshot API (#524)

tasks.named<ShadowJar>("shadowJar") {
    dependencies {
        relocate ("org.bstats", "com.sk89q.worldedit.sponge.bstats") {
            include(dependency("org.bstats:bstats-sponge:1.5"))
        }
    }
}

if (project.hasProperty("signing")) {
    apply(plugin = "signing")

    configure<SigningExtension> {
        sign("shadowJar")
    }

    tasks.named("build").configure {
        dependsOn("signShadowJar")
    }
}
