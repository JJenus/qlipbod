plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

kotlin {
    // v1 targets the JVM only (Linux daemon + tests). The commonMain layout is multiplatform-ready:
    // adding androidTarget() / linuxX64() later requires no restructuring of shared code.
    jvm {}

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmMain.dependencies {
            implementation("org.bouncycastle:bcprov-jdk18on:1.86")
            implementation("org.bouncycastle:bcpkix-jdk18on:1.86")
            implementation("org.jmdns:jmdns:3.6.3")
        }
        jvmTest.dependencies {
            implementation(kotlin("test-junit"))
        }
    }
}