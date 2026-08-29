import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/**
 * versionCode is the one that matters, and it is not the one people display
 * (PRD §9). Android refuses any APK whose versionCode is lower than the
 * installed one, so a bad release cannot be rolled back without an uninstall —
 * which destroys local data, including anything not yet synced.
 */
fun gitCommitCount(): Int = try {
    val process = ProcessBuilder("git", "rev-list", "--count", "HEAD")
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start()
    process.inputStream.bufferedReader().readText().trim().toIntOrNull() ?: 1
} catch (_: Exception) {
    1
}

// Keep the release keystore backed up somewhere that survives a laptop dying:
// Android refuses an update signed with a different key than the installed
// build, and the only way through is an uninstall that destroys local data.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.monio"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.monio"
        minSdk = 26
        targetSdk = 35
        versionCode = gitCommitCount()
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Schemas are still exported and committed. No hand-written Migration
        // classes, ever — see the destructive-migration note in §9.
        ksp { arg("room.schemaLocation", "$projectDir/schemas") }
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Debug and release builds must coexist. Without the suffix the
            // eventual release build fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE
            // and the only way through is an uninstall that destroys local data
            // (PRD §13, M1).
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            // The loc keys the server sends are resolved through an explicit
            // when(key) map, never Resources.getIdentifier() — a string
            // referenced only by name from the server has no code reference and
            // shrinkResources would strip it (PRD §8).
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    // AppCompat is here for exactly one reason: AppCompatDelegate.setApplicationLocales
    // back-ports per-app language selection below API 33, where the framework
    // LocaleManager does not exist. minSdk is 26, so the back-port is the path.
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    // The one place a navigation dependency earns its keep (§9).
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    // A sideloaded APK gets no Play cloud profiles, so the baseline profiles
    // shipped by profileinstaller are the only AOT there is (§9).
    implementation(libs.androidx.profileinstaller)

    // OkHttp with kotlinx.serialization — about sixty lines of client. Not
    // Retrofit: five endpoints do not justify an interface-proxy layer (§9).
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
