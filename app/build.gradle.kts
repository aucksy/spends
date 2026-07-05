import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing is sourced from a local `keystore.properties` (never committed) for local builds,
// or from environment variables for CI. If neither is present the release build is simply left
// unsigned, so the project still configures and builds for everyone. (Same convention as the
// author's other shipped Android app.)
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) FileInputStream(keystorePropertiesFile).use { load(it) }
}
val signingValue: (String, String) -> String? = { propKey, envKey ->
    // Treat blank env vars (an unset CI secret renders as "") as absent, so the release simply
    // builds unsigned instead of failing on a half-configured signing block.
    (keystoreProperties.getProperty(propKey) ?: System.getenv(envKey))?.takeIf { it.isNotBlank() }
}
val releaseStoreFilePath: String? = signingValue("storeFile", "KEYSTORE_FILE")
val releaseStorePassword: String? = signingValue("storePassword", "KEYSTORE_PASSWORD")
val keystorePropOnly: (String) -> String? = { key -> keystoreProperties.getProperty(key)?.takeIf { it.isNotBlank() } }
val releaseKeyAlias: String = keystorePropOnly("keyAlias") ?: "spends"
val releaseKeyPassword: String? = keystorePropOnly("keyPassword") ?: releaseStorePassword
val hasReleaseSigning: Boolean = releaseStoreFilePath != null && releaseStorePassword != null

android {
    namespace = "com.spends.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.spends.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 50
        versionName = "1.48.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // R8/resource shrinking is off for now: tuning keep rules needs a local build toolchain,
            // but this project builds only in CI. The release APK is still fully signed and shippable.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }

    lint {
        // Lint runs in CI for signal but never blocks a Phase-1 cloud build; the full detekt/ktlint
        // + strict-lint gate lands with the QA phase.
        abortOnError = false
        checkReleaseBuilds = false
    }

    // Ship the exported Room schemas as androidTest assets so MigrationTestHelper can validate
    // every future upgrade against real on-disk schemas.
    sourceSets {
        getByName("androidTest") {
            assets.srcDirs(files("$projectDir/schemas"))
        }
    }
}

// Export Room schemas to a versioned folder for migration testing + history.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Core / Lifecycle / Compose
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // WorkManager + Hilt-Work (workers added in later phases; factory wired now)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Legacy .xls (Monito export) reader. AWT-only paths are never hit on the read path; see
    // proguard-rules.pro for the -dontwarn that keeps release dexing/shrinking quiet.
    implementation(libs.jxl)

    // Drive backup: AuthorizationClient (drive.appdata) + Drive REST (OkHttp) + gzip JSON snapshot
    implementation(libs.play.services.auth)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Unit testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.google.truth)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)

    // Instrumentation testing
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
