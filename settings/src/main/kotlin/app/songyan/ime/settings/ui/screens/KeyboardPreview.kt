package app.songyan.ime.settings.ui.screens

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.viewinterop.AndroidView
import app.songyan.ime.settings.R
import app.songyan.ime.settings.ui.LayoutPreviewKey
import app.songyan.ime.settings.ui.LayoutPreviewRequest
import app.songyan.ime.settings.ui.LocalLayoutPreview
import app.songyan.ime.settings.ui.PreviewTarget
import kotlin.math.roundToInt

/**
 * The layout as the keyboard draws it, by the keyboard's own view: the same rows, added keys,
 * bottom row, appearance and proportions. Tapping a key selects it for editing; the selected key
 * is outlined. A split keyboard is drawn at a width where the keyboard splits; on a narrower
 * screen it scrolls sideways under a navigation bar.
 */
@Composable
fun KeyboardPreview(
    request: LayoutPreviewRequest,
    selected: PreviewTarget?,
    onSelect: (PreviewTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val factory = LocalLayoutPreview.current ?: return
    val context = LocalContext.current
    val surface = remember(factory, context) { factory.create(context) }
    var keys by remember(surface) { mutableStateOf(emptyList<LayoutPreviewKey>()) }
    DisposableEffect(surface) {
        surface.onKeys = { keys = it }
        onDispose { surface.onKeys = {} }
    }
    val scrollState = rememberScrollState()
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val width = if (request.split) max(maxWidth, surface.splitWidthDp.dp) else maxWidth
        val scrolls = width > maxWidth
        Column {
            Box(modifier = if (scrolls) Modifier.horizontalScroll(scrollState) else Modifier) {
                Box(modifier = Modifier.width(width).testTag(PREVIEW_TAG)) {
                    AndroidView(
                        factory = { surface.view },
                        update = { surface.show(request) },
                        // The key view paints its whole canvas; without a clip it would cover the editor.
                        modifier = Modifier.width(width).clipToBounds(),
                    )
                    for (key in keys) {
                        KeyTarget(key, request.rows.size, key.target == selected, onSelect)
                    }
                }
            }
            if (scrolls) SplitScrollBar(scrollState, Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
        }
    }
}

/** The test tag of the preview, whose bounds are the keyboard view's. */
const val PREVIEW_TAG = "layout_preview"

/** A selectable area over one drawn keycap, outlined while selected. */
@Composable
private fun KeyTarget(
    key: LayoutPreviewKey,
    rowCount: Int,
    selected: Boolean,
    onSelect: (PreviewTarget) -> Unit,
) {
    val density = LocalDensity.current
    val cap = key.cap
    val description =
        when (val target = key.target) {
            is PreviewTarget.Grid ->
                stringResource(R.string.layout_editor_key_description, target.at.row + 1, target.at.key + 1, key.label)
            is PreviewTarget.Bottom ->
                stringResource(R.string.layout_editor_key_description, rowCount + 1, target.index + 1, key.label)
            // The side columns are listed below the preview, where they are spoken and selected.
            is PreviewTarget.Rail -> null
        }
    val shape = RoundedCornerShape(with(density) { key.radius.toDp() })
    val outline = MaterialTheme.colorScheme.primary
    Box(
        modifier =
            Modifier
                .offset { IntOffset(cap.left.roundToInt(), cap.top.roundToInt()) }
                .size(with(density) { cap.width().toDp() }, with(density) { cap.height().toDp() })
                .then(
                    if (description != null) {
                        Modifier.semantics { contentDescription = description }
                    } else {
                        Modifier.clearAndSetSemantics {}
                    },
                ).then(
                    if (selected) {
                        Modifier
                            .background(outline.copy(alpha = SELECTED_TINT), shape)
                            .border(SELECTED_OUTLINE, outline, shape)
                    } else {
                        Modifier
                    },
                ).clip(shape)
                .selectable(selected = selected, onClick = { onSelect(key.target) }),
    )
}

private val SELECTED_OUTLINE = 3.dp
private const val SELECTED_TINT = 0.16f

/**
 * A track under a sideways-scrolling preview: the thumb is the part of the keyboard in view, and
 * dragging it moves the view.
 */
@Composable
private fun SplitScrollBar(
    state: ScrollState,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val label = stringResource(R.string.layout_editor_preview_scroll)
    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxWidth()
                .height(SCROLL_BAR_HEIGHT)
                .semantics { contentDescription = label },
    ) {
        val track = with(density) { maxWidth.toPx() }
        val total = state.maxValue + track
        val thumb = (track * track / total).coerceAtLeast(with(density) { MIN_THUMB.toPx() })
        val travel = (track - thumb).coerceAtLeast(1f)
        val fraction = if (state.maxValue == 0) 0f else state.value.toFloat() / state.maxValue
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .pointerInput(state, travel) {
                        detectHorizontalDragGestures { change, delta ->
                            change.consume()
                            state.dispatchRawDelta(delta * state.maxValue / travel)
                        }
                    },
        ) {
            Box(
                modifier =
                    Modifier
                        .offset { IntOffset((travel * fraction).roundToInt(), 0) }
                        .width(with(density) { thumb.toDp() })
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

private val SCROLL_BAR_HEIGHT = 10.dp
private val MIN_THUMB = 32.dp
