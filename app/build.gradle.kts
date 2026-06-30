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

    // Two flavors that differ ONLY in which assets are bundled:
    //
    //   full — offline-complete: ships deb-bundle.tar.gz (~162MB) +
    //          wheels.tar.gz (~50MB) in assets. First launch works fully
    //          offline (except rust/clang/hermes-agent clone).
    //          APK size ~225MB.
    //
    //   lite — self-download: ships only the bootstrap (~30MB) in main
    //          assets. First launch downloads everything via apt-get +
    //          pip from PyPI. APK size ~34MB.
    //
    // Both flavors share the exact same Kotlin/Java code — the
    // install functions already check for the presence of bundled
    // assets and fall back to apt-get/pip when they're absent.
    // So the ONLY difference is app/src/{full,lite}/assets/.
    flavorDimensions += "distribution"
    productFlavors {
        create("full") {
            dimension = "distribution"
            // applicationIdSuffix keeps both flavors installable side-by-side
            // on the same device (otherwise they'd clash on the package name).
            applicationIdSuffix = ".full"
            versionNameSuffix = "-full"
        }
        create("lite") {
            dimension = "distribution"
            applicationIdSuffix = ".lite"
            versionNameSuffix = "-lite"
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
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.google.android.material:material:1.12.0")
}
