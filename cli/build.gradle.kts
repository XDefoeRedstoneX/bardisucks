plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":tuya-lan"))
    implementation(project(":core"))
}

application {
    mainClass.set("bardisucks.cli.MainKt")
    applicationName = "bardi"
}

tasks.named<JavaExec>("run") {
    workingDir = rootDir
    standardInput = System.`in`
}
