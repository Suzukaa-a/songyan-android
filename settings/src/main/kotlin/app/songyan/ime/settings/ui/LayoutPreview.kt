package app.songyan.ime.settings.ui

import android.content.Context
import android.graphics.RectF
import android.view.View
import androidx.compose.runtime.staticCompositionLocalOf
import app.songyan.ime.layout.LayoutDefinition
import app.songyan.ime.layout.LayoutKey
import app.songyan.ime.layout.LayoutRow

/**
 * Draws a layout being edited with the keyboard's own key view. The settings module does not
 * depend on the keyboard, so the app supplies the drawing through [LocalLayoutPreview]; the editor
 * places the view and puts its selection over the keys the surface reports.
 */
interface LayoutPreviewSurface {
    val view: View

    /** The narrowest width (dp) at which the keyboard draws a layout split. */
    val splitWidthDp: Int

    /** Called with the selectable keys each time they are placed again. */
    var onKeys: (List<LayoutPreviewKey>) -> Unit

    fun show(request: LayoutPreviewRequest)
}

fun interface LayoutPreviewFactory {
    fun create(context: Context): LayoutPreviewSurface
}

/** The keyboard drawing the app provides; null where none is (the editor then shows no keys). */
val LocalLayoutPreview = staticCompositionLocalOf<LayoutPreviewFactory?> { null }

/**
 * What to draw: [layout] whole or split, and the draft's rows and bottom row as the editor indexes
 * them, which the reported keys point into.
 */
data class LayoutPreviewRequest(
    val layout: LayoutDefinition,
    val split: Boolean,
    val rows: List<LayoutRow>,
    val bottom: List<LayoutKey>,
    /** The nine-key's quick symbols the layout inherits when it sets none of its own. */
    val quickSymbols: String = "",
)

/** A key of the draft the preview drew. */
sealed interface PreviewTarget {
    data class Grid(
        val at: KeySelection,
    ) : PreviewTarget

    data class Bottom(
        val index: Int,
    ) : PreviewTarget

    data class Rail(
        val at: RailSelection,
    ) : PreviewTarget
}

/** A drawn key: what it stands for, its face, and its keycap in the view's pixels. */
data class LayoutPreviewKey(
    val target: PreviewTarget,
    val label: String,
    val cap: RectF,
    val radius: Float,
)
