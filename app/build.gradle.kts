import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

// Export Room schema JSON so the jmdict-builder tool can read the
// identityHash + DDL and emit a prepackaged DB that Room accepts.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.tangotori.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tangotori.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        val compoundApiUrl = localProps["COMPOUND_API_URL"]?.toString()
            ?: "https://tango-tori-backend.REPLACE_ME.workers.dev"
        buildConfigField("String", "COMPOUND_API_URL", "\"$compoundApiUrl\"")
    }

    buildTypes {
        release {
            // R8 minification + shrinking. Enables AOT compilation at install
            // — debug-signed APKs are kept in JIT/interpret-only mode by ART,
            // which is why scrolling feels jittery during dev iteration.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Sign with the debug keystore so `./gradlew :app:installRelease`
            // works without provisioning a release keystore. Same signature as
            // the existing installed debug build (no uninstall needed).
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/NOTICE*"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }
    // The dictionary assets (.dic / .db, ~290 MB raw) are deliberately LEFT
    // COMPRESSED in the APK — they deflate to ~38% (296 MB APK → ~120 MB).
    // All three are copied out of the APK before use anyway (SudachiAssetInstaller
    // and Room's createFromAsset), so compression only costs a one-time inflate
    // on first launch. Do NOT re-add "dic"/"db" to noCompress: it triples the
    // APK for zero runtime benefit. (SudachiAssetInstaller's staleness check is
    // marker-file based because openFd() doesn't work on compressed assets.)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.ui.text.google.fonts)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.sudachi)
    implementation(libs.jieba)
    implementation(libs.pinyin4j)

    // AnkiDroid API via JitPack — used for Stage 3.
    // If the JitPack resolution fails locally, comment this out and the
    // AnkiCardRepository stub will still compile (Stage 3 is feature-flagged).
    implementation(libs.ankidroid.api)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
