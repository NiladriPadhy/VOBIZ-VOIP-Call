import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun completeSigningProperties(props: Properties): Properties? {
    val storeFile = props.getProperty("storeFile")
    val storePassword = props.getProperty("storePassword")
    val keyAlias = props.getProperty("keyAlias")
    if (storeFile.isNullOrBlank() || storePassword.isNullOrBlank() || keyAlias.isNullOrBlank()) {
        return null
    }
    if (props.getProperty("keyPassword").isNullOrBlank()) {
        props.setProperty("keyPassword", storePassword)
    }
    return props
}

fun loadReleaseSigningProperties(): Properties? {
    val fromEnv = Properties().apply {
        System.getenv("KEYSTORE_FILE")?.takeIf { it.isNotBlank() }?.let { setProperty("storeFile", it) }
        System.getenv("KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }?.let { setProperty("storePassword", it) }
        System.getenv("KEY_ALIAS")?.takeIf { it.isNotBlank() }?.let { setProperty("keyAlias", it) }
        System.getenv("KEY_PASSWORD")?.takeIf { it.isNotBlank() }?.let { setProperty("keyPassword", it) }
    }
    completeSigningProperties(fromEnv)?.let { return it }

    val propsFile = rootProject.file("keystore.properties")
    if (!propsFile.isFile) return null
    return completeSigningProperties(Properties().apply { propsFile.inputStream().use { load(it) } })
}

fun resolveKeystoreFile(path: String): File {
    val candidate = File(path)
    return if (candidate.isAbsolute) candidate else rootProject.file(path)
}

fun loadDebugProperties(): Properties {
    val props = Properties()
    val file = rootProject.file("debug.properties")
    if (file.isFile) {
        file.inputStream().use { props.load(it) }
    }
    return props
}

fun quoteBuildConfig(value: String?): String {
    val escaped = (value ?: "").replace("\\", "\\\\").replace("\"", "\\\"")
    return "\"$escaped\""
}

val releaseSigningProperties = loadReleaseSigningProperties()
val debugProperties = loadDebugProperties()

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

    signingConfigs {
        if (releaseSigningProperties != null) {
            create("release") {
                val props = checkNotNull(releaseSigningProperties)
                storeFile = resolveKeystoreFile(checkNotNull(props.getProperty("storeFile")))
                storePassword = checkNotNull(props.getProperty("storePassword"))
                keyAlias = checkNotNull(props.getProperty("keyAlias"))
                keyPassword = checkNotNull(props.getProperty("keyPassword"))
            }
        }
    }

    buildTypes {
        debug {
            // Optional local-only defaults from debug.properties (gitignored).
            // Missing keys stay empty so the repo never ships credentials.
            buildConfigField(
                "String",
                "DEBUG_SIP_USERNAME",
                quoteBuildConfig(debugProperties.getProperty("DEBUG_SIP_USERNAME")),
            )
            buildConfigField(
                "String",
                "DEBUG_SIP_PASSWORD",
                quoteBuildConfig(debugProperties.getProperty("DEBUG_SIP_PASSWORD")),
            )
            buildConfigField(
                "String",
                "DEBUG_BACKEND_URL",
                quoteBuildConfig(debugProperties.getProperty("DEBUG_BACKEND_URL")),
            )
            buildConfigField(
                "String",
                "DEBUG_BACKEND_TOKEN",
                quoteBuildConfig(debugProperties.getProperty("DEBUG_BACKEND_TOKEN")),
            )
            buildConfigField(
                "String",
                "DEBUG_CALLER_ID",
                quoteBuildConfig(debugProperties.getProperty("DEBUG_CALLER_ID")),
            )
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            releaseSigningProperties?.let {
                signingConfig = signingConfigs.getByName("release")
            }
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
