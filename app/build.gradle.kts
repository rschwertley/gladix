// Suppresses the IDE "the 'apply' plugin syntax is older and not recommended" inspection for the
// CONDITIONAL apply(plugin = …) calls below (gms/crashlytics), which cannot move to the plugins {}
// block because they must apply only when google-services.json is present (see the NOTE there).
//
// ⚠️ "GrDeprecatedAPIUsage" IS ALMOST CERTAINLY THE WRONG ID AND IS DOING NOTHING. The `Gr` prefix is
// GROOVY (the inspection ships with the Groovy plugin and targets .gradle files); this is a .gradle.KTS
// file, so it never matches and the warning keeps appearing. An unknown id is silently ignored, which is
// exactly why this looked settled and was not. Left in place only so the next person sees this note
// rather than re-deriving it.
// TO FIX PROPERLY: put the caret on the warning at the apply() calls below, Alt+Enter -> "Suppress for
// file", and let the IDE insert the correct id for your Android Studio version. Then delete this one.
@file:Suppress("GrDeprecatedAPIUsage", "AvoidDuplicateDependencies", "AvoidApplyPluginMethod")

import java.io.File
// ⚠️ REQUIRED — `java.util.Properties()` WRITTEN INLINE DOES NOT COMPILE HERE, and the error does not
// say why. In a .gradle.kts with the Java/Android plugin applied, `java` resolves to the generated
// JavaPluginExtension ACCESSOR, which SHADOWS the root `java` package. So `java.util.Properties()` parses
// as <javaExtension>.util and fails with "Unresolved reference 'util'", taking the surrounding
// runCatching/apply/load/getProperty down with it as inference cascades. The file already imports
// java.io.File for the same reason; keep new java.* types imported here rather than fully qualified.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlinx.serialization)

    alias(libs.plugins.gms) apply false
    alias(libs.plugins.crashlytics) apply false
}

// True only when a PARSEABLE google-services.json is present. CI (nightly/stable) writes this file
// by base64-decoding the GOOGLE_SERVICES_B64 secret; an empty/misconfigured secret yields an empty
// or malformed file that would otherwise pass a bare exists() and hard-fail processGoogleServices.
// Parsing here degrades a bad/absent JSON to the Firebase-free (compileOnly) path instead.
val hasGoogleServices = file("google-services.json").let { f ->
    f.exists() && runCatching { groovy.json.JsonSlurper().parse(f); true }.getOrDefault(false)
}
// Last.fm api_key for the endless-queue radio fallback (see RadioFallback). Computed at TOP LEVEL beside
// hasGoogleServices, matching this file's existing shape for "derive a build input once, feed it to
// buildConfigField" — the first version computed it inside defaultConfig { }, which works in principle but
// put a multi-line file read in the middle of the variant config.
//
// THERE IS NO FIRST-CLASS GRADLE READER FOR local.properties, and it is worth saying so because the
// obvious candidate is wrong: providers.gradleProperty() reads gradle.properties / -P / systemProp, NOT
// local.properties, so it cannot be used here — and gradle.properties is TRACKED in this repo (checked
// 2026-09-09), which is the whole reason the key is not there. Properties + a guarded file read is the
// standard pattern for this file.
// providers.environmentVariable() IS used for the env fallback rather than System.getenv(): it is the
// configuration-cache-friendly API, and this file already cares about that (see verifyExtensionAbi's
// note on resolving everything at configuration time).
//
// Order is local.properties first, then LASTFM_API_KEY env var (the CI path). Missing file, unreadable
// file, missing key and empty value ALL degrade to "" — no build failure on any of them, which is
// required: contributor and CI builds have no local.properties and must still build. Empty is a
// first-class disable, same shape as HAS_FIREBASE.
val lastFmApiKey: String = run {
    val fromLocal = rootProject.file("local.properties").takeIf(File::exists)?.let { f ->
        runCatching {
            Properties().apply { f.inputStream().use { load(it) } }.getProperty("lastfm.apiKey")
        }.getOrNull()
    }
    fromLocal?.takeIf { it.isNotBlank() }
        ?: providers.environmentVariable("LASTFM_API_KEY").orNull
        ?: ""
}
val gitHash = runCatching { execute("git", "rev-parse", "HEAD").take(7) }.getOrDefault("dev")
val gitCount = runCatching { execute("git", "rev-list", "--count", "HEAD").toInt() }.getOrDefault(1)
val isDirty = runCatching { execute("git", "status", "--porcelain", "-uno").isNotEmpty() }.getOrDefault(false)
// "3.1." prefix + zero-padded gitCount so versionName sorts NUMERICALLY as a string in Firebase Crashlytics
// Release Monitoring (which orders the version picker lexicographically). Two things this fixes:
//  • the 3→4 digit lexicographic break ("1000" < "999"): padStart(5,'0') → "01024" > "00999" as strings;
//  • the frozen un-padded 3.0.xxx history: bumping the prefix to 3.1. sorts every new build above all old
//    "3.0.###" entries at once (they can't be re-padded retroactively).
// versionCode stays the raw gitCount (Android requires an Int; it's already monotonic). Display stays tied
// to the count: "3.1.01024" == count 1024, just padded.
val version = "3.1." + gitCount.toString().padStart(5, '0')

