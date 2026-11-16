package app.songyan.ime

import android.content.Context
import android.view.View
import app.songyan.ime.ime.LayoutPreviewKeyboard
import app.songyan.ime.settings.ui.KeySelection
import app.songyan.ime.settings.ui.LayoutPreviewFactory
import app.songyan.ime.settings.ui.LayoutPreviewKey
import app.songyan.ime.settings.ui.LayoutPreviewRequest
import app.songyan.ime.settings.ui.LayoutPreviewSurface
import app.songyan.ime.settings.ui.PreviewTarget
import app.songyan.ime.settings.ui.RailSelection

/** The layout editor's preview drawn by the keyboard's own view; settings cannot reach the keyboard itself. */
object KeyboardLayoutPreview : LayoutPreviewFactory {
    override fun create(context: Context): LayoutPreviewSurface = Surface(LayoutPreviewKeyboard(context))

    private class Surface(
        private val keyboard: LayoutPreviewKeyboard,
    ) : LayoutPreviewSurface {
        private var shown: LayoutPreviewRequest? = null
        private var placed: List<LayoutPreviewKey> = emptyList()

        override val view: View get() = keyboard.view

        override val splitWidthDp: Int get() = keyboard.splitWidthDp

        // A listener set after the keys were placed still hears them.
        override var onKeys: (List<LayoutPreviewKey>) -> Unit = {}
            set(value) {
                field = value
                value(placed)
            }

        init {
            keyboard.onKeys = { keys ->
                placed = keys.map { it.toSettings() }
                onKeys(placed)
            }
        }

        override fun show(request: LayoutPreviewRequest) {
            if (request == shown) return
            shown = request
            keyboard.show(request.layout, request.split, request.rows, request.bottom, request.quickSymbols)
        }
    }

    private fun LayoutPreviewKeyboard.Key.toSettings() =
        LayoutPreviewKey(
            target =
                when (val at = target) {
                    is LayoutPreviewKeyboard.Target.Grid -> PreviewTarget.Grid(KeySelection(at.row, at.index))
                    is LayoutPreviewKeyboard.Target.Bottom -> PreviewTarget.Bottom(at.index)
                    is LayoutPreviewKeyboard.Target.Rail -> PreviewTarget.Rail(RailSelection(at.position, at.index))
                },
            label = label,
            cap = cap,
            radius = radius,
        )
}
