# Edit

I want to ultimately make a bare text editing app, i also want it to handle encrypted files and open everything else normally, cause other apps and encryption tools are too complex, have their custom sync and format, i want to have it all locally sync-able with any tool.

You maintain PROGRESS.md which tracks our progress and keep all info across compaction's, read it fully at the beggining of every conversation.

We maintain CLAUDE.md, but avoid modifying things here as much as you can, try to append instead, add things that don't fit PROGRESS.md here, organize into sections.

## Git

Always attribute commits to yourself naming your model like "claude opus 5.5", this repo is owned and maintained by you, don't put me in any commits.

## Markdown

Written as one long line per paragraph or list item, never hard wrapped. The editor wraps visually.

## PROGRESS.md

No implementations. PROGRESS carries ideas and concepts — the things another instance cannot re-derive by reading the code. How to write the code is not one of them: it can work that out, usually better than a note written before the problem was in front of it, and a stale prescription is worse than no prescription.

Everything else is a concept: what the constraint is, what it costs, what it rules out. Not the line of code that answers it.

PROGRESS says what things are supposed to be and what is left to do. Nothing about what was tried, tested or dropped, and no detailed technical info that can go stale.

## Building

- **A build is two JVMs with a 2 GB heap ceiling each.** The Gradle daemon runs `-Xmx2048m` and the Kotlin daemon another, and the Kotlin one lingers for two hours after it is done compiling. Kotlin daemons also pile up: `Detected multiple Kotlin daemon sessions` means more than one is alive, each with its own ceiling. When memory is tight, `-Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.jvmargs=-Xmx1536m` builds in one JVM instead of two.
- **Clear the daemons between sessions:** `gradle --stop` for Gradle's, `pkill -f 'KotlinCompileDaemo[n]'` for the Kotlin ones. The bracket stops the pattern matching the shell running it, which would kill the command itself and show up as exit code 144 out of nowhere. It protects the pattern and nothing else, so the name written anywhere else on the same line, a `pgrep -af KotlinCompileDaemon` alongside it say, brings 144 back.
- **Lint has to be asked for.** `assembleDebug` does not run it and the release hook is off. `gradle lintDebug` takes about 13 minutes cold and does not run out of memory; it prints nothing useful, so read `app/build/reports/lint-results-debug.sarif`. Occasionally rather than never, not per change.
- **aapt2 is `pkg install aapt2`, a native aarch64 build, and `android.aapt2FromMavenOverride` in `~/.gradle/gradle.properties` points AGP at it.** AGP's own is a glibc x86_64 binary that cannot run under bionic, so without the override every build dies in `processDebugResources`. It is not in the repo because it is an absolute path for this machine only.
- **The SDK is at `~/android-sdk`, installed by a real `sdkmanager`**, and `local.properties` points at it, so no `ANDROID_HOME` is needed. The cmdline-tools scripts have a `#!/usr/bin/env` shebang termux cannot resolve; `termux-fix-shebang` on `cmdline-tools/latest/bin/*` fixes it once.
- `compileSdk = 37` resolves to `platforms;android-37.0` specifically; `android-37.1`, the Android 17 minor release, does not satisfy it.
- **AGP auto-installs `build-tools` and `platform-tools`, and both are x86_64.** aapt2 comes from the override and D8, R8 and apksigner are JVM tools, so an AGP task that shelled out to a native build-tools binary would fail with an exec format error.

## Running on the phone

- **The debug package is `com.lucasta.edit.debug`**, which is what to force-stop between runs. Install it with `adb install -r app/build/outputs/apk/debug/app-debug.apk` and launch it with `adb shell am start -n com.lucasta.edit.debug/com.lucasta.edit.MainActivity`: the activity keeps its unsuffixed class name, so the `.MainActivity` shorthand only works with the release package, `com.lucasta.edit`.
- **Which toolbar row is on screen depends on the keyboard.** The file row is not there while the IME is up, and the file's name is there only while what it says is holding it. The buttons share the width by weight and take the tap across the whole button, so the centre of one is a safe coordinate. In landscape with the keyboard up a dump reported the name bar as 11 px tall while a screenshot showed it whole, so take its height from a screenshot.
- **Test saving through a file picked with Open, not through a MediaStore link.** A picked file goes through the external storage provider, which reads straight off the disk but writes through the storage layer every app shares; a MediaStore link, as `am start` hands one over, is disk on both sides, hides that layer and saves much faster. Replacing the file under a MediaStore link also takes the grant with it, so testing replacement needs a picked file too. A large save through the picker swings several-fold from one run to the next, and that is the layer and the flash, not the app.

## Measuring opens

`adb shell am start -a android.intent.action.VIEW -d content://media/external/file/<id> --grant-read-uri-permission -n com.lucasta.edit.debug/com.lucasta.edit.MainActivity`, then `adb logcat -s edit.perf`, which the temporary timing helpers in `Document.kt` write to. Do not read `setText` in the log as the cost of the index: it is a suspend call, so on a document indexed off the main thread it includes however long the main thread took to get back to it, which during startup is most of it. A first launch after an install runs about twice as slow across the board, so take the second run.
