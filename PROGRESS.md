# PROGRESS

Progress tracker for **Edit** — a bare text editor for Android that opens and closes encrypted files in a format any tool can read and sync, open everything else as plain text.

## Where the project stands

Stock Compose app template, nothing custom written yet.

- `com.lucasta.edit`, AGP 9.3.1, Gradle 9.7.0, Kotlin 2.4.10 (compiled by AGP's built-in Kotlin support)
- minSdk 23, compileSdk 37, targetSdk 37
- Compose UI and foundation 1.11.4, material3 1.4.0, core-ktx 1.19.0, lifecycle 2.11.0
- Source: `MainActivity.kt` (bare hello world) + template theme (`Color.kt`, `Theme.kt`, `Type.kt`)
- Debug APK builds green (`app/build/outputs/apk/debug/app-debug.apk`, 11.6 MB unminified)
- No tests, no test dependencies, no editor/crypto code yet.

## Design decisions

- **Encryption format must be tool-agnostic.** A single self-contained file (age, or AES-GCM + Argon2id envelope) that any desktop tool can decrypt. Explicitly NOT Jetpack Security / `EncryptedFile` — it binds keys to the device keystore, making files unreadable off-device.
- **File access via SAF**, so no storage permissions and any sync tool (Syncthing, rclone, etc.) owns the files.

## Blockers / fix before building features

1. **Edge-to-edge breaks on Android 15+.** targetSdk 37 forces edge-to-edge, but `Theme.kt` uses the no-op `window.statusBarColor` / `navigationBarColor`, content renders under the system bars. Needs `enableEdgeToEdge()` and real inset handling.
2. **`allowBackup="true"`** — wrong default for an encrypted-file app. `backup_rules.xml` / `data_extraction_rules.xml` exist but the manifest never references them.
3. **Release build unverified.** `isMinifyEnabled = true` with empty proguard rules and `lint.checkReleaseBuilds = false`; only debug has been built. Compose needs proguard keep rules once minify actually runs.

## Environment

- Git needs `git config --global --add safe.directory <project dir>` for anything under `/storage/emulated/0` — sdcardfs reports an owner git refuses to trust. Every new project dir needs its own line.
- `/storage/emulated/0` is mounted noexec, so scripts there never keep the exec bit; run them as `bash script.sh`.
- Gradle from the shell needs `-Pandroid.aapt2FromMavenOverride="$ANDROID_HOME/build-tools/35.0.0/aapt2"`. The Maven aapt2 is a glibc binary that cannot run under bionic, so without this every build dies in `processDebugResources`. Add `--no-daemon --max-workers=1 -Dorg.gradle.jvmargs="-Xmx1536m"` to avoid running out of memory. A clean debug build is roughly 3 minutes, so expect a background run rather than a foreground one.
- **AGP 9 compiles Kotlin itself.** Applying `org.jetbrains.kotlin.android` is now a hard error, not a deprecation. Only `com.android.application` and `org.jetbrains.kotlin.plugin.compose` are applied. The `kotlin { compilerOptions { } }` block still works because AGP provides it.
- **The SDK is hand-assembled and has no `sdkmanager`.** `platforms/android-37.0` was downloaded from `dl.google.com/android/repository/platform-37.0_r02.zip` and unzipped by hand. Note `compileSdk = 37` resolves to `android-37.0` specifically — `android-37.1` (the Android 17 minor release, also installed) does not satisfy it.
- **`build-tools/36.0.0` is x86_64 Linux binaries and cannot execute on this device.** AGP 9 requires build-tools 36+, and Google publishes them only for linux/macos/windows — the aarch64 `35.0.0` in use is a hand-compiled build. Installing the unusable 36.0.0 satisfies AGP's version check while real work goes through the overridden aarch64 aapt2 and AGP's own JVM-based D8/R8/apksigner. Builds pass, but this is a hack: any future AGP task that shells out to a native build-tools binary will fail with an exec format error.

## Cleanups (low priority)

- Template leftovers: `MyComposeApplicationTheme` naming, Purple/Pink palette in `Color.kt`

## Not started

- SAF open/save
- `ACTION_VIEW` / `ACTION_EDIT` intent filters for text MIME types
- Editor UI (large-file handling, undo, find)
- Encryption: passphrase entry, KDF, envelope format, decrypt-on-open / encrypt-on-close
