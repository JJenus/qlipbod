plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":sync-core"))
    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))
}