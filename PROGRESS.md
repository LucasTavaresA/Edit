# PROGRESS

Progress tracker for **Edit** — a bare text editor for Android that opens and closes encrypted files in a format any tool can read and sync, open everything else as plain text.

## Where the project stands

Stock Compose app template, nothing custom written yet.

- `com.lucasta.edit`, AGP 8.11.0, Kotlin 1.9.22, Gradle 8.14.3
- minSdk 23, compileSdk 36, targetSdk 36
- Source: `MainActivity.kt` (Scaffold + `Greeting("Android")`) + template theme (`Color.kt`, `Theme.kt`, `Type.kt`)
- Debug APK built successfully (`app/build/outputs/apk/debug/app-debug.apk`)
- Not a git repo. No tests. No editor/crypto code.

## Design decisions

- **Encryption format must be tool-agnostic.** A single self-contained file (age, or AES-GCM + Argon2id envelope) that any desktop tool can decrypt. Explicitly NOT Jetpack Security / `EncryptedFile` — it binds keys to the device keystore, making files unreadable off-device.
- **File access via SAF**, so no storage permissions and any sync tool (Syncthing, rclone, etc.) owns the files.

## Blockers / fix before building features

1. **No version control.** `git init` + first commit.
2. **Edge-to-edge breaks on Android 15+.** targetSdk 36 forces edge-to-edge, but `Theme.kt` uses the no-op `window.statusBarColor` / `navigationBarColor`, and the top bar is a fixed 36dp with `WindowInsets(0,0,0,0)` — content renders under the status bar. Needs `enableEdgeToEdge()` and real inset handling.
3. **Stale, self-contradictory deps.** Compose BOM `2022.10.00` overridden by explicit pins (`ui 1.3.0`, `material3 1.0.0`) plus `resolutionStrategy.force` on `core-ktx 1.8.0` / `lifecycle 2.3.1`. Per the in-file comments this all works around **a local maven repo**, but `settings.gradle.kts` resolves from `google()`/`mavenCentral()` online — so the forces and the large `packaging` block are likely dead weight. Blocks `BasicTextField2` / `TextFieldState`, which a text editor wants.
4. **`allowBackup="true"`** — wrong default for an encrypted-file app. `backup_rules.xml` / `data_extraction_rules.xml` exist but the manifest never references them.
5. **Release build unverified.** `isMinifyEnabled = true` with empty proguard rules and `lint.checkReleaseBuilds = false`; only debug has been built.

## Cleanups (low priority)

- `SmallTopAppBar` is deprecated
- Template leftovers: `MyComposeApplicationTheme`, `Greeting`, Purple/Pink palette
- `junit` / `espresso` declared in `libs.versions.toml` but unused; no test source sets

## Not started

- SAF open/save
- `ACTION_VIEW` / `ACTION_EDIT` intent filters for text MIME types
- Editor UI (large-file handling, undo, find)
- Encryption: passphrase entry, KDF, envelope format, decrypt-on-open / encrypt-on-close
