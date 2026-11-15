import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.argumentProviders.IntelliJPlatformArgumentProvider

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
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
