plugins {
    kotlin("jvm")
    application
}

application {
    mainClass.set("dev.qlipbod.app.linux.cli.MainKt")
}

dependencies {
    implementation(project(":sync-core"))
    // The CLI composition root owns the JmDNS instance lifecycle (it must be closed at
    // exit — its threads are non-daemon). Keep this coordinate in sync with sync-core.
    implementation("org.jmdns:jmdns:3.6.3")
    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))
}