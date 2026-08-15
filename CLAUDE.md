# Echo — working notes for Claude Code

Read [docs/PRODUCT_VISION.md](docs/PRODUCT_VISION.md) and
**[docs/PRIVACY_PRINCIPLES.md](docs/PRIVACY_PRINCIPLES.md)** before changing anything.

## Hard rules

- **Never** add a way for message bodies to reach analytics, logs, crash reports, or any
  admin surface. `:core:analytics` has no `String` parameter type; do not add one.
- **Never** design custom cryptography.
- **Never** declare UI, animation, or haptics "done" from a desk. They are done when the
  developer has felt them on the Pixel and said so.

## Machine setup (this Windows on ARM64 box)

| Tool | Location |
|---|---|
| JDK | `C:\Program Files\Microsoft\jdk-25.0.4.7-hotspot` |
| Android SDK | `C:\Android\Sdk` |
| Gradle (bootstrap) | `C:\Gradle\gradle-9.7.0` |
| Project | `C:\dev\echo` |

`JAVA_HOME`, `ANDROID_HOME` and PATH entries are set at user scope.
`org.gradle.java.home` is set in `C:\Users\yukii\.gradle\gradle.properties`.

### The project must stay on an ASCII path

AGP refuses to build when the project path contains non-ASCII characters. The original
working directory (`C:\Users\yukii\Documents\Claude\メッセージアプリ開発`) therefore holds
only a pointer and a junction named `echo`. **Do not move the project back.**

### Windows ARM64 notes

- The Android **emulator is not usable** here. Develop against the Pixel over adb.
  This suits the project anyway: haptics can only be judged on real hardware.
- `aapt2` / `d8` run under x64 emulation, so builds are slower than on x64 hardware.

## Build and run

```bash
cd /c/dev/echo/android && ./gradlew installDebug
```

```bash
cd /c/dev/echo/android && ./gradlew testDebugUnitTest
```

Launch on device:

```bash
adb shell am start -n jp.echo.android.debug/jp.echo.android.MainActivity
```

Screenshot:

```bash
adb shell screencap -p /sdcard/s.png && adb pull /sdcard/s.png
```

## Versions

Deliberately the latest stable of everything (the developer asked for this):
Gradle 9.7.0, AGP 9.3.1, Kotlin 2.4.10, JDK 25, compileSdk/targetSdk 37, minSdk 24,
Compose BOM 2026.08.00.

Two things that are easy to get wrong:

- **AGP 9 has built-in Kotlin.** The `org.jetbrains.kotlin.android` plugin must NOT be
  applied — AGP fails the build if it is. `org.jetbrains.kotlin.plugin.compose` is still
  required wherever `buildFeatures.compose = true`.
- AGP 9.3.1 bundles KGP 2.2.10. The root `build.gradle.kts` raises it to 2.4.10 through a
  `buildscript` classpath entry. Keep the Compose Compiler plugin on the same version.

## Module layout

| Module | Contains |
|---|---|
| `:core:haptics` | The haptic design system. `HapticTokens.kt` is the tuning table |
| `:core:designsystem` | Colours, type, motion, swipe geometry |
| `:core:analytics` | Event schema. Privacy enforced by types |
| `:app` | Screens |

## Tuning haptics

All haptic numbers live in
`android/core/haptics/src/main/java/jp/echo/android/core/haptics/HapticTokens.kt`.
Nothing else in the app contains a haptic magic number.

Loop: the developer feels a token in **Haptic Lab** (ripple icon, top right of the chat
list) → says what is wrong → change one number → `./gradlew installDebug`.
Keep [docs/HAPTIC_DESIGN.md](docs/HAPTIC_DESIGN.md) in sync when values change.

Measured on the Pixel 10 (API 37): the envelope engine's minimum control-point duration
is **20 ms**, so a 2-point envelope cannot be shorter than 40 ms. Short, instantaneous
tokens are therefore capped at the primitive tier via `HapticSpec.preferredMaxTier`.
The top tier is not automatically the best tier.

## A trap that already cost one round

Never put `pointerInput` on a node that the same gesture translates. Pointer positions are
reported relative to that node, so the drag subtracts itself and the element tracks at half
finger speed while oscillating around the equilibrium — it looks like the bubble is drawn
twice, and the oscillation re-crosses the threshold so the haptic fires more than once.
Attach the gesture to the stationary container and move a child.

`DebugAnalytics` makes this kind of thing measurable without adding logging:

```bash
adb logcat -c && adb shell input swipe 150 1083 560 1083 1200 && adb logcat -d | grep EchoAnalytics
```

Compare `time_to_threshold_ms` against what the geometry predicts. Guessing at gesture bugs
from a screenshot does not work; the timings say plainly whether it is right.

## Sharing the Pixel with four other agents

One device, five agents. Read
`C:\Users\yukii\Documents\Codex\2026-07-22\new-chat\shared-context\DEVICE-LOCK.md`
before any adb command — `gradlew installDebug` counts, it runs adb underneath.

The lock is `shared-context\device-lock.txt`. Absent means free. **Claim it as
`OWNER=echo`**, not `claude`: several of the five run on Claude, so a lock reading
`claude` tells nobody whose it is. Release it the moment you stop, and verify the
delete before saying it is released.

Before injecting taps, screenshot first and confirm Echo is actually on screen. Reusing
coordinates from an earlier run once landed a tap in the owner's private Instagram
conversation, because they had picked the phone up in the meantime.

## Delivering a build

After every `tools/release.ps1` run, **attach the files in the conversation** — the
release APK, the debug APK, the source zip, and the release notes. Writing the path and
expecting them to be fetched from disk is not delivery; the owner asked for the files
themselves, every time.

## Refresh rate

Panels run from 1 Hz to 144 Hz and change rate while the app runs. **Never hardcode a
rate or a frame budget** — derive it from `EchoRefreshRate.read()`. Never drive anything
off a frame count; animations must be time-based so 1 Hz does not stall them and 144 Hz
does not speed them up.

Ask for a high rate only on what is moving, via `Modifier.preferHighFrameRate(active)`.
Holding the panel high while someone reads a message spends their battery for nothing.

Measured on the Pixel 10 while scrolling: total frame 90th percentile 11–12 ms against an
8.33 ms budget, but **GPU 99th percentile is only 2 ms**. The bottleneck is the UI thread,
not the GPU — which matters when judging whether a GPU-heavy effect can be afforded.

## Signing

The release keystore lives outside the repo at `C:\dev\echo-keys\echo-release.jks`, with
its password in `android/keystore.properties` (gitignored, never committed). Losing
either means the app can never be updated again — see [docs/SIGNING.md](docs/SIGNING.md).

`tools/release.ps1` scans the finished source zip for secrets and destroys it if any are
found. That guard exists because an exclusion list alone already failed once.

## Current phase

**Prototype 0** — local only, no network, Android only. See [docs/ROADMAP.md](docs/ROADMAP.md).
Do not start Prototype 1 work (Firebase, accounts, push) until the developer confirms the
feel of Prototype 0 on the device.
