plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Version kommt aus der CI (GitHub Actions); lokal Fallback.
val holoVersionName: String = System.getenv("VERSION_NAME") ?: "0.1.0"
val holoVersionCode: Int = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1

android {
    namespace = "at.b05.holopix"
    compileSdk = 35

    defaultConfig {
        applicationId = "at.b05.holopix"
        minSdk = 29          // Android 10
        targetSdk = 34
        versionCode = holoVersionCode
        versionName = holoVersionName
        buildConfigField("String", "UPDATE_REPO", "\"b05at/HOLOPIX\"")
    }

    signingConfigs {
        create("release") {
            // Eigener Schlüssel über GitHub-Secrets möglich, sonst der feste Repo-Schlüssel.
            // Wichtig: immer derselbe Schlüssel, sonst funktionieren In-App-Updates nicht.
            val ks = System.getenv("HOLOPIX_KEYSTORE_FILE")
            storeFile = if (ks != null) file(ks) else rootProject.file("keystore/holopix.jks")
            storePassword = System.getenv("HOLOPIX_KEYSTORE_PASSWORD") ?: "holopixholopix"
            keyAlias = System.getenv("HOLOPIX_KEY_ALIAS") ?: "holopix"
            keyPassword = System.getenv("HOLOPIX_KEY_PASSWORD") ?: "holopixholopix"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
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
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/INDEX.LIST",
            )
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // HTTP
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // SMB für die Foto-Collage
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10")

}
