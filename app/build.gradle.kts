import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/** Grows with every commit, so a newer build always installs over an older one. */
fun commitCount(): Int = try {
    val git = ProcessBuilder("git", "rev-list", "--count", "HEAD").directory(rootDir).redirectErrorStream(true).start()
    val out = git.inputStream.bufferedReader().readText().trim()
    out.toIntOrNull()?.takeIf { git.waitFor() == 0 } ?: 1
} catch (e: Exception) {
    1
}

android {
    namespace = "bardisucks.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.xdefoeredstonex.bardisucks"
        minSdk = 26
        // Deliberately one behind: targeting 37 (Android 17) makes LAN access a runtime permission, which the
        // app doesn't ask for yet.
        targetSdk = 36
        versionCode = commitCount()
        versionName = "0.2.0"
    }

    signingConfigs {
        // Shared, non-secret key so every build can update the previous one. See signing/README.md.
        create("shared") {
            storeFile = file("signing/shared.keystore")
            storePassword = "android"
            keyAlias = "bardisucks"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            // Not shrunk yet: R8 can only be trusted once the result has been exercised on a real phone.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Screenshot tests write PNGs to build/outputs/roborazzi.
                it.systemProperty("roborazzi.test.record", "true")
                it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                it.maxHeapSize = "2g"
                // Robolectric reaches into JDK internals that Java 17+ closes by default.
                it.jvmArgs(
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-opens=java.base/java.lang=ALL-UNNAMED",
                    "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                )
            }
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    lint {
        disable += "OldTargetApi" // see targetSdk above
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(testFixtures(project(":tuya-lan")))
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.compose.ui.test.junit4)
}
