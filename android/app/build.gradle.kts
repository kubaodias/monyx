import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/**
 * The version is semver, and versionCode is derived from it:
 * major * 1_000_000 + minor * 1_000 + patch. Android compares only the code and
 * refuses any APK whose code is lower than the installed one, so a bad release
 * cannot be rolled back without an uninstall — which destroys local data,
 * including anything not yet synced.
 *
 * scripts/release.mjs passes the version it is publishing as -PmonyxVersion and
 * tags the commit v<version>. Any other build takes the newest such tag, so a
 * local build reports the release it grew from; with no tag at all it is 0.0.0.
 * Keep the arithmetic in step with scripts/release-version.mjs.
 */
val semver = Regex("""^(\d+)\.(\d+)\.(\d+)$""")

fun latestTaggedVersion(): String? = try {
    val process = ProcessBuilder("git", "describe", "--tags", "--abbrev=0", "--match", "v[0-9]*")
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start()
    process.inputStream.bufferedReader().readText().trim().removePrefix("v")
        .takeIf { process.waitFor() == 0 && semver.matches(it) }
} catch (_: Exception) {
    null
}

val appVersion: String = (findProperty("monyxVersion") as String?) ?: latestTaggedVersion() ?: "0.0.0"

fun versionCodeOf(version: String): Int {
    val (major, minor, patch) = semver.matchEntire(version)?.destructured
        ?: error("monyxVersion must be MAJOR.MINOR.PATCH, got '$version'")
    require(minor.toInt() < 1000 && patch.toInt() < 1000) { "minor and patch must stay below 1000" }
    // Android wants a positive code; 0.0.0 is only ever a build nobody released.
    return maxOf(1, major.toInt() * 1_000_000 + minor.toInt() * 1_000 + patch.toInt())
}

// Keep the release keystore backed up somewhere that survives a laptop dying:
// Android refuses an update signed with a different key than the installed
// build, and the only way through is an uninstall that destroys local data.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

// The voice assistant's number, for the call button. Untracked, like the
// keystore: a phone number is personal data and does not belong in the
// repository. Absent, the constant is empty and the button hides itself, so a
// clean checkout builds and simply has no call button.
val voicePropsFile = rootProject.file("voice.properties")
val voiceProps = Properties().apply {
    if (voicePropsFile.exists()) voicePropsFile.inputStream().use { load(it) }
}
val assistantNumber: String = voiceProps.getProperty("assistantNumber").orEmpty()

android {
    namespace = "com.monyx"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.monyx"
        minSdk = 26
        targetSdk = 35
        versionCode = versionCodeOf(appVersion)
        versionName = appVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Schemas are still exported and committed. No hand-written Migration
        // classes, ever — see the destructive-migration note in Entities.kt.
        ksp { arg("room.schemaLocation", "$projectDir/schemas") }

        buildConfigField("String", "ASSISTANT_NUMBER", "\"$assistantNumber\"")
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
            // and the only way through is an uninstall that destroys local data.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            // The loc keys the server sends are resolved through an explicit
            // when(key) map, never Resources.getIdentifier() — a string
            // referenced only by name from the server has no code reference and
            // shrinkResources would strip it.
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

    // The one place a navigation dependency earns its keep.
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    // A sideloaded APK gets no Play cloud profiles, so the baseline profiles
    // shipped by profileinstaller are the only AOT there is.
    implementation(libs.androidx.profileinstaller)

    // OkHttp with kotlinx.serialization — about sixty lines of client. Not
    // Retrofit: five endpoints do not justify an interface-proxy layer.
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
