# Hermes Agent (Android)

A self-contained Android APK that bundles a Termux bootstrap and installs
[Hermes Agent](https://github.com/NousResearch/hermes-agent) (Nous Research)
on first launch — no root, no PC, no pre-installed Termux required.

Inspired by (and forked from) [AnyClaw](https://github.com/friuns2/openclaw-android-assistant),
which does the same for OpenClaw/Codex.

## Architecture

```
┌──────────────────────────────────────────────┐
│                Android APK                   │
│  com.nous.hermes.mobile                      │
│                                              │
│  ┌───────────────┐    ┌─────────────────┐    │
│  │  MainActivity │ →  │ BootstrapInstaller│    │
│  │  (setup UI)  │    │  extracts ~30MB  │    │
│  └───────────────┘    │  bootstrap zip   │    │
│         ↑             └────────┬────────┘    │
│         │                      ▼             │
│  ┌──────┴───────────────────────────────┐    │
│  │ /data/data/com.nous.hermes.mobile/   │    │
│  │        files/usr/   (Termux prefix)  │    │
│  │                                       │    │
│  │   bin/python        bin/git           │    │
│  │   bin/hermes        bin/proot         │    │
│  │   ── via pkg: clang rust make node    │    │
│  │   ── via pip: hermes-agent.[termux]   │    │
│  └───────────────────────────────────────┘    │
└──────────────────────────────────────────────┘
```

## Why `targetSdk = 28`?

Android 10+ (targetSdk 29+) enforces W^X memory protection via SELinux, which
blocks executing binaries from the app's writable data directory. Setting
`targetSdk = 28` is the same trick the official F-Droid Termux uses to keep
the embedded `bin/`, `lib/`, and `libexec/` runnable.

## Why `arm64-v8a` only?

The Termux bootstrap archive we embed only ships aarch64 binaries. Adding
other ABIs would either need separate bootstrap zips or would silently fail
on those devices. Restricting via `abiFilters` keeps the APK small (~30 MB)
and matches what 99% of modern Android phones use.

## Minimum requirements

- Android 7.0 (API 24) or higher
- `arm64-v8a` device (most phones since 2017)
- ~600 MB free storage (bootstrap + Python/Rust/Node toolchain + Hermes venv)
- Internet access on first launch (download toolchain packages + clone hermes-agent)

## What's skipped

The first iteration intentionally skips Hermes voice support
(`faster-whisper` → `ctranslate2`) because no Android wheel exists. This matches
the Hermes `.[termux]` extras officially — see
<https://hermesagent.org.cn/docs/getting-started/termux>. Voice can be added
later via ctranslate2 source compile or an onnxruntime-android swap.

---

# Building

## Option A — Local build

### Prerequisites
- Android Studio Iguana+ (or just Android SDK command-line tools)
- JDK 17
- `curl` (for downloading bootstrap)

### 1. Download the Termux bootstrap

```bash
./scripts/download-bootstrap.sh            # aarch64 by default
# or:
./scripts/download-bootstrap.sh --arch aarch64
```

Drops `bootstrap-aarch64.zip` (~30 MB) into `app/src/main/assets/`.

### 2. Build the APK

```bash
# Debug (no signing)
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# Release (signed, requires signing env vars — see below)
export SIGNING_KEYSTORE_PATH=$HOME/hermes-release.jks
export SIGNING_KEYSTORE_PASSWORD=changeit
export SIGNING_KEY_ALIAS=hermes
export SIGNING_KEY_PASSWORD=changeit
./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

## Option B — GitHub Actions (recommended)

The workflow at [`.github/workflows/build.yml`](.github/workflows/build.yml)
runs on every push/PR and on `v*` tags. It will:

1. Set up JDK 17 + Android SDK on `ubuntu-latest`
2. Download the Termux bootstrap
3. Decode your signing keystore from a GitHub Secret
4. Run `./gradlew :app:assembleRelease`
5. Upload the resulting APK as a workflow artifact (30-day retention)
6. **On `v*` tags** — also create a GitHub Release and attach the APK

The build job runs even without secrets (producing an unsigned APK), so forks
can verify the build green before configuring signing.

### Generate the signing keystore

```bash
keytool -genkeypair \
  -keystore hermes-release.jks \
  -storetype JKS \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias hermes \
  -storepass 'changeit' \
  -keypass 'changeit' \
  -dname "CN=Hermes Mobile, O=Self-signed, C=US"
```

### Add the four GitHub Secrets

In your repo → **Settings → Secrets and variables → Actions → New repository secret**:

| Secret name             | Value                                              |
| ----------------------- | -------------------------------------------------- |
| `KEYSTORE_BASE64`        | `base64 -w0 hermes-release.jks` output             |
| `KEYSTORE_PASSWORD`      | `changeit` (the `-storepass` you used)            |
| `SIGNING_KEY_ALIAS`      | `hermes` (the `-alias` you used)                   |
| `SIGNING_KEY_PASSWORD`   | `changeit` (the `-keypass` you used)              |

To get `KEYSTORE_BASE64` on Linux/macOS:

```bash
base64 -w0 hermes-release.jks    # Linux
base64 -i hermes-release.jks     # macOS
```

### Trigger a build

- Push to `main` → workflow runs
- Pull request → workflow runs (unsigned APK)
- **Publish a release**: `git tag v0.1.0 && git push origin v0.1.0` → release job fires
- Or use **Actions → Build Release APK → Run workflow** for a manual trigger

The signed APK ends up under the workflow run's **Artifacts** section
(`hermes-agent-arm64-release.zip`).

---

# First run (on the device)

On first launch, the app will:

1. Extract the bootstrap environment (~30 MB compressed → ~100 MB extracted)
2. `pkg install` Python, clang, rust, make, node, ripgrep, ffmpeg, openssl, libffi…
3. Clone `https://github.com/NousResearch/hermes-agent` into `~/hermes-agent`
4. Create a venv and run `pip install -e '.[termux]' -c constraints-termux.txt`
5. Write a `~/.hermes/config.yaml` skeleton (openrouter + claude-3.5-sonnet defaults)
6. Verify with `hermes --version`

Steps 1–4 only happen once. Subsequent launches skip straight to the
"done" screen and let you open a shell.

To get a shell: install the official
[Termux](https://github.com/termux/termux/releases) APK (any recent version)
and tap **Open Shell** in the app. Termux will auto-detect the
`com.nous.hermes.mobile` prefix and drop you in.

---

# Repository layout

```
hermes-android-apk/
├── .github/workflows/build.yml      # GitHub Actions CI
├── app/
│   ├── build.gradle.kts             # targetSdk=28, signing config, arm64-v8a
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml      # dataSync foreground service type
│       ├── assets/
│       │   ├── setup-hermes.sh      # reference manual setup (if you prefer)
│       │   └── bootstrap-aarch64.zip # downloaded at build time, not committed
│       ├── java/com/nous/hermes/mobile/
│       │   ├── BootstrapInstaller.kt   # extracts bootstrap, fixes Termux paths
│       │   ├── HermesServerManager.kt  # installs proot/python/hermes
│       │   ├── HermesForegroundService.kt
│       │   └── MainActivity.kt         # setup flow + UI
│       └── res/
│           ├── layout/activity_main.xml
│           └── values/{strings,themes}.xml
├── gradle/wrapper/                  # gradle-wrapper.jar is committed
├── scripts/download-bootstrap.sh
├── settings.gradle.kts
└── README.md                        # you are here
```

---

# License

MIT — same as Hermes Agent and AnyClaw. See source headers for individual file
provenance (a few files are adapted from Termux's `TermuxInstaller.java` and
AnyClaw's `BootstrapInstaller.kt`).
