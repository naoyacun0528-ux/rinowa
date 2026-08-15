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

## Current phase

**Prototype 0** — local only, no network, Android only. See [docs/ROADMAP.md](docs/ROADMAP.md).
Do not start Prototype 1 work (Firebase, accounts, push) until the developer confirms the
feel of Prototype 0 on the device.
