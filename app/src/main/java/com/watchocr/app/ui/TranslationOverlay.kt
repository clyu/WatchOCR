package com.watchocr.app.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.watchocr.app.LOG_TAG
import com.watchocr.app.data.AnalysisItem
import com.watchocr.app.data.AppSettings
import com.watchocr.app.ui.theme.WatchOcrTheme

/**
 * One result as [TranslationOverlay] shows it. A plain class rather than a data
 * class on purpose: [OverlayContent] keys its scroll position on the instance,
 * and two results that happen to read the same are still two arrivals, each of
 * which should open at the top.
 */
private class OverlayResult(val translation: String, val analysis: List<AnalysisItem>)

/**
 * The newest result from the watched folder, shown over whatever app is in
 * front so it can be read against the original without switching to WatchOCR:
 * the translation and the idiom/slang analysis, but not the extracted text, which
 * is what the user is already looking at.
 *
 * A TYPE_APPLICATION_OVERLAY window, which needs the "display over other apps"
 * permission; without it [show] does nothing. Owned by
 * [com.watchocr.app.service.DirectoryMonitorService], whose lifetime bounds the
 * window's.
 *
 * A tap on the panel takes the window down until the next [show]. The button in
 * its corner shrinks it to just that button and back. A new result always
 * arrives expanded, whatever state the last one was left in: the user took a
 * screenshot because they want to read this one.
 *
 * Main thread only, like every window operation it makes.
 */
class TranslationOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(WindowManager::class.java)

    /**
     * Anchored top-end, where the corner button is: the window shrinks towards
     * it when minimized, so the button stays exactly where it was tapped.
     * [updateMinimized] has why the width changes with the state.
     *
     * Never animated. With no windowAnimations the window appears and goes
     * on the spot, and with no move animation — see [disableMoveAnimation] —
     * minimizing and restoring are just as immediate.
     */
    private val layoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // Never takes focus: the keyboard, the back key and every touch outside
        // the window stay with the app underneath.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.END
        disableMoveAnimation()
    }

    private var result by mutableStateOf<OverlayResult?>(null)
    private var minimized by mutableStateOf(false)

    /**
     * The panel's height cap as a percentage of the screen's; content beyond it
     * scrolls. Applied live: changed while the overlay is up, it resizes on the
     * spot.
     */
    var maxHeightPercent by mutableStateOf(AppSettings.DEFAULT_OVERLAY_MAX_HEIGHT_PERCENT)

    /** The window currently added, or null while nothing is showing. */
    private var view: ComposeView? = null

    fun show(translation: String, analysis: List<AnalysisItem>) {
        result = OverlayResult(translation, analysis)
        updateMinimized(false)
        if (view != null) return
        // Checked rather than left to addView, which refuses with an exception:
        // having no permission is an ordinary state, not a failure — system
        // settings can revoke it at any time.
        if (!Settings.canDrawOverlays(context)) {
            Log.i(LOG_TAG, "not allowed to display over other apps, overlay not shown")
            return
        }
        val owner = OverlayLifecycleOwner()
        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            // On detach rather than in hide(): a tap on the panel calls hide()
            // from inside its own dispatch, and destroying the lifecycle there
            // would dispose the composition in the middle of handling that tap.
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) = owner.destroy()
            })
            setContent {
                // The app's own theme, light or dark as the system is, so the
                // card matches the ones in History.
                WatchOcrTheme {
                    result?.let { current ->
                        OverlayContent(
                            result = current,
                            minimized = minimized,
                            maxHeightPercent = maxHeightPercent,
                            onDismiss = ::hide,
                            onToggleMinimized = { updateMinimized(!minimized) }
                        )
                    }
                }
            }
        }
        try {
            windowManager.addView(composeView, layoutParams)
        } catch (e: RuntimeException) {
            // Never attached, so the listener above will never get to do this.
            owner.destroy()
            throw e
        }
        view = composeView
    }

    /**
     * Takes the window down, if one is up.
     *
     * removeView rather than removeViewImmediate: a tap on the panel calls this
     * from inside the window's own touch dispatch, which must not have its view
     * detached out from under it. The removal is posted instead, and the
     * window's lifecycle ends once it lands.
     */
    fun hide() {
        val shown = view ?: return
        view = null
        windowManager.removeView(shown)
    }

    /**
     * The window is MATCH_PARENT wide while expanded and WRAP_CONTENT while
     * minimized, rather than one of the two throughout. Minimized, a full-width
     * window would go on blocking touches across the top of the app underneath
     * with nothing visible to show for it. Expanded, WRAP_CONTENT would not fill
     * the width: ViewRootImpl first measures such a window at the preferred
     * dialog width — 320dp on a phone — and keeps that whenever the content
     * fits, which a fillMaxWidth panel always does.
     *
     * Not named setMinimized: the delegated [minimized] property already
     * compiles to a private setter with that name and JVM signature.
     */
    private fun updateMinimized(minimized: Boolean) {
        if (minimized == this.minimized) return
        this.minimized = minimized
        layoutParams.width = if (minimized) {
            WindowManager.LayoutParams.WRAP_CONTENT
        } else {
            WindowManager.LayoutParams.MATCH_PARENT
        }
        view?.let { windowManager.updateViewLayout(it, layoutParams) }
    }
}

