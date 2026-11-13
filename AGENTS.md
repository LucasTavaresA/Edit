# Edit

I want to ultimately have a text editing app, i also want it to handle encrypted files and open everything else normally, cause other apps and encryption tools are too complex, have their custom sync and format, i want to have it all locally sync-able with any tool.

You maintain PROGRESS.md which tracks our progress and keep all info across compactions, read it fully at the beginning of every conversation, it strictly defines what we have and needs to be explicitly decided against with me when going to a different direction.

We maintain AGENTS.md, but avoid modifying things here as much as you can, try to append instead, add things that don't fit PROGRESS.md here, organize into sections.

## Git

Always attribute commits to yourself naming your model like "claude opus 5.5", this repo is owned and maintained by you, don't put me in any commits.

## Markdown

Written as one long line per paragraph or list item, never hard wrapped. The editor wraps visually.

## PROGRESS.md

PROGRESS says what things are supposed to be and what is left to do, as concepts another instance cannot re-derive by reading the code: what a constraint is, what it costs, what it rules out. No implementations: the code can be worked out, usually better than a note written before the problem was in front of it, and a stale prescription is worse than none. Nothing about what was tried, tested or dropped.

A decision earns a place only if it is spread across the code rather than sitting in one place, or if it is unintuitive but intentional, the kind another instance would otherwise "fix". Anything a reader finds by opening the one file it lives in stays out, and so does the mechanism behind a decision: keep the why and what it rules out, in as few sentences as carry it.

## Building

- **Anything a build needs only because of the machine it runs on stays out of the repo**: memory settings, toolchain overrides and the SDK's location belong to the machine, in `~/.gradle/gradle.properties` and the untracked `local.properties`.
- **Lint has to be asked for.** `assembleDebug` does not run it and the release hook is off. `gradle lintDebug` takes several minutes cold. It ends in `BUILD FAILED` whenever lint finds an error, which is lint's verdict and not the build breaking; one line per finding is in `app/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt`, and the full detail in `app/build/reports/lint-results-debug.sarif`. Occasionally rather than never, not per change.
- `compileSdk = 37` resolves to `platforms;android-37.0` specifically; `android-37.1`, the Android 17 minor release, does not satisfy it.

## Running the app

- **The debug package is `com.lucasta.edit.debug`**, which is what to force-stop between runs. Install it with `adb install -r app/build/outputs/apk/debug/app-debug.apk` and launch it with `adb shell am start -n com.lucasta.edit.debug/com.lucasta.edit.MainActivity`: the activity keeps its unsuffixed class name, so the `.MainActivity` shorthand only works with the release package, `com.lucasta.edit`.
- **Which toolbar row is on screen depends on the keyboard.** The file row is not there while the IME is up, and the file's name is there only while what it says is holding it. The buttons share the width by weight and take the tap across the whole button, so the centre of one is a safe coordinate. In landscape with the keyboard up a dump reported the name bar as 11 px tall while a screenshot showed it whole, so take its height from a screenshot.
- **Test saving through a file picked with Open, not through a MediaStore link.** A picked file goes through the external storage provider, which reads straight off the disk but writes through the storage layer every app shares; a MediaStore link, as `am start` hands one over, is disk on both sides, hides that layer and saves much faster. Replacing the file under a MediaStore link also takes the grant with it, so testing replacement needs a picked file too. A large save through the picker swings several-fold from one run to the next, and that is the layer and the flash, not the app.
- **What a keyboard sends can be traced** by returning the editor's input connection wrapped in a `java.lang.reflect.Proxy` that logs each call with its arguments, its answer and the selection after it. Temporary only; it is not to be committed.
- **The text size a pinch leaves is kept across starts**, so put it back after testing one.
- **Edit is a single instance, so `am start -d <link>` at a running Edit joins its list of files** through `onNewIntent`; no force-stop is needed to hand over several. A file handed over that way is not kept across a start, since its access ends with the process, unless the intent also carries `--grant-persistable-uri-permission`, which is how to put files in the kept list without going through the picker. The kept list is readable with `adb shell run-as com.lucasta.edit.debug cat shared_prefs/files.xml`.
- **Where the screen stood in the file on screen is written down when Edit goes out of sight**, so a test of a start sends Edit home before `am force-stop`; stopped while in front it is killed before being told, and what comes back is what was written when that file last left the screen.
- **Only the toolbar is in a `uiautomator dump`.** The rows are drawn by hand and there is no text field, so no text reaches the accessibility tree; read the text off a screenshot.

## Measuring opens

`adb shell am start -a android.intent.action.VIEW -d content://media/external/file/<id> --grant-read-uri-permission -n com.lucasta.edit.debug/com.lucasta.edit.MainActivity`, then `adb logcat -s edit.perf`, which the temporary timing helpers in `Document.kt` write to. Do not read `setText` in the log as the cost of the index: it is a suspend call, so on a document indexed off the main thread it includes however long the main thread took to get back to it, which during startup is most of it. A first launch after an install runs about twice as slow across the board, so take the second run.
