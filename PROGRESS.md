# PROGRESS

Progress tracker for **Edit** — a bare text editor for Android that opens and closes encrypted files in a format any tool can read and sync, open everything else as plain text.

## Where the project stands

A working bare editor: open a file, edit it, save it.

- `com.lucasta.edit`, AGP 9.3.1, Gradle 9.7.0, Kotlin 2.4.10 (compiled by AGP's built-in Kotlin support)
- minSdk 26, compileSdk 37, targetSdk 37
- Compose UI and foundation 1.11.4, material3 1.4.0. Five dependencies are declared — the Compose BOM, `ui`, `foundation`, `material3`, `activity-compose` and `kotlinx-coroutines-android` — matching what the source imports. core-ktx and lifecycle arrive transitively at 1.18.0 and 2.9.4.
- Source is two files: `MainActivity.kt` (edge-to-edge setup and the editor UI) and `TextFile.kt` (read, write, display name). No theme package, no `MaterialTheme` wrapper — material3 composables fall back to their own defaults.
- **Edge-to-edge is on for every device**, via `enableEdgeToEdge()` with a pinned `SystemBarStyle.light(TRANSPARENT, TRANSPARENT)` for both bars, and `Modifier.safeDrawingPadding()` keeping content out from under them. The style is pinned rather than `auto` because `auto` follows the system dark theme and would flip the bar icons to white while the app itself stays light. Dark icons on both bars need API 26, which is minSdk, so every supported device gets transparent bars with dark icons and the fallback colour is never reached.
- `AndroidManifest.xml` is currently the only XML left, after the template's unused resources were deleted. The app label is a literal string in it and the window theme points straight at the framework's `@android:style/Theme.Material.Light.NoActionBar`. The only other resources are the `ic_launcher.webp` launcher bitmaps.
- Debug APK builds green with no compiler warnings (`app/build/outputs/apk/debug/app-debug.apk`, 11.6 MB unminified)
- Release builds too, minified by R8 and signed, at 1.2 MB. No proguard rules of our own were needed — AndroidX and Compose ship their own consumer rules. Verified by dumping the APK, then installed by hand and confirmed running.
- No tests and no test dependencies. No crypto code yet.

## Design decisions

- **Encryption format must be tool-agnostic.** A single self-contained file (age, or AES-GCM + Argon2id envelope) that any desktop tool can decrypt. Explicitly NOT Jetpack Security / `EncryptedFile` — it binds keys to the device keystore, making files unreadable off-device.
- **File access via SAF**, so no storage permissions and any sync tool (Syncthing, rclone, etc.) owns the files.

## Environment

- Git needs `git config --global --add safe.directory <project dir>` for anything under `/storage/emulated/0` — sdcardfs reports an owner git refuses to trust. Every new project dir needs its own line.
- `/storage/emulated/0` is mounted noexec, so scripts there never keep the exec bit; run them as `bash script.sh`.
- **`android.enableResourceOptimizations=false` is load-bearing.** AGP 9's `optimizeReleaseResources` drives `aapt2 optimize`, and the aapt2 here is a hand-built 2.19 from build-tools 35 while AGP 9 expects 36. The mismatch does not error — it produces an APK with no `AndroidManifest.xml`, no `resources.arsc` and no signature, while Gradle still reports BUILD SUCCESSFUL. Never trust a green release build alone; dump the APK. Debug is unaffected because it never runs that task.
- **Nothing can be installed from the shell.** The uid here is a normal app, not shell, so `pm install` fails: system server cannot read FUSE storage by path, and streaming via stdin dies in `PackageInstallerService` because a non-shell uid has no installer package. `file://` intents to the package installer do not resolve either. Release APKs get copied to `/storage/emulated/0/Download/` to be tapped by hand.
- Gradle from the shell needs `-Pandroid.aapt2FromMavenOverride="$ANDROID_HOME/build-tools/35.0.0/aapt2"`. The Maven aapt2 is a glibc binary that cannot run under bionic, so without this every build dies in `processDebugResources`. Add `--no-daemon --max-workers=1 -Dorg.gradle.jvmargs="-Xmx1536m"` to avoid running out of memory. A clean debug build is roughly 3 minutes, so expect a background run rather than a foreground one.
- **AGP 9 compiles Kotlin itself.** Applying `org.jetbrains.kotlin.android` is now a hard error, not a deprecation. Only `com.android.application` and `org.jetbrains.kotlin.plugin.compose` are applied. The `kotlin { compilerOptions { } }` block still works because AGP provides it.
- **The SDK is hand-assembled and has no `sdkmanager`.** `platforms/android-37.0` was downloaded from `dl.google.com/android/repository/platform-37.0_r02.zip` and unzipped by hand. Note `compileSdk = 37` resolves to `android-37.0` specifically — `android-37.1` (the Android 17 minor release, also installed) does not satisfy it.
- **`build-tools/36.0.0` is x86_64 Linux binaries and cannot execute on this device.** AGP 9 requires build-tools 36+, and Google publishes them only for linux/macos/windows — the aarch64 `35.0.0` in use is a hand-compiled build. Installing the unusable 36.0.0 satisfies AGP's version check while real work goes through the overridden aarch64 aapt2 and AGP's own JVM-based D8/R8/apksigner. Builds pass, but this is a hack: any future AGP task that shells out to a native build-tools binary will fail with an exec format error.

## Known limits of the current editor

1. **Opening a non-UTF-8 file and saving it destroys it.** Bad bytes decode to U+FFFD and get written back as replacement characters. Opening everything as plain text is the point of the app, so this needs a real answer — detect non-text content and refuse to save, or hold the original bytes and rewrite only what changed. Most urgent of these.
2. No unsaved-changes tracking, so opening a second file silently throws away edits to the first.
3. The whole file is read into one String, so large files will hurt.
4. UTF-8 is assumed on both read and write; no BOM or charset detection.
5. No persistable URI permission is taken, so a file cannot be reopened after the process dies. Harmless until there is a recent-files list to reopen from.

## Not started

- `ACTION_VIEW` / `ACTION_EDIT` intent filters for text MIME types
- Editor UI (large-file handling, undo, find)
- Encryption: passphrase entry, KDF, envelope format, decrypt-on-open / encrypt-on-close
- Device-to-device backup posture. `allowBackup="false"` stops cloud backup on every supported version, but from Android 12 it does not stop device-to-device transfer — that needs `android:dataExtractionRules` with a `<device-transfer>` exclude, which costs an XML file. The app persists nothing at all today, so there is nothing to leak; do this when it first stores something, such as recent files, SAF permission grants or settings.