// ── APK FILENAME CARRIES THE VARIANT. AGP names an APK "<archivesName>-<variantName>.apk", so setting
// archivesName here yields Gladix-v3.1.NNNNN-release.apk and Gladix-v3.1.NNNNN-debug.apk. The version is
// already in the name; what was missing is the VARIANT, and its absence is what let a debug build be
// uploaded to GitHub for months without it being visible on the releases page — a renamed debug APK and a
// renamed release APK looked identical there. With the marker, the wrong artifact announces itself.
//
// `base { archivesName }` rather than rewriting variant.outputs: the output-renaming API needs
// VariantOutputImpl, which is AGP-internal and a poor bet on 9.3.2. This is the supported lever and it
// produces the same result.
//
// ⚠️ UPLOAD THE ...-release.apk. Nothing enforces that — it is a naming convention, not a gate.
base {
    archivesName = "Gladix-v$version"
}

android {
    namespace = "dev.brahmkshatriya.echo"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.rschwertley.gladix.auto"
        minSdk = 24
        targetSdk = 37
        // Note: versionCode only increments on git commits. 
        // For local development, consider committing frequently to update the version.
        versionCode = gitCount
        versionName = "v${version}_$gitHash${if (isDirty) "-dirty" else ""}($gitCount)"
        // True only when google-services.json is present. Compile-time constant used to guard
        // every Firebase call site so no-JSON builds never load the (compileOnly) Firebase classes.
        buildConfigField("boolean", "HAS_FIREBASE", "$hasGoogleServices")
        // Last.fm api_key for the endless-queue radio fallback (see RadioFallback). PUBLIC, READ-ONLY,
        // NO USER DATA, NO BILLING — the free tier needs only an api_key in the query string, and a key in
        // a shipped APK is assumed extractable. That is acceptable for this class of key; it would not be
        // for anything authenticated.
        // ⚠️ READ FROM local.properties, NOT gradle.properties. gradle.properties IS TRACKED in this repo
        // (checked 2026-09-09) so a key placed there would be committed; local.properties is git-ignored on
        // line 3 of .gitignore. LASTFM_API_KEY env var is the CI path. Empty is a FIRST-CLASS DISABLE, the
        // same shape as HAS_FIREBASE: RadioFallback.isEnabled is false, the fallback never fires, and
        // contributor and CI builds compile and run unchanged. Rotating the key needs no code change.
        // ⚠⚠ THIS VALUE EXISTS IN EXACTLY ONE PLACE, ON ONE MACHINE, AND IS IN NO BACKUP. local.properties
        // is git-ignored, so a fresh clone, a second machine or a CI runner builds with an EMPTY key and
        // the fallback silently disabled. SAME SHAPE AS THE DEBUG KEYSTORE (see the signingConfig note
        // below): one unversioned local file, no copy anywhere, and losing it is not detectable at build
        // time — the build succeeds. BACK IT UP OUTSIDE THE REPO.
        // Mitigated, not solved: RadioFallback logs `reason=no-api-key` when it would have fired, so a
        // keyless build is distinguishable from a working one in logcat rather than being silent. That is
        // the only signal; there is deliberately no build-time warning, because contributor and CI builds
        // must stay clean.
        buildConfigField("String", "LASTFM_API_KEY", "\"$lastFmApiKey\"")
    }

    // ── WHICH VARIANT GOES WHERE. Not derivable from this file, so it is written down. VERIFIED
    // AGAINST THE ACTUAL BUILD FLOW AND THE PUBLISHED ARTIFACTS ON 2026-09-05 — earlier revisions of this
    // note were WRONG (see the inheritance warning below), so treat it as the record and correct it here
    // rather than re-inferring from the variants that happen to exist.
    //
    //   release  -> GOOGLE PLAY, as an AAB, via Build > Generate Signed App Bundle (the wizard; output
    //              lands in app/release/app-release.aab, NOT build/outputs). AND, from 2026-09-05, the
    //              SIDELOADED APK on GitHub — see the signingConfig on the release block below, which is
    //              what makes that APK installable and upgradable.
    //              ⚠️ ON PLAY THE APP SELF-UPDATER IS OFF; EXTENSION UPDATES STAY ON. Play owns app
    //              updates. Decided by INSTALL SOURCE, not build type, so a release APK sideloaded from
    //              GitHub DOES self-update — deliberately, and it is why AppUpdater has a "release" arm.
    //              AppUpdater.isStoreInstall() is checked at the top of updateApp(), before any network
    //              work, and is fail-closed twice over: an empty installer list and a thrown exception
    //              both resolve to "store" (skip). updateApp() has exactly ONE caller
    //              (ExtensionsViewModel.update), so no entry point can route around it, and `force`
    //              lifts only the 24h throttle, never the gate.
    //   debug    -> WHAT WAS ACTUALLY SHIPPED TO GITHUB UNTIL 2026-09-05. Built with Build > Build APK(s)
    //              on the debug variant and renamed in place before upload. Confirmed from
    //              build/outputs/apk/debug/output-metadata.json (variantName "debug") and from the
    //              published v3.1.01063 asset being the same size as a local debug build. Unminified,
    //              unobfuscated, debuggable, and guarded by NEITHER verifyExtensionAbi (nothing to check —
    //              R8 never ran) NOR verifyCleanKotlinOutput (a real gap: that guard covers the stale-
    //              Kotlin-output bug from build 1058, which debug builds are equally exposed to).
    //              After the switch, debug is local development only.
    //   stable   -> ⚠️ UPSTREAM'S CHANNEL. NEVER BUILT HERE.
    //   nightly  -> ⚠️ UPSTREAM'S CHANNEL. NEVER BUILT HERE.
    //
    // ⚠️ STABLE AND NIGHTLY ARE INHERITED, NOT OURS, AND YOU CANNOT DERIVE THIS FORK'S DISTRIBUTION FROM
    // THEM. `git log -S '"nightly" ->' -- AppUpdater.kt` returns 422fc7e1 (2025-04-24, author
    // brahmkshatriya); the updater itself is 093cb8e1 (2025-04-02), whose message reads "add in app
    // updater, not tested, will not test". Those channels describe how brahmkshatriya/echo ships, were
    // inherited wholesale by this fork, and have never produced an artifact here. This fork's own 2026
    // commits (c361a884, cb536383, bfc32a5a) only added the install-source gate and the throttle around
    // them — they hardened a gate on channels nobody had checked applied.
    //
    // A 2026-09-02 revision of THIS note asserted "stable -> SIDELOADED APK / nightly -> SIDELOADED APK"
    // and "roughly 95% of users are on stable/nightly". Both were false, derived from the mere presence of
    // the variants. That is the error this paragraph exists to prevent: the variants prove only that
    // upstream had those channels.
    //
    // ⚠️ SIGNING KEY: release is signed with the DEBUG keystore, on purpose. See the signingConfig note on
    // the release block.
    buildTypes {
        release {
            // ⚠⚠ THE ONLY PROJECT-LEVEL LEVER OVER STUDIO'S SELECTED BUILD VARIANT. Studio
            // intermittently reopens on `debug` after `release` was selected. The selection is IDE state,
            // not project state: it lives in
            //   %LOCALAPPDATA%/Google/AndroidStudio<VERSION>/projects/<name>.<hash>/
            //       external_build_system/modules/Echo.app.xml
            //   as <option name="SELECTED_BUILD_VARIANT" value="release" />
            // keyed PER STUDIO VERSION (misc.xml has ExternalStorageConfigurationManager enabled, which is
            // also why this project has no .iml files). Studio makes a fresh directory on every update and
            // leaves the old one behind, so the selection does not survive an update - verified 2026-09-17,
            // five such directories existed, the four older ones dormant with `debug` and only the newest
            // carrying `release`. `/.idea` is git-ignored, but that is NOT the cause: the variant is not
            // stored in .idea at all.
            // This line is the one place the choice can be expressed in the REPO.
            //
            // ⚠️ WHAT IS VERIFIED vs WHAT IS NOT, because the distinction decides whether this
            // actually fixes the symptom:
            //   VERIFIED (AGP 9.3.2, decoded from the jars): ApplicationBuildType.setDefault/isDefault
            //     exists and is NOT deprecated; it is reachable here because
            //     ApplicationExtension.getBuildTypes() is NamedDomainObjectContainer<ApplicationBuildType>
            //     (it is NOT on the base BuildType); internal/ide/v2/ConvertersKt reads it when building
            //     the v2 tooling model, and com.android.builder.model.v2.dsl.BaseConfig declares
            //     `Boolean isDefault()`. So it genuinely reaches the IDE. It governs the INITIAL selection.
            //   NOT VERIFIED: whether it ALSO serves as the fallback when the stored selection is missing
            //     or unreadable - that is inside Android Studio and there is no source for it here. If the
            //     reset persists, that is the reason, and this line is still correct but insufficient.
            //
            // ⚠️ DEBUG WAS DEFAULT BY CONVENTION, NOT BY DECLARATION. Nothing in this project
            // has ever set isDefault (grep across every .kts/.gradle: zero hits), so this is making an
            // implicit choice explicit, NOT overriding a deliberate earlier one. It also matches practice:
            // the documented build workflow is Build Variants -> release, then Build APK(s).
            //
            // ⚠️ BLAST RADIUS IS THE DSL PROPERTY AND THE MODEL SERIALISER, NOTHING ELSE. Every
            // class under com/android/build in the AGP jar referencing isDefault is either internal/dsl/
            // (the property) or internal/ide/v2/ (the converter); the remaining hits are an unrelated
            // isDefault on NDK ABI info. NOTHING resolves variants, substitutes dependencies, or configures
            // tasks from it - cross-module matching is matchingFallbacks, which nightly and stable set
            // below and which this does not touch. Two project mechanisms that read build types are also
            // unaffected because both key on WHAT IS BEING BUILT, not on which variant is default: the
            // updater's stable/nightly gate, and the extension-ABI guard that runs only on R8 variants.
            //
            // ⚠⚠ THE PROJECT'S OWN SESSION NOTES CONTRADICT THIS LINE, AND THIS LINE IS THE
            // CURRENT ONE. Two entries predate it and will read as instructions:
            //   • a June note: "Build variant must be set to debug in Build Variants panel"
            //   • a "Build Variants — which goes where" section describing the selection as a
            //     MANUAL step
            // Both were written while `debug` was the implicit default and the selection was IDE-only
            // state; neither is a deliberate choice of debug over release. Recorded HERE because the
            // notes are outside this repo and may lag indefinitely — so the contradiction has to be
            // resolvable from inside the tree, the same reason every other correction in this
            // codebase goes inline at the wrong claim rather than into a side document.
            // ⚠️ IF YOU CAME HERE FROM ONE OF THOSE NOTES: the manual step is no longer needed,
            // and `release` is the project-declared default. If you WANT debug, select it — that
            // still works and still persists per Studio version; this only changes what a fresh or
            // updated Studio starts on.
            isDefault = true

            // ⚠️ SIGNED WITH THE DEBUG KEYSTORE, DELIBERATELY (2026-09-05). Every APK shipped to GitHub
            // before this date was a debug build, so it carries the debug key's signature. Android refuses
            // an in-place upgrade across a change of signing identity, so signing release with a NEW key
            // would force every existing sideloaded user to UNINSTALL first — wiping settings, the Room
            // database (history, downloads index) and every extension login. Reusing the debug key makes
            // the switch to a minified, guarded build a silent in-place upgrade instead.
            //
            // ⚠️ THE COST, SO IT IS VISIBLE WHEN THE TRADE IS REVISITED: the debug keystore is generated by
            // the SDK with a published password and alias, so ANYONE can build an APK that upgrades over a
            // sideloaded install. That is the STATUS QUO — it has been true of every APK shipped so far —
            // not a weakness introduced here. Adopting a real keystore is a one-way door: it fixes that,
            // and it costs every existing user an uninstall/reinstall and their local data. Do it, if at
            // all, as a deliberate announced migration rather than as a side effect of another change.
            // The Play AAB is unaffected either way — the wizard signs it with the upload key.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                // Load-bearing: without this line the release R8 run never reads proguard-rules.pro,
                // so the extension-ABI keep rule below would be silently dropped. nightly/stable inherit
                // this via initWith(getByName("release")).
                "proguard-rules.pro",
            )
        }
        create("nightly") {
            initWith(getByName("release"))
            applicationIdSuffix = ".nightly"
            matchingFallbacks += "release"
        }
        create("stable") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    androidResources {
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = true
    }

    lint {
        disable.add("MissingTranslation")
        abortOnError = false
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":common"))
    implementation(project(":deezer-extension"))
    implementation(libs.kotlin.reflect)
    implementation(libs.bundles.androidx)
    implementation(libs.material)
    implementation(libs.bundles.paging)
    implementation(libs.filekache)
    implementation(libs.bundles.room)
    implementation(libs.sqlite.async)
    ksp(libs.room.compiler)
    implementation(libs.bundles.koin)
    implementation(libs.androidx.car.app)
    implementation(libs.bundles.media3)
    implementation(libs.bundles.coil)

    implementation(libs.zxing.core)
    implementation(libs.pikolo)
    implementation(libs.fadingedgelayout)
    implementation(libs.fastscroll)
    implementation(libs.nestedscrollwebview)
    implementation(libs.acsbendi.webview)

    testImplementation(libs.junit)

    // Firebase on the COMPILE classpath in every build so direct references in main source resolve
    // even without google-services.json (CI / F-Droid). Mutually exclusive by design: with JSON present
    // it is `implementation` (compile + runtime, packaged); without, it stays `compileOnly` (compile
    // only, never packaged) so Firebase-free builds stay Firebase-free. `implementation` is a superset of
    // `compileOnly` for the compile classpath, so this is identical to declaring both — but avoids putting
    // the same artifact on both configurations (the "declared multiple times" warning).
    if (hasGoogleServices) {
        @Suppress("AvoidDuplicateDependencies")
        implementation(platform(libs.firebase.bom))
        @Suppress("AvoidDuplicateDependencies")
        implementation(libs.bundles.firebase)
    } else {
        @Suppress("AvoidDuplicateDependencies")
        compileOnly(platform(libs.firebase.bom))
        compileOnly(libs.bundles.firebase)
    }
}

