plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-library`
}

dependencies {
    api(project(":tuya-lan"))
    testImplementation(kotlin("test"))
    testImplementation(testFixtures(project(":tuya-lan")))
}
