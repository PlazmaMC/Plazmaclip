plugins {
    java
    signing
    `maven-publish`
    id("com.gradleup.shadow") version "8.3.4" apply false
    id("net.minecraftforge.gradlejarsigner") version "1.0.5"
}

repositories {
    mavenCentral()
}

subprojects {
    tasks.register<Jar>("sourcesJar") {
        from(zipTree(tasks.jar.map { it.outputs.files.singleFile }))
        archiveClassifier.set("sources")
    }
}

tasks {
    jar {
        jarSigner.sign(jar.get())

        val app = project(":kotlin").tasks.named("shadowJar")
        dependsOn(app)

        from(zipTree(app.map { it.outputs.files.singleFile }))
        manifest { attributes("Main-Class" to "plazmaclip.PlazmaclipKt") }

        rename { if (it.endsWith("-LICENSE.txt")) "META-INF/license/$it" else it }
    }

    register<Jar>("sourcesJar") {
        val app = project(":kotlin").tasks.named("sourcesJar")
        dependsOn(app)

        from(zipTree(app.map { it.outputs.files.singleFile }))
        archiveClassifier.set("sources")
    }
}

publishing {
    publications.register<MavenPublication>("maven") {
        groupId = project.group.toString()
        version = project.version.toString()
        artifactId = "plazmaclip"

        from(components["java"])
        artifact(tasks["sourcesJar"])
        withoutBuildIdentifier()

        pom {
            url.set("https://github.com/PlazmaMC/Plazmaclip")
            name.set("plazmaclip")
            description.set(project.description)
            packaging = "jar"

            licenses {
                license {
                    name.set("MIT")
                    url.set("https://github.com/PlazmaMC/Plazmaclip/blob/main/LICENSE.md")
                    distribution.set("repo")
                }
            }

            issueManagement {
                system.set("GitHub")
                url.set("https://github.com/PlazmaMC/Plazmaclip/issues")
            }

            developers {
                developer {
                    id.set("AlphaKR93")
                    name.set("Alpha")
                    email.set("dev@alpha93.kr")
                    url.set("https://alpha93.kr/")
                }
            }

            scm {
                url.set("https://github.com/PlazmaMC/Plazmaclip")
                connection.set("scm:git:https://github.com/PlazmaMC/Plazmaclip.git")
                developerConnection.set("scm:git:git@github.com:https://github.com/PlazmaMC/Plazmaclip.git")
            }
        }
    }

    repositories.maven("https://maven.pkg.github.com/PlazmaMC/Plazmaclip") {
        name = "githubPackage"

        credentials {
            username = System.getenv("GITHUB_USERNAME")
            password = System.getenv("GITHUB_TOKEN")
        }
    }
}

signing {
    val signingKey: String? by project
    val signingPassword: String? by project
    useInMemoryPgpKeys(signingKey, signingPassword)
    sign(publishing.publications["maven"])
}

jarSigner {
    autoDetect("")
}