// NOTE: apply() is REQUIRED here, not a `plugins {}` entry — these plugins are applied CONDITIONALLY
// (only when google-services.json is present). The declarative plugins {} block cannot be conditional;
// gms/crashlytics are declared there with `apply false` and applied here. Moving them into plugins {}
// unconditionally would run processGoogleServices in every build and hard-fail the Firebase-free
// (no-JSON) path (CI / F-Droid). The "use the plugins DSL" inspection is a false positive for this case.
if (hasGoogleServices) {
    apply(plugin = libs.plugins.gms.get().pluginId)
    apply(plugin = libs.plugins.crashlytics.get().pluginId)
}

fun execute(vararg command: String): String = providers.exec {
    commandLine(*command)
}.standardOutput.asText.get().trim()

// RECURRENCE GUARD for the extension-ABI keep rule (see app/proguard-rules.pro). Fails the release
// build if R8 repackaged/renamed any core :common ABI class — the exact symptom that breaks every
// third-party extension at load. Dumb-and-robust: string-matches a handful of critical classes to
// themselves in mapping.txt; skips gracefully when minify is off (no mapping.txt).
tasks.register("verifyExtensionAbi") {
    description = "Verifies R8 did not repackage/rename the extension ABI (common.** + kept stdlib packages), failing the build if it did."
    group = "verification"
    // Resolve everything from Project at CONFIGURATION time into plain, serializable locals (a File and a
    // List<String>). The doLast action below captures ONLY these + File I/O — no layout/logger/project
    // reference at execution time — so it is compatible with the configuration cache (the bundle build).
    val mappingRoot: File = layout.buildDirectory.dir("outputs/mapping").get().asFile
    // ── INVARIANT: EVERY `-keep class <pkg>.** { *; }` IN proguard-rules.pro NEEDS AN ANCHOR HERE. ──
    // The keep rules are a SWEEP of the whole link-but-don't-bundle ABI surface; this list is what proves
    // the sweep held. An unanchored keep rule is unverified — R8 could repackage that whole package and
    // this task would still print "ABI intact". That is not hypothetical: okio.** and
    // com.google.protobuf.** were kept but UNANCHORED from this task's creation (2026-08-01) until
    // 2026-08-24, while the failure message below already claimed to cover them.
    // Anchors are chosen to be (a) guaranteed present — the blanket keep stops R8 shrinking them, so the
    // only way one leaves mapping.txt is repackaging, which is exactly what we are testing for; (b) stable
    // across library versions — root interfaces/types, never anything marked experimental.
    // ⚠️ Do NOT add anchors one-at-a-time in response to a crash. Add one when you add a keep rule.
    val critical = listOf(
        // our ABI — dev.brahmkshatriya.echo.common.** (rule 1)
        "dev.brahmkshatriya.echo.common.clients.ExtensionClient",
        "dev.brahmkshatriya.echo.common.clients.TrackClient",
        "dev.brahmkshatriya.echo.common.clients.AlbumClient",
        "dev.brahmkshatriya.echo.common.clients.RadioClient",
        "dev.brahmkshatriya.echo.common.models.Track",
        "dev.brahmkshatriya.echo.common.models.EchoMediaItem",
        // kotlin.** (rule 2)
        "kotlin.jvm.functions.Function0",        // function types
        "kotlin.jvm.functions.Function1",
        "kotlin.coroutines.Continuation",        // suspend machinery
        // kotlinx.coroutines.** / kotlinx.serialization.** (rule 3)
        "kotlinx.coroutines.flow.Flow",
        "kotlinx.serialization.KSerializer",
        // okhttp3.** / okio.** / com.google.protobuf.** (rule 4)
        "okhttp3.OkHttpClient",
        // okio: ByteString is core to okio and referenced pervasively by okhttp — it cannot be absent
        // while okhttp is on the classpath. Anchor added 2026-08-24 (rule 4 was previously unverified).
        "okio.ByteString",
        // protobuf: MessageLite is the root interface every generated message implements, unchanged
        // across 2.x→4.x. Deliberately NOT an experimental type (v36.0 removed the experimental
        // FieldOrder enum). Anchor added 2026-08-24 (rule 4 was previously unverified).
        "com.google.protobuf.MessageLite",
        // HealthMonitor report types - dev.brahmkshatriya.echo.utils.HealthMonitor** (rule 5).
        // THE ONLY NON-ABI ANCHORS HERE, and they are in this list rather than a second one
        // because the invariant above is about keep rules, not about the ABI: an unanchored keep
        // rule is unverified whatever it protects. These are kept so Crashlytics can group a
        // breaker trip per FAMILY. Up to build 1119 they were NOT kept, and R8 merged all five
        // ConsecutiveSkip* subclasses into one dex class - only ConsecutiveSkipErrorException had
        // a mapping entry, as "qx1" - so every family reported as one issue and health_report_type
        // read "qx1". One family anchor is enough: all five are kept by a single pattern, so if it
        // lapses they lapse together. The outer class is anchored too because the rule keeps it.
        "dev.brahmkshatriya.echo.utils.HealthMonitor",
        "dev.brahmkshatriya.echo.utils.HealthMonitor\$ConsecutiveSkipUnavailableException",
    )
    doLast {
        val mappingFiles: List<File> = (mappingRoot.listFiles()?.toList().orEmpty())
            .map { dir -> File(dir, "mapping.txt") }
            .filter { it.exists() }
        if (mappingFiles.isEmpty()) {
            println("verifyExtensionAbi: no mapping.txt found (minify off?) — skipping ABI check.")
            return@doLast
        }
        mappingFiles.forEach { file ->
            val variant = file.parentFile?.name ?: "unknown"
            val lines = file.readLines()
            critical.forEach { fqcn ->
                val selfMapped = lines.any { line -> line.startsWith("$fqcn -> $fqcn:") }
                if (!selfMapped) throw GradleException(
                    "Kept class broken: $fqcn was repackaged/renamed by R8 in variant '$variant'. " +
                        "A -keep rule is missing or not applied. If the class is part of the extension " +
                        "ABI (common.** + kotlin.** + kotlinx.coroutines.** + kotlinx.serialization.** + " +
                        "okhttp3.** + okio.** + com.google.protobuf.**), extensions will fail to load " +
                        "with NoClassDefFoundError; if it is a HealthMonitor report type (rule 5, NOT " +
                        "ABI), R8 has merged or renamed the ConsecutiveSkip* families and Crashlytics " +
                        "will group every breaker trip into one issue again. See app/proguard-rules.pro."
                )
            }
            println("verifyExtensionAbi: '$variant' kept classes intact (${critical.size} anchors self-mapped).")
        }
    }
}

