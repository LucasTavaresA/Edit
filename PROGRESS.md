# PROGRESS

Progress tracker for **Edit** — a bare text editor for Android that opens and closes encrypted files in a format any tool can read and sync, open everything else as plain text.

## Where the project stands

Stripped back to a bare hello world. All template scaffolding is gone.

- `com.lucasta.edit`, AGP 9.3.1, Gradle 9.7.0, Kotlin 2.4.10 (compiled by AGP's built-in Kotlin support)
- minSdk 26, compileSdk 37, targetSdk 37
- Compose UI and foundation 1.11.4, material3 1.4.0. Only four dependencies are declared — the Compose BOM, `ui`, `foundation`, `material3` and `activity-compose` — because nothing else is referenced by the source. core-ktx and lifecycle arrive transitively at 1.18.0 and 2.9.4.
- Source is one file: `MainActivity.kt`, a `setContent { Text("Hello") }`. No theme package, no `MaterialTheme` wrapper — material3 composables fall back to their own defaults.
- **Edge-to-edge is on for every device**, via `enableEdgeToEdge()` with a pinned `SystemBarStyle.light(TRANSPARENT, TRANSPARENT)` for both bars, and `Modifier.safeDrawingPadding()` keeping content out from under them. The style is pinned rather than `auto` because `auto` follows the system dark theme and would flip the bar icons to white while the app itself stays light. Dark icons on both bars need API 26, which is minSdk, so every supported device gets transparent bars with dark icons and the fallback colour is never reached.
- `AndroidManifest.xml` is currently the only XML left, after the template's unused resources were deleted. The app label is a literal string in it and the window theme points straight at the framework's `@android:style/Theme.Material.Light.NoActionBar`. The only other resources are the `ic_launcher.webp` launcher bitmaps.
- Debug APK builds green with no compiler warnings (`app/build/outputs/apk/debug/app-debug.apk`, 11.6 MB unminified)
- No tests, no test dependencies, no editor/crypto code yet.

## Design decisions

- **Encryption format must be tool-agnostic.** A single self-contained file (age, or AES-GCM + Argon2id envelope) that any desktop tool can decrypt. Explicitly NOT Jetpack Security / `EncryptedFile` — it binds keys to the device keystore, making files unreadable off-device.
- **File access via SAF**, so no storage permissions and any sync tool (Syncthing, rclone, etc.) owns the files.

## Blockers / fix before building features

1. **`allowBackup="true"`** — wrong default for an encrypted-file app. Set it to `false` in the manifest; the unreferenced `backup_rules.xml` / `data_extraction_rules.xml` are already gone, and `false` needs no rules file at all.
2. **Release build unverified.** `isMinifyEnabled = true` with empty proguard rules and `lint.checkReleaseBuilds = false`; only debug has been built. Compose needs proguard keep rules once minify actually runs.

## Environment

- Git needs `git config --global --add safe.directory <project dir>` for anything under `/storage/emulated/0` — sdcardfs reports an owner git refuses to trust. Every new project dir needs its own line.
- `/storage/emulated/0` is mounted noexec, so scripts there never keep the exec bit; run them as `bash script.sh`.
- Gradle from the shell needs `-Pandroid.aapt2FromMavenOverride="$ANDROID_HOME/build-tools/35.0.0/aapt2"`. The Maven aapt2 is a glibc binary that cannot run under bionic, so without this every build dies in `processDebugResources`. Add `--no-daemon --max-workers=1 -Dorg.gradle.jvmargs="-Xmx1536m"` to avoid running out of memory. A clean debug build is roughly 3 minutes, so expect a background run rather than a foreground one.
- **AGP 9 compiles Kotlin itself.** Applying `org.jetbrains.kotlin.android` is now a hard error, not a deprecation. Only `com.android.application` and `org.jetbrains.kotlin.plugin.compose` are applied. The `kotlin { compilerOptions { } }` block still works because AGP provides it.
- **The SDK is hand-assembled and has no `sdkmanager`.** `platforms/android-37.0` was downloaded from `dl.google.com/android/repository/platform-37.0_r02.zip` and unzipped by hand. Note `compileSdk = 37` resolves to `android-37.0` specifically — `android-37.1` (the Android 17 minor release, also installed) does not satisfy it.
- **`build-tools/36.0.0` is x86_64 Linux binaries and cannot execute on this device.** AGP 9 requires build-tools 36+, and Google publishes them only for linux/macos/windows — the aarch64 `35.0.0` in use is a hand-compiled build. Installing the unusable 36.0.0 satisfies AGP's version check while real work goes through the overridden aarch64 aapt2 and AGP's own JVM-based D8/R8/apksigner. Builds pass, but this is a hack: any future AGP task that shells out to a native build-tools binary will fail with an exec format error.

## Not started

- SAF open/save
- `ACTION_VIEW` / `ACTION_EDIT` intent filters for text MIME types
- Editor UI (large-file handling, undo, find)
- Encryption: passphrase entry, KDF, envelope format, decrypt-on-open / encrypt-on-close
