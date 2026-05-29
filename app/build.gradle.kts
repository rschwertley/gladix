plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlinx.serialization)

    alias(libs.plugins.gms) apply false
    alias(libs.plugins.crashlytics) apply false
}

val hasGoogleServices = file("google-services.json").exists()
val gitHash = runCatching { execute("git", "rev-parse", "HEAD").take(7) }.getOrDefault("dev")
val gitCount = runCatching { execute("git", "rev-list", "--count", "HEAD").toInt() }.getOrDefault(1)
val isDirty = runCatching { execute("git", "status", "--porcelain", "-uno").isNotEmpty() }.getOrDefault(false)
val version = "3.0.$gitCount"

android {
    namespace = "dev.brahmkshatriya.echo"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.rschwertley.gladix.auto"
        minSdk = 24
        targetSdk = 36
        // Note: versionCode only increments on git commits. 
        // For local development, consider committing frequently to update the version.
        versionCode = gitCount
        versionName = "v${version}_$gitHash${if (isDirty) "-dirty" else ""}($gitCount)"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
            )
        }
        create("nightly") {
            initWith(getByName("release"))
            applicationIdSuffix = ".nightly"
            resValue("string", "app_name", "Echo Nightly")
        }
        create("stable") {
            initWith(getByName("release"))
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

    if (!hasGoogleServices) return@dependencies
    implementation(platform(libs.firebase.bom))
    implementation(libs.bundles.firebase)
}

if (hasGoogleServices) {
    apply(plugin = libs.plugins.gms.get().pluginId)
    apply(plugin = libs.plugins.crashlytics.get().pluginId)
}

fun execute(vararg command: String): String = providers.exec {
    commandLine(*command)
}.standardOutput.asText.get().trim()