// Run the guard whenever R8 runs — release, nightly AND stable. All three are distributed (see the note
// on buildTypes above: Play gets release, sideloads get stable/nightly), so all three can break
// extensions and all three must be verified. A failure in this finalizer fails the build.
tasks.matching { it.name.matches(Regex("^minify.*WithR8$")) }.configureEach {
    finalizedBy("verifyExtensionAbi")
}

// The OTHER half of the extension-ABI guarantee, gated on the same trigger so a shippable build cannot be
// produced without both. They cover disjoint failure modes and neither subsumes the other:
//   verifyExtensionAbi (finalizedBy, above) - POST-compile: did R8 rename/repackage the kept classes?
//   :common:checkKotlinAbi (dependsOn, here) - PRE-compile: did the public ABI of :common itself change?
// dependsOn rather than finalizedBy because this one needs no build output, so failing before R8 runs is
// strictly cheaper. See the abiValidation block in common/build.gradle.kts for the bootstrap step.
tasks.matching { it.name.matches(Regex("^minify.*WithR8$")) }.configureEach {
    dependsOn(":common:checkKotlinAbi")
}

// ── A SHIPPABLE BUILD MUST NOT COMPILE ON TOP OF PREVIOUS KOTLIN OUTPUT ──
// Build 1058 shipped an APK in which StreamableLoader still called App.getFileCache(), a getter that had
// been deleted two commits earlier. Nothing was wrong with the source. Cached.loadMedia/getMedia are
// PUBLIC INLINE, so their bodies are copied into every CALLER's class file; the commit that changed them
// recompiled Cached.kt but not its callers, and those orphaned copies kept a call to a member that no
// longer existed. Every track resolve then died with NoSuchMethodError at runtime.
//
// Neither half of the toolchain can see this. The compiler never re-checks an already-inlined body (the
// caller is simply not recompiled, so nothing looks at it), and R8 packaged the dangling member reference
// silently - there is no -dontwarn suppressing it. Compiling a shippable variant from nothing is the only
// reliable defence, and it is the GENERAL fix: it covers every future edit to any public inline function,
// not just this one.
//
// This gate deliberately does NOT clean for you. A clean task wired into the same build has no ordering
// guarantee against the compile it is meant to precede, so it would be a fix that silently stops working.
// It REFUSES instead - same shape as verifyExtensionAbi above: a cheap check with an actionable message.
//
// Scope is the minified variants only. Debug stays incremental and fast: it is never shipped, and its
// output lives in a different directory, so it cannot leak into a release compilation.
//
// ⚠️ Fail-open if the task-name regex ever stops matching (a product flavor would make the names
// `compile<Flavor><BuildType>Kotlin`). If flavors are ever added, widen the regex or this silently
// protects nothing. The variant list below must likewise track buildTypes {} above.
tasks.register("verifyCleanKotlinOutput") {
    description = "Fails a release/nightly/stable build that would compile on top of existing Kotlin output."
    // ⚠️ THE VARIANT LIST HERE IS ABOUT WHAT SHIPS, AND IT WAS WRONG UNTIL 2026-09-05. An earlier note
    // read "All three are shipped variants ... and debug deliberately not: it is never distributed" —
    // exactly backwards at the time, since debug was the ONLY variant distributed outside Play (see the
    // buildTypes note). stable/nightly are upstream's and have never been built here; they stay in the
    // list because guarding an unbuilt variant costs nothing and would be correct if one were ever built.
    // debug stays OUT: it is now genuinely local-development-only, and gating it would force a clean build
    // on every day-to-day compile. If debug is ever distributed again, add it here — that is the whole
    // exposure this guard covers, and the 1058 stale-output bug does not care which variant it lands in.
    group = "verification"
    // Resolved at CONFIGURATION time into plain serializable locals so the action below captures no
    // Project reference - same configuration-cache constraint as verifyExtensionAbi.
    val kotlinClassesRoot: File = layout.buildDirectory.dir("tmp/kotlin-classes").get().asFile
    val guarded: List<String> = listOf("release", "nightly", "stable")
    // In `gradlew clean bundleRelease` the two are independent roots; without this they could be ordered
    // either way and a genuinely clean build could still trip the check.
    mustRunAfter("clean")
    doLast {
        val dirty = guarded
            .map { variant -> File(kotlinClassesRoot, variant) }
            .filter { dir -> dir.isDirectory && dir.walkTopDown().any { it.extension == "class" } }
        if (dirty.isNotEmpty()) throw GradleException(
            "Refusing to build a shippable variant on top of existing Kotlin output " +
                "(${dirty.joinToString(", ") { it.name }}). Incremental compilation can leave a caller " +
                "holding a STALE copy of a public inline function's body: build 1058 shipped with " +
                "StreamableLoader still calling the deleted App.getFileCache(), and every track resolve " +
                "failed with NoSuchMethodError. Run './gradlew clean' first, then rebuild."
        )
    }
}

tasks.matching { it.name.matches(Regex("^compile(Release|Nightly|Stable)Kotlin$")) }.configureEach {
    dependsOn("verifyCleanKotlinOutput")
}
