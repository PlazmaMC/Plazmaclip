plugins {
    kotlin("jvm") version "2.0.21"
    id("com.gradleup.shadow") version "8.3.4"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.sigpipe:jbsdiff:1.0")
}

kotlin {
    jvmToolchain(21)
}

tasks.shadowJar {
    dependencies {
        exclude(dependency("org.jetbrains:annotations"))
    }

    listOf("org.apache.commons.compress", "org.tukaani", "io.sigpipe").forEach {
        relocate(it, "plazmaclip.libs")
    }

    exclude("META-INF/LICENSE.txt")
    exclude("META-INF/NOTICE.txt")
    relocate("kotlin", "plazmaclip.libs")

    minimize()
}
