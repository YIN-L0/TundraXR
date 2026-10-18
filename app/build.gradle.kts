plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.tundra.tundraxr"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.tundra.tundraxr"
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Local Tundra brain (server/). Default works over USB after `adb reverse tcp:8787 tcp:8787`;
        // for Wi-Fi set tundra.serverUrl=http://<laptop-ip>:8787 in gradle.properties.
        val serverUrl = providers.gradleProperty("tundra.serverUrl").getOrElse("http://localhost:8787")
        buildConfigField("String", "TUNDRA_SERVER_URL", "\"$serverUrl\"")

        // Demo mode: scripted answers and capture analysis instead of the laptop brain, for testing
        // the UI without a server or manuals. Set tundra.demo=true in gradle.properties.
        val demo = providers.gradleProperty("tundra.demo").getOrElse("false").toBoolean()
        buildConfigField("boolean", "TUNDRA_DEMO", "$demo")
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.runtime)
    implementation(libs.androidx.scenecore)
    // Hand and head tracking for two-hand region capture.
    implementation(libs.androidx.arcore)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.video)
    // Stub jar: the real implementation ships in the headset's system image. Bundling it in the
    // APK shadows the system copy, every call throws "Stub!", and the XR session never starts.
    compileOnly(libs.extensions1.xr)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}