package dev.brahmkshatriya.echo.utils.ui.prefs

import android.content.Context
import androidx.preference.Preference
import dev.brahmkshatriya.echo.R

class LoadingPreference(context: Context) : Preference(context) {
    init {
        layoutResource = R.layout.item_loading
        // Not selectable (2026-10-31), so the row is not focusable (Preference.onBindViewHolder sets the row's
        // focusable from isSelectable, androidx.preference 1.2.1). On TV, FragmentUtils.focusFirstRowOnTv landed
        // on this spinner, then lost focus when ExtensionInfoFragment replaced the screen with the real rows.
        // Now it keeps waiting for a real row. It had no click action, so nothing else changes.
        isSelectable = false
    }
}