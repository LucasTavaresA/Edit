# PROGRESS

Progress tracker for **Edit** — a bare text editor for Android that opens and closes encrypted files in a format any tool can read and sync, open everything else as plain text.

## Where the project stands

Stock Compose app template, nothing custom written yet.

- `com.lucasta.edit`, AGP 8.11.0, Kotlin 1.9.22, Gradle 8.14.3
- minSdk 23, compileSdk 36, targetSdk 36
- Source: `MainActivity.kt` (bare hello world) + template theme (`Color.kt`, `Theme.kt`, `Type.kt`)
- Debug APK built successfully (`app/build/outputs/apk/debug/app-debug.apk`)
- Not a git repo. No tests. No editor/crypto code.

## Design decisions

- **Encryption format must be tool-agnostic.** A single self-contained file (age, or AES-GCM + Argon2id envelope) that any desktop tool can decrypt. Explicitly NOT Jetpack Security / `EncryptedFile` — it binds keys to the device keystore, making files unreadable off-device.
- **File access via SAF**, so no storage permissions and any sync tool (Syncthing, rclone, etc.) owns the files.

## Blockers / fix before building features

1. **Edge-to-edge breaks on Android 15+.** targetSdk 36 forces edge-to-edge, but `Theme.kt` uses the no-op `window.statusBarColor` / `navigationBarColor`, and the top bar is a fixed 36dp with `WindowInsets(0,0,0,0)` — content renders under the status bar. Needs `enableEdgeToEdge()` and real inset handling.
2. **`allowBackup="true"`** — wrong default for an encrypted-file app. `backup_rules.xml` / `data_extraction_rules.xml` exist but the manifest never references them.
3. **Release build unverified.** `isMinifyEnabled = true` with empty proguard rules and `lint.checkReleaseBuilds = false`; only debug has been built. Compose needs proguard keep rules once minify actually runs.

## Environment

- Git needs `git config --global --add safe.directory <project dir>` for anything under `/storage/emulated/0` — sdcardfs reports an owner git refuses to trust. Every new project dir needs its own line.
- `/storage/emulated/0` is mounted noexec, so scripts there never keep the exec bit; run them as `bash script.sh`.
- Gradle from the shell needs `-Pandroid.aapt2FromMavenOverride="$ANDROID_HOME/build-tools/35.0.0/aapt2"`. The Maven aapt2 is a glibc binary that cannot run under bionic, so without this every build dies in `processDebugResources`. Add `--no-daemon --max-workers=1 -Dorg.gradle.jvmargs="-Xmx1536m"` to avoid running out of memory. A clean debug build is roughly 3 minutes, so expect a background run rather than a foreground one.
- **Version ceiling: AGP 8.11 / compileSdk 36.** Compose BOM 2026.x, core-ktx 1.17+ and lifecycle 2.10+ all require AGP 9.1+ and compileSdk 37, and only `android-36` is installed. Raising the ceiling means installing the SDK 37 platform and bumping AGP.

## Cleanups (low priority)

- Template leftovers: `MyComposeApplicationTheme` naming, Purple/Pink palette in `Color.kt`

## Not started

- SAF open/save
- `ACTION_VIEW` / `ACTION_EDIT` intent filters for text MIME types
- Editor UI (large-file handling, undo, find)
- Encryption: passphrase entry, KDF, envelope format, decrypt-on-open / encrypt-on-close
