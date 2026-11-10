rootProject.name = "bardisucks"

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include("tuya-lan", "core", "cli")

// The Android app needs the Android SDK. On a PC without one (it only builds the CLI) the module is left out.
val localProperties = file("local.properties")
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null ||
    (localProperties.exists() && localProperties.readLines().any { it.startsWith("sdk.dir") })
if (hasAndroidSdk) {
    include("app")
} else {
    logger.lifecycle("No Android SDK found (ANDROID_HOME or local.properties): skipping the :app module.")
}
