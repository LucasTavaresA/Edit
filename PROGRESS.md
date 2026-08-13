# PROGRESS

Progress tracker for **Edit** — a bare text editor for Android that opens and closes encrypted files in a format any tool can read and sync.

## Where the project stands

A working bare editor: open a file, edit it, save it.

- `com.lucasta.edit`, AGP 9.3.1, Gradle 9.7.0, Kotlin 2.4.10 (compiled by AGP's built-in Kotlin support)
- minSdk 26, compileSdk 37, targetSdk 37
- Compose UI and foundation 1.11.4, material3 1.4.0. Five dependencies are declared — the Compose BOM, `ui`, `foundation`, `material3`, `activity-compose` and `kotlinx-coroutines-android` — matching what the source imports. core-ktx and lifecycle arrive transitively at 1.18.0 and 2.9.4.
- Source is two files: `MainActivity.kt` (edge-to-edge setup and the editor UI) and `TextFile.kt` (encoding detection, strict read, write, display name). No theme package, no `MaterialTheme` wrapper — material3 composables fall back to their own defaults.
- **Edge-to-edge is on for every device**, via `enableEdgeToEdge()` with a pinned `SystemBarStyle.light(TRANSPARENT, TRANSPARENT)` for both bars, and `Modifier.safeDrawingPadding()` keeping content out from under them. The style is pinned rather than `auto` because `auto` follows the system dark theme and would flip the bar icons to white while the app itself stays light. Dark icons on both bars need API 26, which is minSdk, so every supported device gets transparent bars with dark icons and the fallback colour is never reached.
- Debug APK builds green with no compiler warnings (`app/build/outputs/apk/debug/app-debug.apk`, 11.5 MB unminified), about 2m40s from clean
- Release builds too, minified by R8 and signed, at 1.5 MB. No proguard rules of our own were needed — AndroidX and Compose ship their own consumer rules. Verified by dumping the APK and by `apksigner verify` (v2 scheme). Not yet installed from the termux toolchain.
- No tests and no test dependencies. No crypto code yet.

## Design decisions

- **Encryption format must be tool-agnostic.** A single self-contained file (age, or AES-GCM + Argon2id envelope) that any desktop tool can decrypt. Explicitly NOT Jetpack Security / `EncryptedFile` — it binds keys to the device keystore, making files unreadable off-device.
- **File access via SAF**, so no storage permissions and any sync tool (Syncthing, rclone, etc.) owns the files.

## Environment

Everything below is plain `pkg install` or a stock SDK — no hand-assembled pieces, no version fictions, nothing that needs to be worked around.

- **Build with `gradle assembleDebug` or `gradle assembleRelease` from the project root.** No flags, no property overrides on the command line. A clean build of either is about 2m40s, which is long enough to want a background run.
- Gradle 9.7.0 comes from `pkg install gradle` — the same version `gradle-wrapper.properties` pins. `gradlew` is still in the repo and correct for anyone else, but the wrapper distribution was never downloaded here, so `./gradlew` would pull 130 MB from `services.gradle.org` to arrive at the same Gradle. The termux mirror runs about 10x faster than Google's, which is why the packaged one won.
- JDK is `openjdk-21` (21.0.12). AGP compiles to JVM 17 bytecode regardless, per `compileOptions` and the `kotlin` block.
- **aapt2 is `pkg install aapt2`**, a native aarch64 build reporting `2.20-android-16.0.0_r4` — genuinely build-tools 36 era, which is what AGP 9 expects. `optimizeReleaseResources` runs and produces a correct APK, so the old `android.enableResourceOptimizations=false` workaround is gone.
- The one setting that has to stay is `android.aapt2FromMavenOverride`, pointing at `/data/data/com.termux/files/usr/bin/aapt2`. AGP's own aapt2 comes from Maven as a glibc x86_64 binary that cannot run under bionic, so without the override every build dies in `processDebugResources`. It lives in `~/.gradle/gradle.properties`, not in the repo, because it is an absolute path for this machine only.
- **SDK is at `~/android-sdk`, installed by a real `sdkmanager`** (cmdline-tools 22.0). `local.properties` points at it, so no `ANDROID_HOME` is needed. The cmdline-tools scripts ship a `#!/usr/bin/env` shebang that termux cannot resolve; `termux-fix-shebang` on `cmdline-tools/latest/bin/*` fixes it once.
- `platforms;android-37.0` is installed properly, with `package.xml`. `compileSdk = 37` resolves to `android-37.0` specifically — `android-37.1`, the Android 17 minor release, does not satisfy it.
- **AGP auto-installs `build-tools;36.0.0` and `platform-tools`, and both are x86_64.** Nothing executes them: aapt2 comes from the override and D8, R8 and apksigner are JVM tools. The debug build in fact succeeded before build-tools existed at all. Worth remembering only if some future AGP task shells out to a native build-tools binary, which would fail with an exec format error.
- `adb` is `pkg install android-tools`, a native aarch64 build. There is no device attached yet — reaching the device adb runs on needs Wireless debugging turned on in Developer options and a one-time pairing.

## Known limits of the current editor

1. **Only files that survive a byte-exact round trip will open at all.** A file with a few corrupt bytes is refused outright, even though it might be worth reading. There is a TODO in `TextFile.kt` for offering to open it anyway as a deliberate choice, with a clear warning that saving will rewrite the bytes that could not be represented and that a backup must exist first.
2. Legacy single-byte encodings are rejected. Latin-1 and Windows-1252 accept any byte sequence, so they cannot be detected, only guessed at. A file of Latin-1 accented text will simply refuse to open.
3. No unsaved-changes tracking, so opening a second file silently throws away edits to the first.
4. The whole file is read into one String, so large files will hurt.
5. No persistable URI permission is taken, so a file cannot be reopened after the process dies. Harmless until there is a recent-files list to reopen from.

## Not started

- `ACTION_VIEW` / `ACTION_EDIT` intent filters for text MIME types
- Editor UI (large-file handling, undo, find)
- Encryption: passphrase entry, KDF, envelope format, decrypt-on-open / encrypt-on-close
- Device-to-device backup posture. `allowBackup="false"` stops cloud backup on every supported version, but from Android 12 it does not stop device-to-device transfer — that needs `android:dataExtractionRules` with a `<device-transfer>` exclude, which costs an XML file. The app persists nothing at all today, so there is nothing to leak; do this when it first stores something, such as recent files, SAF permission grants or settings.
