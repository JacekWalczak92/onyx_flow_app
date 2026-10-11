plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "pl.jack.floatchat"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.jack.floatchat"
        minSdk = 29          // Boox Note Air 5C = Android 13+, ale zostawiamy zapas
        targetSdk = 35
        versionCode = 6
        versionName = "6.0.0"   // FloatChat_v6
    }

    // Stały klucz w repo (aplikacja prywatna, sideload) — dzięki temu APK z GitHub Actions
    // można instalować jako aktualizację bez odinstalowywania poprzedniej wersji.
    signingConfigs {
        create("floatchat") {
            storeFile = rootProject.file("keystore/floatchat.jks")
            storePassword = "floatchat"
            keyAlias = "floatchat"
            keyPassword = "floatchat"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("floatchat")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("floatchat")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.webkit:webkit:1.12.1")
    // Dostęp do ukrytego ActivityOptions.setLaunchWindowingMode (okno swobodne) bez roota
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
}
