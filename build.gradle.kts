plugins {
    java
}

group = "net.curxxed.dev"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.jar {
    manifest {
        attributes(
            "Premain-Class" to "net.curxxed.dev.agent.AgentBootstrap",
            "Agent-Class"   to "net.curxxed.dev.agent.AgentBootstrap",
            "Can-Retransform-Classes" to "true",
            "Can-Redefine-Classes"    to "true"
        )
    }
}