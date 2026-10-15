package dev.brahmkshatriya.echo.ui.settings


import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toResourceImageHolder
import dev.brahmkshatriya.echo.playback.PlayerService.Companion.CACHE_SIZE
import dev.brahmkshatriya.echo.playback.PlayerService.Companion.CLOSE_PLAYER
import dev.brahmkshatriya.echo.playback.CrossfadeController
import dev.brahmkshatriya.echo.playback.PlayerService.Companion.TRUE_CROSSFADE_SECONDS
import dev.brahmkshatriya.echo.playback.PlayerService.Companion.SKIP_FADE_ON_ALBUMS
import dev.brahmkshatriya.echo.playback.PlayerService.Companion.SKIP_SILENCE
import dev.brahmkshatriya.echo.playback.PlayerService.Companion.STREAM_QUALITY
import dev.brahmkshatriya.echo.playback.PlayerService.Companion.UNMETERED_STREAM_QUALITY
import dev.brahmkshatriya.echo.playback.PlayerService.Companion.streamQualities
import dev.brahmkshatriya.echo.playback.listener.PlayerRadio.Companion.AUTO_START_RADIO
import dev.brahmkshatriya.echo.ui.common.FragmentUtils.openFragment
import dev.brahmkshatriya.echo.ui.player.PlayerViewModel.Companion.KEEP_QUEUE
import dev.brahmkshatriya.echo.ui.settings.AudioEffectsFragment.Companion.AUDIO_FX
import dev.brahmkshatriya.echo.utils.ContextUtils.SETTINGS_NAME
import dev.brahmkshatriya.echo.utils.ui.prefs.MaterialListPreference
import dev.brahmkshatriya.echo.utils.ui.prefs.MaterialSliderPreference
import dev.brahmkshatriya.echo.utils.ui.prefs.TransitionPreference

class SettingsPlayerFragment : BaseSettingsFragment() {
    override val title get() = getString(R.string.player)
    override val icon get() = R.drawable.ic_play_circle.toResourceImageHolder()
    override val creator = { AudioPreference() }

    class AudioPreference : PreferenceFragmentCompat() {

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            configure()
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val context = preferenceManager.context
            preferenceManager.sharedPreferencesName = SETTINGS_NAME
            preferenceManager.sharedPreferencesMode = Context.MODE_PRIVATE
            val screen = preferenceManager.createPreferenceScreen(context)
            preferenceScreen = screen

            PreferenceCategory(context).apply {
                title = getString(R.string.playback)
                key = "playback"
                isIconSpaceReserved = false
                layoutResource = R.layout.preference_category
                screen.addPreference(this)

                TransitionPreference(context).apply {
                    key = AUDIO_FX
                    title = getString(R.string.audio_fx)
                    summary = getString(R.string.audio_fx_summary)
                    layoutResource = R.layout.preference
                    isIconSpaceReserved = false
                    addPreference(this)
                }

                MaterialListPreference(context).apply {
                    key = STREAM_QUALITY
                    title = getString(R.string.stream_quality)
                    summary = getString(R.string.stream_quality_summary)
                    entries = context.resources.getStringArray(R.array.stream_qualities)
                    entryValues = streamQualities
                    layoutResource = R.layout.preference
                    isIconSpaceReserved = false
                    // streamQualities[1] == "medium", matching PlayerService.select's `default`
                    // parameter. This row declared [2] ("lowest") while every read used [1], so the
                    // screen showed Lowest on a device that was streaming Medium.
                    setDefaultValue(streamQualities[1])
                    addPreference(this)
                }

                MaterialListPreference(context).apply {
                    key = UNMETERED_STREAM_QUALITY
                    title = getString(R.string.unmetered_stream_quality)
                    summary = getString(R.string.unmetered_stream_quality_summary)
                    entries =
                        context.resources.getStringArray(R.array.stream_qualities) + getString(R.string.unmetered_stream_quality_auto)
                    entryValues = streamQualities + "off"
                    layoutResource = R.layout.preference
                    isIconSpaceReserved = false
                    // "off" is the Auto entry appended to entryValues just above, and matches the
                    // default PlayerService.select reads for this key. It declared "medium" while
                    // every read used "off", so the screen showed Medium on a device using Auto.
                    setDefaultValue("off")
                    addPreference(this)
                }
            }

