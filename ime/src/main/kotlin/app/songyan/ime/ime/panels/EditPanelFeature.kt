package app.songyan.ime.ime.panels

import android.content.Context
import app.songyan.ime.ime.EditAction

/** Owns editing-keypad state and forwards actions through the existing editor callbacks. */
internal class EditPanelFeature(
    private val context: Context,
    private val host: PanelFeatureHost,
    private val edit: (EditAction, Boolean) -> Unit,
    private val repeatEdit: (EditAction, Boolean, () -> Boolean) -> Unit,
) {
    private val panel: Lazy<EditPanelView> =
        host.lazyPanel {
            EditPanelView(context).apply {
                onEdit = { action, extend -> edit(action, extend) }
                onEditRepeated = { action, extend, stillHeld -> repeatEdit(action, extend, stillHeld) }
            }
        }

    /** Opens the keypad with Select off. */
    fun open() {
        val view = panel.value
        view.reset()
        host.show(view)
    }

    /** A closed panel starts the next time with Select off. */
    fun reset() {
        panel.initializedValue?.reset()
    }

    /** What the editor and the clipboard allow now; ignored while another panel is showing. */
    fun updateAvailability(value: EditAvailability) {
        val view = panel.initializedValue ?: return
        if (host.isVisible(view)) view.setAvailability(value)
    }

    /** A new selection state from the editor, for Copy and Cut. */
    fun updateSelection(hasSelection: Boolean) {
        val view = panel.initializedValue ?: return
        if (host.isVisible(view)) view.setSelection(hasSelection)
    }
}
