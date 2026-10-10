# CRITICAL — DO NOT REMOVE. This block is the COMPLETE extension ABI: everything a dynamically-loaded
# extension APK links against but does NOT bundle, so the app (parent classloader) must provide it by
# its ORIGINAL name at runtime. R8 (since AGP 9.1) repackages classes into the default package by
# default, moving/renaming them and making every extension fail to load (NoClassDefFoundError — first
# common.**, then kotlin.jvm.functions.Function0, etc.). The set is derived from the canonical extension
# contract (echo-extension-template / deezer-extension/app: compileOnly(dev.brahmkshatriya.echo:common)
# + compileOnly(kotlin-stdlib)), where :common api-exposes kotlinx + okhttp + protobuf — NOT from chasing
# individual crashes. Parent-first DexClassLoader delegation + the extension's own -dontobfuscate mean an
# in-contract extension cannot reference a host package outside this set. After ANY AGP/R8/proguard change,
# the verifyExtensionAbi task (app/build.gradle.kts) fails the build if any anchor stops self-mapping in
# build/outputs/mapping/<variant>/mapping.txt.
#
# ⚠️ ADDING A KEEP RULE HERE? ADD AN ANCHOR TO `critical` IN app/build.gradle.kts TOO. A keep rule with no
# anchor is UNVERIFIED: R8 can repackage that whole package and verifyExtensionAbi still reports "ABI
# intact". Each rule below names its anchor(s) so a rule without one is visible at a glance. (okio and
# protobuf were unanchored 2026-08-01 → 2026-08-24 — the gap this annotation exists to prevent.)
#
# EVERY rule's annotation starts with the SAME singular token `# anchor:` even when it lists several
# classes, so `grep -n "^# anchor:"` returns one line per keep rule and a missing line is a real gap.
# A plural `# anchors:` variant made a colon-anchored grep return five of seven and read as a two-rule
# gap that did not exist (2026-08-24). Do not reintroduce the plural form.

# 1. Our own ABI module.
-keep class dev.brahmkshatriya.echo.common.** { *; }
# anchor: ExtensionClient, TrackClient, AlbumClient, RadioClient, Track, EchoMediaItem

# 2. Kotlin stdlib — extensions declare it compileOnly and rely on the app at runtime. Covers function
#    types (kotlin.jvm.functions.Function0..N), suspend machinery (kotlin.coroutines.Continuation),
#    kotlin.Result/Unit/Pair, collections, text/regex/sequences, and @kotlin.Metadata.
-keep class kotlin.** { *; }
# anchor: kotlin.jvm.functions.Function0, Function1, kotlin.coroutines.Continuation

# 3. Coroutines + serialization — :common api-exposes these (Flow/StateFlow/SharedFlow in signatures,
#    @Serializable models). Extensions compile against them transitively and do not bundle them.
-keep class kotlinx.coroutines.** { *; }
# anchor: kotlinx.coroutines.flow.Flow
-keep class kotlinx.serialization.** { *; }
# anchor: kotlinx.serialization.KSerializer

# 4. okhttp (+okio) and protobuf — :common api-exposes these (OkHttpClient/Call in ContinuationCallback,
#    protobuf in settings). compileOnly/transitive in extensions -> the app must provide them by name.
-keep class okhttp3.** { *; }
# anchor: okhttp3.OkHttpClient
-keep class okio.** { *; }
# anchor: okio.ByteString
-keep class com.google.protobuf.** { *; }
# anchor: com.google.protobuf.MessageLite

# 5. HealthMonitor report types. NOT extension ABI - a different failure mode, kept here because
#    this file is the only place that can prevent it.
#    Crashlytics groups a non-fatal on the exception CLASS, so the 2026-09 split of
#    ConsecutiveSkipException into Stall/Network/Unavailable/Internal/Error families only EXISTS if
#    each family survives R8 as its own named class. In build 1119 it did not: only
#    ConsecutiveSkipErrorException had a class entry in mapping.txt (as "qx1"); the other four and
#    ConsecutiveSkipException had none, their constructors appearing only as frames inlined into
#    PlayerEventListener.reportAndResetConsecutiveSkips. One dex class for all five families, so
#    per-family mute - the entire point of the split - was impossible, and health_report_type read
#    "qx1" for every report.
#    `{ *; }` IS LOAD-BEARING, NOT HABIT: the merge was enabled by R8 stripping skipCount /
#    lastExtensionId / lastCauses as unused (nothing in-app reads them - only the message), which
#    left the five subclasses structurally identical and therefore mergeable. Keeping the members is
#    what keeps them distinguishable. The OUTER class is kept too, so a nested name cannot drift
#    from an obfuscated enclosing name.
#    The previous rule 5 - XML-instantiated OverlapScrollingViewBehavior - went away with that class
#    in c9649426 (2026-09-07); verifyExtensionAbi's failure message still named it until 2026-10-10.
-keep class dev.brahmkshatriya.echo.utils.HealthMonitor** { *; }
# anchor: dev.brahmkshatriya.echo.utils.HealthMonitor, HealthMonitor$ConsecutiveSkipUnavailableException


# Preserve generics + all annotation variants + nested/lambda linkage so kotlinx.serialization type
# resolution and suspend/lambda types crossing the extension classloader boundary still resolve after
# shrinking (proguard-android-optimize keeps *Annotation* but NOT Signature/InnerClasses/EnclosingMethod).
-keepattributes Signature,Exceptions,InnerClasses,EnclosingMethod,*Annotation*
