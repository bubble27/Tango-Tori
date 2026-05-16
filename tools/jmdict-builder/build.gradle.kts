plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.tangotori.jmdictbuilder.MainKt")
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.sqlite.jdbc)
}

// Default working dir = project root, so paths like `app/src/main/assets/...`
// resolve naturally when invoked via `./gradlew :tools:jmdict-builder:run`.
// Depend on app KSP so the Room schema JSON is fresh before we read it.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    dependsOn(":app:kspDebugKotlin")
}
