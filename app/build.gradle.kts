plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.drift.airmouse"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.drift.airmouse"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Lets the fast release build install on your own watch for testing.
            // Replace with your Play upload key before publishing.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // Screenshot tests write their pictures to app/build/outputs/roborazzi.
            all { it.systemProperty("roborazzi.test.record", "true") }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.wear.compose:compose-material3:1.7.1")
    implementation("androidx.wear.compose:compose-foundation:1.7.1")
    implementation("androidx.wear.compose:compose-navigation:1.7.1")
    implementation("androidx.wear:wear-ongoing:1.1.0")
    implementation("androidx.wear:wear-input:1.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.77.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.77.0")
    testImplementation("androidx.compose.ui:ui-test-junit4:1.10.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.10.0")
}
