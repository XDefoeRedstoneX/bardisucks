plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

dependencies {
    api(libs.coroutines.core)
    api(libs.serialization.json)
    testImplementation(kotlin("test"))
}
