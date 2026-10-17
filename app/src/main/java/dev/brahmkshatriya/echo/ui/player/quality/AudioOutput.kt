package dev.brahmkshatriya.echo.ui.player.quality

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import dev.brahmkshatriya.echo.R

/**
 * Current audio output for the quality sheet's output row, plus the tap that hands off to system
 * settings. READ-ONLY with respect to playback: nothing here touches the player, the session, or
 * any audio focus or volume state. The volume half of that row is media3's `deviceVolume`, wired in
 * PlayerViewModel - see the note there for why that is not `player.volume`.
 */
object AudioOutput {

    /**
     * [name] is the device's own product name ("Pixel Buds Pro"), null for the built-in speaker -
     * where the product name is the PHONE model and reads as a mistake, so the row falls back to
     * [label] there. [label] is always populated and is the only guaranteed text.
     */
    data class Output(
        @StringRes val label: Int,
        @DrawableRes val icon: Int,
        val name: String?,
    )

    fun current(context: Context): Output {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return Output(R.string.audio_output, R.drawable.ic_speaker_24dp, null)
        val type = activeDevice(audioManager)?.let { it.type to it.productName?.toString() }
        return Output(
            label = labelFor(type?.first),
            icon = iconFor(type?.first),
            name = if (type == null || isBuiltin(type.first)) null else type.second,
        )
    }

    /**
     * ⚠⚠ EXACT ON API 31+, A HEURISTIC BELOW IT, AND THE SPLIT IS NOT A STYLE CHOICE.
     * getAudioDevicesForAttributes (API 31) returns the device that WOULD BE USED for the given
     * attributes, which is the actual question. There is no public equivalent on 29-30:
     * getDevices(GET_DEVICES_OUTPUTS) lists what is AVAILABLE, and the built-in speaker is always in
     * that list (see PlayerService.hasExternalAudioOutput, whose comment says the same thing for its
     * own boolean). So below API 31 this walks [PRECEDENCE] and takes the highest-ranked connected
     * type, which matches Android's routing most of the time and WILL occasionally be wrong - the
     * real rule involves recency of connection, which is not observable from here.
     * ⚠️ IF THIS LABEL IS EVER REPORTED AS WRONG, CHECK THE API LEVEL FIRST. On 31+ a wrong
     * label is a real defect; on 29-30 it is this heuristic and the fix is not more precedence
     * entries, it is accepting a generic label on those versions.
     */
    private fun activeDevice(audioManager: AudioManager): AudioDeviceInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            runCatching { audioManager.getAudioDevicesForAttributes(attributes).firstOrNull() }
                .getOrNull()
        } else {
            val outputs = runCatching { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }
                .getOrDefault(emptyArray())
            PRECEDENCE.firstNotNullOfOrNull { type -> outputs.firstOrNull { it.type == type } }
        }

    // Highest-priority route first. Built-in speaker is deliberately ABSENT: it is always present in
    // the device list, so including it would make the walk always succeed at the last entry and the
    // null return below would never happen - the null is what means "nothing external, assume
    // speaker", which labelFor already handles.
    private val PRECEDENCE = listOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_DOCK,
    )

    // TYPE_BUILTIN_SPEAKER_SAFE is API 30, so it is matched through a guarded helper rather than as a
    // `when` branch - the constant would inline and lint-warn, and on 29 the device never reports it.
    private fun isBuiltinSpeakerSafe(type: Int) =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE

    private fun isBuiltin(type: Int) = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> true
        else -> isBuiltinSpeakerSafe(type)
    }

    @StringRes
    private fun labelFor(type: Int?): Int = when (type) {
        null -> R.string.phone_speaker
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO ->
            R.string.bluetooth_audio
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES ->
            R.string.wired_headphones
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> R.string.usb_audio
        else -> if (isBuiltin(type)) R.string.phone_speaker else R.string.audio_output
    }

    @DrawableRes
    private fun iconFor(type: Int?): Int = when (type) {
        null -> R.drawable.ic_speaker_24dp
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO ->
            R.drawable.ic_bluetooth_24dp
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES ->
            R.drawable.ic_headset_24dp
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> R.drawable.ic_usb_24dp
        else -> R.drawable.ic_speaker_24dp
    }

    /**
     * Route-change notifications for as long as a caller wants them. Returns false if the
     * AudioManager is unavailable, in which case the caller simply stays on its last reading -
     * there is nothing here worth failing a screen for.
     *
     * ⚠⚠ THE MAIN LOOPER IS PASSED DELIBERATELY, SO CALLBACKS CAN TOUCH VIEWS.
     * registerAudioDeviceCallback's handler decides the callback thread; without one it uses the
     * thread's own Looper, which is a trap waiting for the first caller that registers off the
     * main thread.
     * ⚠️ REGISTRATION ITSELF FIRES onAudioDevicesAdded WITH THE CURRENT DEVICE LIST.
     * That is documented AudioManager behaviour, so a caller gets an immediate callback it did
     * not cause - which is harmless here (it re-resolves to the same output) but means the
     * callback must be idempotent, and that a caller cannot use "was I called?" to mean "did
     * something change?".
     */
    fun registerDeviceCallback(context: Context, callback: AudioDeviceCallback): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return false
        return runCatching {
            audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
            true
        }.getOrDefault(false)
    }

    fun unregisterDeviceCallback(context: Context, callback: AudioDeviceCallback) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return
        runCatching { audioManager.unregisterAudioDeviceCallback(callback) }
    }

    // API 30+ in AOSP and NOT IN THE SDK - javap on android-37's android.jar shows Settings$Panel
    // carrying only ACTION_INTERNET_CONNECTIVITY / NFC / VOLUME / WIFI, and Settings itself has no
    // ACTION_CONNECTED_DEVICE_SETTINGS at all. So this is a raw string by necessity, first in the
    // chain because it is the actual output switcher, and backed by two public actions that cannot
    // disappear.
    private const val MEDIA_OUTPUT_PANEL = "android.settings.panel.action.MEDIA_OUTPUT"

    private val SETTINGS_CHAIN = listOf(
        MEDIA_OUTPUT_PANEL,
        Settings.ACTION_BLUETOOTH_SETTINGS,
        Settings.ACTION_SOUND_SETTINGS,
    )

    /**
     * ⚠⚠ THE CHAIN IS DRIVEN BY THE ATTEMPT, NOT BY resolveActivity, AND THAT IS DELIBERATE
     * RATHER THAN A SHORTCUT. From API 30 PackageManager.resolveActivity is subject to package
     * visibility filtering, so it can return null for an intent that would in fact launch - which
     * would make the chain SKIP the output-switcher panel on exactly the versions that have it. A
     * failed startActivity throws ActivityNotFoundException and costs nothing, so trying is both
     * cheaper and strictly more accurate than asking. Flip this to a resolve gate only if a device is
     * found where the attempt has a visible cost.
     *
     * Returns false only if ALL THREE fail, which needs a device with no Settings activity for sound
     * - near-impossible, and if it happens the tap is simply inert rather than crashing.
     */
    fun openSystemOutputSettings(context: Context): Boolean {
        for (action in SETTINGS_CHAIN) {
            val launched = runCatching {
                context.startActivity(Intent(action))
                true
            }.getOrDefault(false)
            if (launched) return true
        }
        return false
    }
}
