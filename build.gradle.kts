plugins {
    java
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "net.curxxed.dev"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
    maven("https://repo.spongepowered.org/maven/")
}

dependencies {
    // ASM — needed at JVM startup so it HAS to be shaded in, not a provided dep
    implementation("org.ow2.asm:asm:9.7")
    implementation("org.ow2.asm:asm-commons:9.7")
    compileOnly("org.spongepowered:mixin:0.8.7")
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveFileName.set("lunar-agent-${project.version}.jar")

    // relocate so we don't collide with whatever ASM version lunar/ichor ships with
    relocate("org.objectweb.asm", "net.curxxed.dev.agent.asm")

    manifest {
        attributes(
            "Premain-Class"           to "net.curxxed.dev.agent.AgentBootstrap",
            "Agent-Class"             to "net.curxxed.dev.agent.AgentBootstrap",
            "Can-Retransform-Classes" to "true",
            "Can-Redefine-Classes"    to "true"
        )
    }
}

tasks.register<Jar>("sourcesJar") {
    archiveClassifier.set("sources")
    from(sourceSets.main.get().allSource)
}

tasks.build {
    dependsOn(tasks.shadowJar)
}