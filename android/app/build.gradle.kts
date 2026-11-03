import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    // README screenshots, rendered on the JVM: gradlew :app:recordRoborazziDebug
    id("io.github.takahirom.roborazzi")
}

android {
    namespace = "space.iamjkc.shiftbell"
    compileSdk = 36

    defaultConfig {
        applicationId = "space.iamjkc.shiftbell"
        // Android 13: USE_EXACT_ALARM, the notification permission and alarm-usage vibration all exist.
        // A personal app for one phone (a Pixel on Android 17), so no compatibility branches below that.
        minSdk = 33
        targetSdk = 36
        versionCode = 5
        versionName = "0.4.1"
    }

    buildTypes {
        release {
            // Signed with the debug key so it installs straight from this PC.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        // Version-bump nags are not defects; dependencies move on purpose, not because lint ran.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    // Scans the PC's pairing QR code through Google Play services: no camera permission, nothing bundled.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

    val compose = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(compose)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation(compose)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.46.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.46.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

