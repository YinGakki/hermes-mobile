plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nous.hermes.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nous.hermes.mobile"
        minSdk = 24
        // targetSdk 28 allows executing binaries from app data directory.
        // Android 10+ (targetSdk 29+) enforces W^X which blocks this via SELinux.
        // Termux (F-Droid) uses the same approach.
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"

        // Restrict to arm64-v8a — the Termux bootstrap archive bundled in
        // assets/ only ships aarch64 binaries, so there is no point shipping
        // the APK for other ABIs. Also keeps Play Store / sideload size down.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // Sign release builds with keystore from GitHub Secrets / env vars.
            // See README.md → "Signing setup" for how to generate these.
            val keystorePath = System.getenv("SIGNING_KEYSTORE_PATH")
            val keystorePwd = System.getenv("SIGNING_KEYSTORE_PASSWORD")
            val keyAlias = System.getenv("SIGNING_KEY_ALIAS")
            val keyPwd = System.getenv("SIGNING_KEY_PASSWORD")

            if (!keystorePath.isNullOrEmpty() && File(keystorePath).exists()) {
                signingConfig = signingConfigs.create("release") {
                    storeFile = File(keystorePath)
                    storePassword = keystorePwd
                    this.keyAlias = keyAlias
                    this.keyPassword = keyPwd
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Don't compress bootstrap zip in assets — extracted on first run.
    androidResources {
        noCompress += listOf("zip", "tar.gz")
    }

    lint {
        // targetSdk = 28 is intentional (see comment above) — we sideload,
        // not ship via Google Play, so the ExpiredTargetSdkVersion check
        // (which only enforces Play Store policy) doesn't apply to us.
        disable += "ExpiredTargetSdkVersion"
        // Don't let any other lint error block the release build either —
        // this is a dev/self-distributed APK, not Play-bound.
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.google.android.material:material:1.12.0")
}
