plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

// The repo lives under a OneDrive-synced path, so OneDrive will silently
// dehydrate build outputs (including the 216 MB bundled SudachiDict asset)
// and break Gradle's snapshot step. Redirect all build/ output to a path
// outside the synced tree. Override with TANGOTORI_BUILD_DIR if desired.
val buildRoot: String = System.getenv("TANGOTORI_BUILD_DIR")
    ?: "${System.getProperty("user.home")}/.tangotori-build"

allprojects {
    layout.buildDirectory.set(file("$buildRoot/${rootProject.name}/${name}"))
}
