plugins {
    kotlin("jvm") version "2.2.10"
    application
}
repositories { mavenCentral() }
kotlin { jvmToolchain(17) }
application { mainClass.set("io.loopbreak.carlink.desktop.MainKt") }
dependencies {
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    implementation("org.java-websocket:Java-WebSocket:1.6.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.16")
    testImplementation(kotlin("test-junit5"))
    // The protocol tests carried over from the Android project use JUnit 4.
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.test { useJUnitPlatform() }
