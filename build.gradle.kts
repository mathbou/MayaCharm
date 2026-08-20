import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    kotlin("jvm") version "2.3.20"
    id("org.jetbrains.intellij.platform") version "2.14.0"
    id("org.jetbrains.changelog") version "2.5.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()
val platformVersion = providers.gradleProperty("platformVersion").get()

// Set the JVM language level used to build the project.
kotlin {
    jvmToolchain {
        languageVersion = JavaLanguageVersion.of(21)
        vendor = JvmVendorSpec.JETBRAINS
    }
}

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
        jetbrainsRuntime()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea(platformVersion)

        pluginVerifier()

        compatiblePlugin(providers.gradleProperty("platformPlugins"))
    }
}

intellijPlatform {

    pluginConfiguration{
        id = providers.gradleProperty("pluginGroup")
        name = providers.gradleProperty("pluginName_")
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceVersion")
            untilBuild = providers.gradleProperty("pluginUntilVersion")
        }

        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"

            with(it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
            }
        }

        val changelog = project.changelog // local variable for configuration cache compatibility
        // Get the latest available change notes from the changelog file
        changeNotes = providers.gradleProperty("pluginVersion").map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }
    }

    publishing {
        token = System.getenv("IDEA_PLATFORM_PUBLISH")
        channels = providers.gradleProperty("pluginVersion").map { listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" }) }
    }

    pluginVerification {
        ides {
            recommended()
            create(IntelliJPlatformType.RustRover, platformVersion)
            create(IntelliJPlatformType.PyCharm, platformVersion)
        }
    }
}

changelog {
    version = providers.gradleProperty("pluginVersion")
    groups = emptyList()
}

tasks {
    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
        distributionType = Wrapper.DistributionType.BIN
    }

    withType<JavaCompile> {
        options.release = 21
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }

    publishPlugin {
        dependsOn("patchChangelog")
    }
}

intellijPlatformTesting {
    runIde.register("runPycharmFrontend") {
        type = IntelliJPlatformType.PyCharm
        version = platformVersion
    }
}