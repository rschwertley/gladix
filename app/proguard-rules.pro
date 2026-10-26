# CRITICAL — DO NOT REMOVE. This block is the COMPLETE extension ABI: everything a dynamically-loaded
# extension APK links against but does NOT bundle, so the app (parent classloader) must provide it by
# its ORIGINAL name at runtime. R8 (since AGP 9.2.1 or 9.3.1 — git can't separate them) repackages
# classes into the default package by default, moving/renaming them and making every extension fail
# to load (NoClassDefFoundError — first common.**, then kotlin.jvm.functions.Function0, etc.). The
# set is derived from the canonical extension
# contract (echo-extension-template / deezer-extension/app: compileOnly(dev.brahmkshatriya.echo:common)
# + compileOnly(kotlin-stdlib)), where :common api-exposes kotlinx + okhttp + protobuf — NOT from chasing
# individual crashes. Parent-first DexClassLoader delegation + the extension's own -dontobfuscate mean an
# in-contract extension cannot reference a host package outside this set. After ANY AGP/R8/proguard change,
# the verifyExtensionAbi task (app/build.gradle.kts) fails the build if any anchor stops self-mapping in
# build/outputs/mapping/<variant>/mapping.txt.
#
# [CORRECTED 2026-10-26] THE LINE ABOVE SAID "since AGP 9.1", AND GIT REFUTES IT. The repo shipped
# on agp = 9.1.0 then 9.1.1 from 2026-06-21 (c7680c01, a6ada124) until 2026-07-27 with extensions
# loading fine; the break arrives in the window after the 9.1.1 -> 9.2.1 bump (5382928f,
# 2026-07-27). The fix
# commit e2743274 (2026-07-30) is titled "...broken by R8 repackaging (AGP 9.x)" — deliberately
# vague — and was authored while agp = 9.3.1 (bumped the day before by cf60b564). So git proves it
# was NOT 9.1, but cannot separate 9.2.1 from 9.3.1, one day apart; neither the notes nor git pick
# between them, so both stand above.
# HOW THE WRONG NUMBER GOT IN, because that is the transferable part: the OTHER July R8 incident —
# the protobuf finalization LinkageError fixed by dd63cd08 (2026-07-12) — genuinely WAS on AGP
# 9.1.1, and its note in common/src/commonMain/resources/META-INF/proguard/consumer-proguard-rules.pro
# correctly says "AGP 9.1's more aggressive R8". That correct attribution was reused for this
# unrelated, later break. Two R8 regressions five weeks apart, two different AGP versions — do not
# collapse them again.
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


# 6. The Cast OptionsProvider, instantiated BY REFLECTION from the manifest's
#    com.google.android.gms.cast.framework.OPTIONS_PROVIDER_CLASS_NAME meta-data. AGP derives keep
#    rules from manifest android:name attributes; a meta-data android:value is an opaque STRING, so
#    nothing tells R8 this class is reachable and it is free to rename or remove it. The resulting
#    failure is release-only, and CastAvailability catches it - so it would present as a missing
#    Cast button with one logcat line, not as a crash. Anchored in verifyExtensionAbi so R8 renaming
#    it fails the build instead.
#    Narrow on purpose: this is one reflectively-named class, not an ABI sweep, so no `.** { *; }`.
-keep class dev.brahmkshatriya.echo.cast.CastOptionsProvider { <init>(); }
# anchor: dev.brahmkshatriya.echo.cast.CastOptionsProvider


