# Prototype 0: no third-party SDKs yet, default AGP + Compose rules are sufficient.

# ---------------------------------------------------------------------------- E2EE
#
# The crypto AAR ships a proguard.txt that is **zero bytes long**. It declares no keep
# rules of its own, so nothing protects it from R8 — and everything it needs protecting
# for is reached by reflection, which R8 cannot see.
#
# JNA binds Java to native code by looking classes and fields up by name at runtime.
# Renaming any of them does not fail at build time and does not fail at start-up; it fails
# the moment the crypto engine is first opened, as an exception with no obvious connection
# to shrinking. Debug builds are unaffected because R8 does not run.
#
# So: keep the bridge, all of it. The cost is a handful of kilobytes of class metadata.
-keep class com.sun.jna.** { *; }
-keep class * extends com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.Structure { *; }
-keep interface com.sun.jna.** { *; }

# The UniFFI-generated bridge, and the Kotlin API sitting on top of it. Callback interfaces
# here are invoked *from Rust*, which R8 also cannot see as a use.
-keep class uniffi.** { *; }
-keep class org.matrix.rustcomponents.sdk.crypto.** { *; }

# JNA is compiled against desktop AWT classes that do not exist on Android and are never
# reached there. Without this the build fails on references R8 cannot resolve.
-dontwarn java.awt.**
-dontwarn javax.swing.**

# WebRTC, all of it.
#
# ## What went wrong without this
#
# A release build **aborted the moment a call started**, inside the native library's
# JNI_OnLoad, before any of this project's code ran. The library loaded fine; it then
# looked up org.webrtc.ContextUtils.getApplicationContext by name and did not find it,
# because R8 had renamed it to `h`. Native code that resolves Java members by string
# cannot be seen by a shrinker: there is no reference to follow, so every one of those
# classes looked unused.
#
# ## Why it went unnoticed for so long
#
# **Calls were only ever tested on debug builds, where R8 does not run.** The fixed
# download link served the debug APK, so the build people installed and the build that
# would ship behaved differently in exactly the place nobody was looking. That link now
# serves the release build (tools/publish.sh), which is what surfaced this.
#
# The whole package is kept rather than a list of the members the native side happens to
# use today. A list would be a guess about somebody else's C++, and getting it wrong
# produces this same crash on a phone, months later, with a stack that points at a
# library rather than at the rule that removed the method.
-keep class org.webrtc.** { *; }
-keepclassmembers class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Exception class names survive shrinking.
#
# A crash report that says "ol1" is not a report. The names cost a few hundred bytes and
# they are the difference between a diagnosis and another round trip to a physical device
# — which, for this project, means asking somebody to go and press a button again.
-keepnames class * extends java.lang.Throwable