/**
 * The platform's PRIVATE_FLAG_NO_MOVE_ANIMATION, which the SDK hides: the bit
 * Android 14's public setCanPlayMoveAnimation(false) sets. Its value has not
 * changed since before this app's minSdk.
 */
private const val PRIVATE_FLAG_NO_MOVE_ANIMATION = 0x40

/**
 * Has the window jump to a new position rather than slide there. The system
 * animates any window whose position changes, and the overlay's changes on
 * every minimize and restore: anchored top-end, its left edge moves with its
 * width.
 *
 * Android 14 has a public setter for this. Before it, the flag behind that
 * setter already existed, but only in a field the SDK hides, so it is set
 * there by reflection. Quietly: a platform that refuses costs a slide, where
 * an exception escaping from here would stop monitoring altogether — the
 * service builds the overlay from inside its own reconcile.
 */
private fun WindowManager.LayoutParams.disableMoveAnimation() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        setCanPlayMoveAnimation(false)
        return
    }
    try {
        val field = WindowManager.LayoutParams::class.java.getField("privateFlags")
        field.setInt(this, field.getInt(this) or PRIVATE_FLAG_NO_MOVE_ANIMATION)
    } catch (e: Exception) {
        Log.w(LOG_TAG, "could not turn off the overlay's move animation", e)
    }
}

/**
 * What a ComposeView needs from its window that an activity would otherwise
 * supply: a lifecycle to run its recomposer by, and a saved-state registry for
 * rememberSaveable. A window a service adds straight to the WindowManager comes
 * with neither, and ComposeView refuses to attach without them.
 *
 * One per window: a destroyed lifecycle cannot be started again.
 */
private class OverlayLifecycleOwner : SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    init {
        // Restored from nothing, since the window never outlives the process
        // to have saved anything — but restored all the same: the registry
        // refuses reads until it has been, and allows it only while the
        // lifecycle is still INITIALIZED.
        savedStateRegistryController.performRestore(null)
        // All the way up, not just to CREATED: the window recomposer keeps its
        // frame clock paused below STARTED, and nothing after the first
        // composition would ever recompose — not a new result, not the
        // minimize button, not a height change.
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }
}

/**
 * Both of the overlay's states, drawn the way History draws a record: in the
 * same Card, for the same colors and corners in either system theme, and with
 * the same gap from the top and sides that a card keeps from the list's edges.
 *
 * The two states also put the corner button at the same offset from the
 * window's top-end corner, which the window is anchored at — that is what
 * keeps the button from moving between them.
 */
@Composable
private fun OverlayContent(
    result: OverlayResult,
    minimized: Boolean,
    maxHeightPercent: Int,
    onDismiss: () -> Unit,
    onToggleMinimized: () -> Unit
) {
    // Above the branch, so that minimizing and restoring keeps the reading
    // position; keyed on the result, so that a new one opens at the top.
    val scrollState = remember(result) { ScrollState(0) }
    // No gap below: there it would only be an invisible strip of window,
    // blocking touches to the app underneath.
    Box(Modifier.padding(start = RecordCardMargin, top = RecordCardMargin, end = RecordCardMargin)) {
        if (minimized) {
            Card(shape = CircleShape) {
                IconButton(onClick = onToggleMinimized) {
                    Icon(Icons.Default.OpenInFull, contentDescription = "Restore")
                }
            }
        } else {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    // Clipped first, so the ripple keeps to the card's rounded corners.
                    .clip(CardDefaults.shape)
                    // A tap anywhere dismisses. A drag scrolls instead, and the
                    // corner button keeps its own taps.
                    .clickable(onClickLabel = "Dismiss", onClick = onDismiss)
            ) {
                // fillMaxWidth because a Card lays its content out in a Column,
                // which would otherwise shrink this Box to the text's width and
                // pull the corner button in from the card's edge along with it.
                Box(Modifier.fillMaxWidth()) {
                    val maxHeight = (LocalConfiguration.current.screenHeightDp * maxHeightPercent / 100f).dp
                    Column(
                        Modifier
                            .heightIn(max = maxHeight)
                            .verticalScroll(scrollState)
                            // The end padding keeps the text clear of the corner button.
                            .padding(start = 16.dp, top = 12.dp, end = 48.dp, bottom = 12.dp)
                    ) {
                        val hasTranslation = result.translation.isNotBlank()
                        val hasAnalysis = result.analysis.isNotEmpty()
                        when {
                            hasTranslation -> Text(result.translation, style = MaterialTheme.typography.bodyLarge)
                            // An empty panel would read as the overlay misbehaving
                            // rather than as an image with no text in it.
                            !hasAnalysis -> Text("No text found.", style = MaterialTheme.typography.bodyLarge)
                        }
                        if (hasAnalysis) {
                            if (hasTranslation) {
                                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            }
                            AnalysisLines(result.analysis)
                        }
                    }
                    IconButton(onClick = onToggleMinimized, modifier = Modifier.align(Alignment.TopEnd)) {
                        Icon(Icons.Default.CloseFullscreen, contentDescription = "Minimize")
                    }
                }
            }
        }
    }
}