            PreferenceCategory(context).apply {
                title = getString(R.string.behavior)
                key = "behavior"
                isIconSpaceReserved = false
                layoutResource = R.layout.preference_category
                screen.addPreference(this)

                SwitchPreferenceCompat(context).apply {
                    key = KEEP_QUEUE
                    title = getString(R.string.keep_queue)
                    summary = getString(R.string.keep_queue_summary)
                    layoutResource = R.layout.preference_switch
                    isIconSpaceReserved = false
                    setDefaultValue(true)
                    addPreference(this)
                }

                SwitchPreferenceCompat(context).apply {
                    key = CLOSE_PLAYER
                    title = getString(R.string.stop_player)
                    summary = getString(R.string.stop_player_summary)
                    layoutResource = R.layout.preference_switch
                    isIconSpaceReserved = false
                    setDefaultValue(false)
                    addPreference(this)
                }

                SwitchPreferenceCompat(context).apply {
                    key = SKIP_SILENCE
                    title = getString(R.string.skip_silence)
                    summary = getString(R.string.skip_silence_summary)
                    layoutResource = R.layout.preference_switch
                    isIconSpaceReserved = false
                    setDefaultValue(true)
                    addPreference(this)
                }

                val skipFadeOnAlbums = SwitchPreferenceCompat(context).apply {
                    key = SKIP_FADE_ON_ALBUMS
                    title = getString(R.string.skip_fade_on_albums)
                    summary = getString(R.string.skip_fade_on_albums_summary)
                    layoutResource = R.layout.preference_switch
                    isIconSpaceReserved = false
                    setDefaultValue(true)
                }

                // ⚠⚠ TRUE CROSSFADE. Two Track Fade rows stood above this one until Build 2
                // (2026-10-15) - a crossfade_enabled switch and a 1-5 s crossfade_duration slider -
                // and they are deleted, not migrated. This slider has always had its own key, so
                // nothing here changes meaning; see the note at PlayerService's abandoned constants.
                // 0 is off and is the default.
                MaterialSliderPreference(
                    context,
                    CrossfadeController.FADE_SECONDS_MIN,
                    CrossfadeController.FADE_SECONDS_MAX
                ).apply {
                    key = TRUE_CROSSFADE_SECONDS
                    title = getString(R.string.true_crossfade)
                    summary = getString(
                        R.string.true_crossfade_seconds_summary,
                        CrossfadeController.FADE_SECONDS_MAX
                    )
                    isIconSpaceReserved = false
                    // Mirrors PlayerService.syncCrossfade's getInt default. Both must stay 0.
                    setDefaultValue(CrossfadeController.FADE_SECONDS_MIN)
                    setOnPreferenceChangeListener { _, newValue ->
                        // The album switch belongs to whichever fade is live, so it follows this
                        // slider now rather than the deleted Track Fade switch. > 0 = crossfade on.
                        skipFadeOnAlbums.isVisible = (newValue as? Int ?: 0) > 0
                        true
                    }
                    addPreference(this)
                }

                skipFadeOnAlbums.isVisible =
                    (preferenceManager.sharedPreferences?.getInt(TRUE_CROSSFADE_SECONDS, 0) ?: 0) > 0
                addPreference(skipFadeOnAlbums)

                SwitchPreferenceCompat(context).apply {
                    key = AUTO_START_RADIO
                    title = getString(R.string.auto_start_radio)
                    summary = getString(R.string.auto_start_radio_summary)
                    layoutResource = R.layout.preference_switch
                    isIconSpaceReserved = false
                    setDefaultValue(true)
                    addPreference(this)
                }

                MaterialSliderPreference(context, 200, 1000, allowOverride = true).apply {
                    key = CACHE_SIZE
                    title = getString(R.string.cache_size)
                    summary = getString(R.string.cache_size_summary)
                    isIconSpaceReserved = false
                    setDefaultValue(250)
                    addPreference(this)
                }
            }
        }

        override fun onPreferenceTreeClick(preference: Preference): Boolean {
            val view = listView.findViewById<View>(preference.key.hashCode())
            return when (preference.key) {
                AUDIO_FX -> {
                    requireActivity().openFragment<AudioEffectsFragment>(view)
                    true
                }

                else -> false
            }
        }
    }
}
