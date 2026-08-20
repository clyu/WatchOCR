package com.watchocr.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/**
 * The settings screen's in-progress text-field edits, held outside the
 * composition so they survive everything that disposes it — a tab switch, a
 * configuration change — and are never re-read from DataStore.
 *
 * That re-read is the whole point. Kept in composition state, the fields have
 * to be seeded from [com.watchocr.app.data.AppSettings] every time the screen
 * is composed, and that seeding races the debounced write the departing screen
 * launched on its way out: DataStore has not caught up yet, so the field comes
 * back showing the value from *before* the edit. It never corrects itself —
 * nothing re-seeds it a second time — and the damage is not only cosmetic: by
 * the time the screen is left again the stored value it is compared against
 * has caught up, the two differ, and the flush writes that stale value back
 * over the newer one. The edit is then gone for good.
 *
 * Not rememberSaveable either, for the reason it was already avoided: the API
 * key must not land in the saved instance state Bundle in plain text. A
 * ViewModel's state is never written there, so both fields can live here.
 *
 * Null means "not edited on this screen yet"; the field falls back to the
 * stored value until the user's first keystroke.
 */
class SettingsDraftViewModel : ViewModel() {
    var apiKey by mutableStateOf<String?>(null)
    var model by mutableStateOf<String?>(null)
}
