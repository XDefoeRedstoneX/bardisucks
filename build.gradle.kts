import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

// Declared together here so the Android and Kotlin plugins share one classloader (they need to see each other).
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.application) apply false
}

// Java 17 bytecode everywhere, so the same modules run on Android and on the future hub.
subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
        }
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        tasks.withType<Test>().configureEach { useJUnitPlatform() }
    }
}
