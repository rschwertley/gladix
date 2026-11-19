// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.gms) apply false
    // Declared here (classpath only) so subprojects can apply it below. apply false = not applied to root.
    alias(libs.plugins.detekt) apply false
}

// Headless Kotlin static analysis applied to EVERY module (:app, :common, :deezer-extension). This is the
// ONLY static analyzer that covers the Kotlin/JVM :deezer-extension and the KMP :common — neither has an
// Android Lint task. Left at Detekt's DEFAULT config: PSI-only (no type resolution), which is fast and
// structurally immune to the IDE "Inspect Code" reified-generic/@OptIn type-inference hang (detekt is a
// batch parser, not the IDE's incremental inference engine). Run: `./gradlew detekt` (all modules) or
// `./gradlew :deezer-extension:detekt` (one). Reports: <module>/build/reports/detekt/detekt.{html,xml}.
// 1.23.8 cannot run on JDK 25 (detekt#9772, fixed only in 2.0), so run it on JDK 17: `./gradlew detekt "-Dorg.gradle.java.home=C:/Users/rschw/.jdks/ms-17.0.19"`.
// If Gradle's configuration cache / project-isolation ever rejects this cross-project block, the equivalent
// fallback is to add `alias(libs.plugins.detekt)` to each module's own plugins{} block instead.
subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")
}