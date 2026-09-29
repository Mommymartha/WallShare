import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    // google-services is intentionally NOT applied here yet. Applying it without a real
    // google-services.json fails the build immediately ("File google-services.json is
    // missing"). The firebase-messaging dependency below compiles fine without it - the
    // plugin is only needed to auto-configure FirebaseApp from that file, which we don't
    // do until device/push-token registration is implemented (Step 5). At that point:
    //   1. Add a real google-services.json to app/ (see project README setup steps).
    alias(libs.plugins.google.services)
}

// Secrets live in local.properties (gitignored) for local dev, or CI environment
// variables in a real pipeline. Never hardcode these, never commit local.properties.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun secret(key: String): String =
    localProperties.getProperty(key) ?: System.getenv(key) ?: ""

android {
    namespace = "com.wallshare.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wallshare.app"
        minSdk = 26 // Android 8.0 - matches our background-execution model (see notifications/ module)
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "SUPABASE_URL", "\"${secret("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${secret("SUPABASE_ANON_KEY")}\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${secret("GOOGLE_WEB_CLIENT_ID")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false // enable + add rules once the app is feature-complete
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.navigation.compose)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Backend: Supabase (Postgres + Auth + Storage). This is our ONLY database/auth dependency.
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.auth)
    implementation(libs.supabase.storage)
    implementation(libs.supabase.realtime)
    implementation(libs.ktor.client.android)

    // Google Sign-In via Credential Manager (feeds the ID token into Supabase auth)
    implementation(libs.credentials)
    implementation(libs.credentials.play.services)
    implementation(libs.googleid)

    // FCM client SDK only - receiving push, not using Firestore/Firebase Auth/etc.
    implementation(libs.firebase.messaging)

    // Background wallpaper application work, triggered by FCM
    implementation(libs.work.runtime.ktx)

    implementation(libs.coil.compose)
    implementation(libs.material.icons.extended)
}
