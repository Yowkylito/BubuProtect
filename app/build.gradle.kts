import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/**
 * Release signing credentials.
 *
 * Read from `keystore.properties` (gitignored) with an environment-variable fallback for CI. They are
 * deliberately *not* in this file: build scripts are committed, and a signing key checked into git
 * stays in the history forever even after it is deleted - at which point anyone with repo access can
 * ship an update that Android accepts as genuinely yours.
 *
 * A missing keystore is not an error. The release build still runs and produces an unsigned APK, so
 * a fresh clone or a CI job without the secrets can still verify that R8 and the build work.
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use(::load)
    }
}

fun signingSecret(propertyName: String, environmentName: String): String? =
    (keystoreProperties.getProperty(propertyName) ?: System.getenv(environmentName))
        ?.takeIf(String::isNotBlank)

val releaseStoreFile: File? = signingSecret("storeFile", "BUBU_STORE_FILE")
    ?.let(rootProject::file)
    ?.takeIf(File::exists)
val releaseStorePassword: String? = signingSecret("storePassword", "BUBU_STORE_PASSWORD")
val releaseKeyAlias: String? = signingSecret("keyAlias", "BUBU_KEY_ALIAS")
val releaseKeyPassword: String? = signingSecret("keyPassword", "BUBU_KEY_PASSWORD")

/**
 * Signing is all-or-nothing.
 *
 * A keystore present but with a blank password is a half-filled template, not an attempt to sign, and
 * handing that to AGP produces `keystore password was incorrect` - which reads like a wrong password
 * rather than an unfinished setup. Treating it as "not configured" keeps the failure honest.
 */
val hasCompleteReleaseSigning = releaseStoreFile != null &&
    releaseStorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null

// Warn only when a release build was actually asked for, so debug builds stay quiet. Without this an
// unsigned APK looks like a successful release and only fails later, at install time.
if (!hasCompleteReleaseSigning &&
    gradle.startParameter.taskNames.any { it.contains("elease") }
) {
    val reason = when {
        !keystorePropertiesFile.exists() ->
            "keystore.properties does not exist - copy keystore.properties.template to it"
        releaseStoreFile == null ->
            "the keystore file named by storeFile was not found (paths are relative to the project root)"
        else ->
            "keystore.properties is missing one of storePassword, keyAlias or keyPassword"
    }
    logger.warn("BubuProtect: release output will be UNSIGNED and cannot be installed - $reason.")
}

android {
    namespace = "com.personal.bubuprotect"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.personal.bubuprotect"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The vault is offline-only: no API keys, no BuildConfig secrets, no network.
        ndk {
            // SQLCipher ships native code. Keep only the ABIs real devices use.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        // Created only when the keystore is actually present, rather than created-then-populated.
        //
        // AGP validates every signing config a variant references, and one without a storeFile fails
        // with `SigningConfig "release" is missing required property "storeFile"` - during Gradle
        // sync, not just at build time. So "no keystore" has to mean "no config at all"; an empty
        // config is not a valid representation of that state.
        if (hasCompleteReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword

                // v1 (JAR) signing is off: it is only needed below API 24 and minSdk here is 26, so
                // it is dead weight that also carries the Janus class of vulnerability
                // (CVE-2017-13156), where a DEX file prepended to a v1-only APK still verifies.
                // v2/v3 sign the whole archive, so the same trick fails.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
            // findByName, not getByName: it returns null when no keystore was configured, which
            // yields an unsigned APK rather than a build failure - see the note on
            // keystoreProperties. getByName would throw during configuration instead.
        }
        debug {
            // Keeps debug and release vaults from fighting over the same DB file.
            applicationIdSuffix = ".debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.navigation.compose)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation(libs.androidx.biometric)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    // Coil (local GIF assets only)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)

    // Encrypted local storage: Room over SQLCipher
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.sqlcipher.android)
}
