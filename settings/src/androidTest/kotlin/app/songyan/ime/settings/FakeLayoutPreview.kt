package app.songyan.ime.settings

import android.content.Context
import android.graphics.RectF
import android.view.View
import app.songyan.ime.layout.LayoutAuxiliaryArea
import app.songyan.ime.layout.PhoneKeypadRailPolicy
import app.songyan.ime.settings.ui.KeySelection
import app.songyan.ime.settings.ui.LayoutPreviewFactory
import app.songyan.ime.settings.ui.LayoutPreviewKey
import app.songyan.ime.settings.ui.LayoutPreviewRequest
import app.songyan.ime.settings.ui.LayoutPreviewSurface
import app.songyan.ime.settings.ui.PreviewTarget
import app.songyan.ime.settings.ui.RailSelection

/**
 * Stands in for the keyboard's drawing, which this module cannot reach: every key of the request
 * on a plain grid, so the editor's selection and fields can be driven. The real drawing is tested
 * in the app module.
 */
object FakeLayoutPreview : LayoutPreviewFactory {
    private const val CELL = 60f

    override fun create(context: Context): LayoutPreviewSurface =
        object : LayoutPreviewSurface {
            private var keys: List<LayoutPreviewKey> = emptyList()
            override val view: View =
                object : View(context) {
                    override fun onMeasure(
                        widthMeasureSpec: Int,
                        heightMeasureSpec: Int,
                    ) = setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (CELL * ROWS).toInt())
                }
            override val splitWidthDp: Int = 680
            override var onKeys: (List<LayoutPreviewKey>) -> Unit = {}
                set(value) {
                    field = value
                    value(keys)
                }

            override fun show(request: LayoutPreviewRequest) {
                val placed = mutableListOf<LayoutPreviewKey>()

                fun place(
                    target: PreviewTarget,
                    label: String,
                    row: Int,
                    column: Int,
                ) {
                    val left = column * CELL
                    val top = row * CELL
                    placed += LayoutPreviewKey(target, label, RectF(left, top, left + CELL - 4, top + CELL - 4), 8f)
                }
                request.rows.forEachIndexed { row, keys ->
                    keys.keys.forEachIndexed {
                        index,
                        key,
                        ->
                        place(PreviewTarget.Grid(KeySelection(row, index)), key.label, row, index)
                    }
                }
                request.bottom.forEachIndexed { index, key ->
                    place(PreviewTarget.Bottom(index), key.label, request.rows.size, index)
                }
                if (!PhoneKeypadRailPolicy.usesBundledRails(request.layout)) {
                    request.layout.auxiliaryAreas.orEmpty().forEach { area ->
                        area.keys.forEachIndexed { index, key ->
                            val column = if (area.position == LayoutAuxiliaryArea.Position.LEADING) 12 else 13
                            place(PreviewTarget.Rail(RailSelection(area.position, index)), key.label, index, column)
                        }
                    }
                }
                keys = placed
                onKeys(keys)
            }
        }

    private const val ROWS = 5
}
