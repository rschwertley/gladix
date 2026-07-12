-dontobfuscate
# No `allowoptimization` on the extension contract: extensions subclass open/abstract
# contract types (e.g. PagedData) and override their methods, so R8 must not finalize
# them — same finalization shape as the protobuf case below.
-keep class dev.brahmkshatriya.echo.common.** { public protected *; }
-keep,allowoptimization class kotlin.** { public protected *; }
-keep,allowoptimization class kotlinx.coroutines.** { public protected *; }
-keep,allowoptimization class kotlinx.serialization.** { public protected *; }
# No `allowoptimization`: extensions subclass okhttp3 abstract types (EventListener,
# etc.) and override methods — R8 finalization would break them at runtime.
-keep class okhttp3.** { public protected *; }
# NOTE: no `allowoptimization` here. Extensions are loaded at runtime via DexLoader
# (parent-first), so their protobuf-generated messages subclass the HOST's
# com.google.protobuf.GeneratedMessage and override methods like getUnknownFields().
# `allowoptimization` lets R8 finalize those methods (nothing in the host overrides
# them, so R8 thinks it's safe) → runtime "overrides final method in GeneratedMessage"
# LinkageError. This surfaced with AGP 9.1's more aggressive R8; AGP 8.x didn't exercise
# it. Plain -keep excludes protobuf from optimization so extension overrides stay valid.
-keep class com.google.protobuf.** { public protected *; }

-dontwarn com.oracle.svm.core.annotate.Delete
-dontwarn com.oracle.svm.core.annotate.Substitute
-dontwarn com.oracle.svm.core.annotate.TargetClass
-dontwarn java.lang.Module
-dontwarn org.graalvm.nativeimage.hosted.Feature$BeforeAnalysisAccess
-dontwarn org.graalvm.nativeimage.hosted.Feature
-dontwarn org.graalvm.nativeimage.hosted.RuntimeResourceAccess
