import org.jetbrains.intellij.platform.gradle.TestFrameworkType

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
