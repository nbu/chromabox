import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.argumentProviders.IntelliJPlatformArgumentProvider
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

kotlin {
    compilerOptions {
        // Interface default methods stay in the interfaces. Otherwise every class gets a copy of each, and the plugin
        // verifier counts them as overrides of the platform's deprecated and experimental API.
        jvmDefault = JvmDefaultMode.NO_COMPATIBILITY
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    testImplementation(libs.junit)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2026.2.4")
        testFramework(TestFrameworkType.Platform)
        // Optional: swatches in the Markdown preview (C-07).
        bundledPlugin("org.intellij.plugins.markdown")
        // Java's ElementColorProvider, for the test that AWT colors don't get a second swatch (C-01.g).
        testBundledPlugin("com.intellij.java")
        // CSS in Markdown code fences, which the IDE shows swatches for itself.
        testBundledPlugin("com.intellij.css")
        testFramework(TestFrameworkType.Plugin.Java)
    }
}

intellijPlatform {
    pluginConfiguration {
        // The version's CHANGELOG section is the Marketplace's "What's new".
        val changelog = project.changelog
        changeNotes = providers.gradleProperty("version").map { version ->
            with(changelog) {
                renderItem((getOrNull(version) ?: getUnreleased()).withHeader(false).withEmptySections(false), Changelog.OutputType.HTML)
            }
        }
        ideaVersion {
            // 2026.2, the IDE it's built against; no upper bound, as the Marketplace recommends.
            sinceBuild = "262"
        }
    }
    pluginVerification {
        ides {
            recommended()
        }
    }
}

// Screenshots for the README and the Marketplace (docs/screenshots): an IDE that opens a copy of the demo project, with
// the Robot Server plugin, which runs scripts in it over HTTP. The copy is outside this repository, so the IDE doesn't
// show its Git branch.
val screenshotDemo = File(System.getProperty("java.io.tmpdir"), "lumen-ui")
val copyScreenshotDemo = tasks.register<Sync>("copyScreenshotDemo") {
    from("docs/screenshots/demo") { exclude(".idea") }
    into(screenshotDemo)
    preserve { include(".idea/**") }
}
intellijPlatformTesting.runIde.register("runIdeForScreenshots") {
    task {
        dependsOn(copyScreenshotDemo)
        jvmArgumentProviders += CommandLineArgumentProvider {
            listOf(
                "-Drobot-server.port=8082",
                "-Didea.trust.all.projects=true",
                "-Dide.show.tips.on.startup.default.value=false",
                "-Djb.privacy.policy.text=<!--999.999-->",
                "-Djb.consents.confirmation.enabled=false",
                "-Dide.mac.message.dialogs.as.sheets=false",
            )
        }
        args(screenshotDemo.absolutePath)
    }
    plugins {
        robotServerPlugin()
    }
}

// N-02's benchmark runs in a JVM of its own: after the other tests, the same scan is about a quarter slower.
tasks.register<Test>("benchmark") {
    description = "Runs the scan benchmark (N-02) in its own JVM."
    group = "verification"
    // The same classpath and JVM as the test task, which the IntelliJ Platform plugin sets up. Its platform argument
    // provider refers to the task it belongs to, so this task gets its own.
    val test = tasks.test.get()
    testClassesDirs = test.testClassesDirs
    classpath = test.classpath
    systemProperties(test.systemProperties)
    jvmArgumentProviders += test.jvmArgumentProviders.map { provider ->
        if (provider !is IntelliJPlatformArgumentProvider) provider
        else IntelliJPlatformArgumentProvider(provider.intellijPlatformConfiguration, provider.coroutinesJavaAgentFile, provider.runtimeArchProvider, this)
    }
    useJUnit()
    isScanForTestClasses = false
    include("**/ScanBenchmarkTest.class")
    shouldRunAfter(test)
}

tasks.test {
    exclude("**/ScanBenchmarkTest.class")
}

tasks.check {
    dependsOn("benchmark")
}
