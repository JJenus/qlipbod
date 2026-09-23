plugins {
    kotlin("multiplatform") version "2.4.20" apply false
    kotlin("plugin.serialization") version "2.4.20" apply false
    kotlin("jvm") version "2.4.20" apply false
}

allprojects {
    group = "dev.qlipbod"
    version = "0.1.0-SNAPSHOT"
}