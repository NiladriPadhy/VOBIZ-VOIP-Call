import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.enetro.vobizvoip"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.enetro.vobizvoip"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        buildConfigField("String", "DEFAULT_REGISTRAR_URL", "\"wss://registrar.vobiz.ai:5063/\"")
        buildConfigField("String", "DEFAULT_SIP_DOMAIN", "\"registrar.vobiz.ai\"")

        // Convenience defaults pre-filled into a fresh install. Kept empty here so
        // release builds ship no credentials; the debug build type overrides them.
        buildConfigField("String", "DEBUG_SIP_USERNAME", "\"\"")
        buildConfigField("String", "DEBUG_SIP_PASSWORD", "\"\"")
        buildConfigField("String", "DEBUG_BACKEND_URL", "\"\"")
        buildConfigField("String", "DEBUG_BACKEND_TOKEN", "\"\"")
        buildConfigField("String", "DEBUG_CALLER_ID", "\"\"")
    }

    buildTypes {
        debug {
            // Debug-only fresh-install defaults so the POC can be tested without
            // retyping config. Never included in release builds.
            buildConfigField("String", "DEBUG_SIP_USERNAME", "\"npadhy94954314076727638\"")
            buildConfigField("String", "DEBUG_SIP_PASSWORD", "\"papu1234\"")
            buildConfigField("String", "DEBUG_BACKEND_URL", "\"https://bff6-122-176-249-201.ngrok-free.app\"")
            buildConfigField(
                "String",
                "DEBUG_BACKEND_TOKEN",
                "\"5ae9d5b882875c15ec3c805f55ff08e4bbe21fb28549cb2470385b62f22896ea\"",
            )
            buildConfigField("String", "DEBUG_CALLER_ID", "\"+918071581219\"")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // OkHttp 5.5 requires compileSdk 37; this POC intentionally targets the installed SDK 36.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.github.webrtc-sdk:android:144.7559.12")

    implementation(platform("com.google.firebase:firebase-bom:34.18.0"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.firebase:firebase-installations")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