# 7. Exception types we report to Crashlytics. SAME FAILURE MODE AS RULE 5, DIFFERENT TRIGGER, and
#    the same fix - so read rule 5's note first; this is its generalisation.
#    Crashlytics fixes an issue's identity AT INGEST from the class name in the frame, and then
#    deobfuscates for DISPLAY. If a class has no entry in mapping.txt the ingest name is the
#    obfuscated one and there is nothing to map back, so the issue is filed under whatever R8 chose
#    THAT BUILD: PlayerException arrived as `mn3` in build 1129 and `on3` in 1132, and the two were
#    separate issues for the same defect. Note what this is NOT: the mapping file IS uploaded (the
#    Crashlytics plugin 3.0.8 defaults mappingFileUploadEnabled to variant.isMinifyEnabled, decoded
#    from CrashlyticsPlugin bytecode 2026-10-17), which is why the SURROUNDING frames in those same
#    reports read correctly as PlayerEventListener$onPlayerError$9.invokeSuspend. Only the exception
#    class was missing, because R8 had merged or inlined it away.
#    `{ *; }` IS LOAD-BEARING FOR THE SAME REASON IT IS IN RULE 5: an exception whose fields are
#    stripped as unused becomes structurally identical to its siblings and therefore mergeable, and
#    a merged class cannot have its own name. Keeping the members is what keeps them distinct.
#    ⚠️ THE LINE NUMBER IN THE FRAME STILL MOVES BUILD TO BUILD and nothing here changes
#    that - onPlayerError's lambda index and :2036 will keep shifting. Crashlytics tolerates that
#    within one issue; what it cannot tolerate is the class name changing, which is all this fixes.
#    ⚠⚠ [CORRECTED 2026-10-18] SCOPED BY PACKAGE AND THAT MISSED MOST OF THEM.
#    The three packages named below covered playback/extensions/download only, and the exceptions
#    that actually kept re-filing were NESTED IN NON-EXCEPTION OUTER CLASSES, where no package
#    pattern reaches: build 1136 still showed "xd" (ExtensionsViewModel's "Update failed for ..."
#    wrapper) and "pd" (AppUpdater$GithubNoReleasesException), and the same "No releases found"
#    report filed separately on 1133 and 1136. The old note even LISTED those classes as
#    "deliberately not covered ... they have not churned", which was wrong on the second clause.
#    One rule over every Throwable has no such gap, and it needs no maintenance when a new
#    exception is added in a new place - which is the other half of why the package list failed.
#    ⚠️ -keepnames, NOT -keep: NAMES ONLY, SHRINKING STILL ALLOWED. `-keepnames` is
#    `-keep,allowshrinking`, so an exception class nothing constructs is still removed; only the
#    names of the ones that survive are pinned. That is the whole requirement - Crashlytics groups
#    on the class name at ingest - and it is why this costs almost nothing compared with the `{ *; }`
#    form, which would also pin every member of every Throwable in every dependency.
#    ⚠️ IT ALSO SUBSUMES THE ANTI-MERGE PROPERTY RULE 5 GETS FROM `{ *; }`: a class
#    whose name must survive cannot be horizontally merged into a sibling. Rule 5 is deliberately
#    left alone anyway - it keeps the OUTER HealthMonitor class too, which is not a Throwable, and
#    its fields are read by the report itself.
#    ⚠⚠ IF AN ANCHOR BELOW EVER FAILS, ASK WHICH OF TWO THINGS HAPPENED BEFORE
#    CHANGING THE RULE. A missing mapping.txt line means EITHER R8 renamed the class (the rule is
#    broken - a real failure) OR R8 shrank it away because nothing constructs it any more (the rule
#    is working and the anchor is simply a bad choice). The anchors are picked to be classes that
#    are definitely thrown, so the second case should not arise; if it does, move the anchor rather
#    than widening the rule.
-keepnames class * extends java.lang.Throwable
# anchor: dev.brahmkshatriya.echo.playback.exceptions.PlayerException, extensions.exceptions.ExtensionNotFoundException, download.exceptions.DownloadException, utils.AppUpdater$GithubNoReleasesException


# Preserve generics + all annotation variants + nested/lambda linkage so kotlinx.serialization type
# resolution and suspend/lambda types crossing the extension classloader boundary still resolve after
# shrinking (proguard-android-optimize keeps *Annotation* but NOT Signature/InnerClasses/EnclosingMethod).
-keepattributes Signature,Exceptions,InnerClasses,EnclosingMethod,*Annotation*
