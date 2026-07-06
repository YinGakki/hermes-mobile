plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nous.hermes.mobile"
    compileSdk = 35

    // NDK 版本：用于编译 PTY JNI 代码（pty.c → libhermespty.so）
    // 必须与 CI 中安装的 NDK 版本一致
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.nous.hermes.mobile"
        // minSdk 29：proot+rootfs 方案需要 Android 10+ 的 Os.symlink 等 API。
        // openclaw-termux 同样用 minSdk=29。
        minSdk = 29
        // targetSdk 28 允许从 app 数据目录执行二进制。但 proot 二进制通过
        // jniLibs（lib*.so）打包，Android 会自动解压到 nativeLibraryDir 并
        // 带执行位，绕过 W^X。所以 targetSdk 可以提高到 36。
        // 不过为保守起见，沿用 28（与 Termux F-Droid 同策略）。
        targetSdk = 28
        versionCode = 3
        versionName = "0.0.2-beta"

        // Restrict to arm64-v8a — proot 二进制和 Ubuntu rootfs 只支持 aarch64。
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        // PTY native 代码构建配置（CMake → libhermespty.so）
        externalNativeBuild {
            cmake {
                // 纯 C 代码，不需要 C++ STL
                arguments("-DANDROID_STL=none")
                cFlags("-std=c11")
            }
        }
    }

    // CMake 构建：编译 pty.c → libhermespty.so（PTY JNI 桥接）
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
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
        // 关键：让 jniLibs 中的 lib*.so（proot、libtalloc）解压到
        // nativeLibraryDir 并带执行位，绕过 Android 10+ W^X 策略。
        // openclaw-termux 用同样的配置。
        jniLibs {
            useLegacyPackaging = true
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

    // 解压 rootfs tarball（.tar.gz）和 deb 包（.tar.xz/.gz）。
    // 参照 openclaw-termux，用纯 Java 解压避免 proot 内 fork+exec 问题。
    // zstd 格式暂不支持（Ubuntu 24.04 rootfs tarball 是 .tar.gz，deb 主要 .tar.xz/.tar.gz）。
    implementation("org.apache.commons:commons-compress:1.26.0")
    implementation("org.tukaani:xz:1.9")
}
