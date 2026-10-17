package dev.brahmkshatriya.echo.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * Cast framework configuration. INSTANTIATED BY REFLECTION from the manifest's
 * `com.google.android.gms.cast.framework.OPTIONS_PROVIDER_CLASS_NAME` meta-data, never by us.
 *
 * ⚠⚠ THAT REFLECTION IS WHY proguard-rules.pro KEEPS THIS CLASS, AND WHY THE KEEP IS
 * ANCHORED IN verifyExtensionAbi. AGP generates keep rules from manifest `android:name` attributes;
 * a meta-data `android:value` is JUST A STRING, so R8 has no reason to believe this class is reachable
 * and would rename or remove it. The failure would be release-only and invisible in debug - the exact
 * shape the 2026-09 ConsecutiveSkip split hit - so it is pinned by a keep rule plus an anchor that
 * fails the build if R8 renames it anyway.
 *
 * DEFAULT MEDIA RECEIVER, deliberately: it needs no Cast developer registration and no custom receiver
 * to host. What it costs is stated here so it is not rediscovered later - it cannot apply custom HTTP
 * headers to a media URL, and its codec support varies by Chromecast generation (FLAC in particular).
 * Both are the reasons the serving layer exists at all, rather than passing extension URLs straight
 * through.
 */
class CastOptionsProvider : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
        .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
