package app.songyan.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupGeometryTest {
    private val cell = 48f
    private val cellHeight = 52f
    private val gap = 6f
    private val layerWidth = 400f

    private fun key(
        left: Float,
        top: Float,
        width: Float = 36f,
    ) = KeyBounds(left, top, left + width, top + 50f)

    @Test
    fun theFirstAlternateSitsRightAboveTheKey() {
        val anchor = key(left = 100f, top = 200f)
        val popup = PopupGeometry.alternates(anchor, 3, cell, cellHeight, layerWidth, 0f, gap)
        assertFalse(popup.mirrored)
        val keyCenter = (anchor.left + anchor.right) / 2
        val firstCenter = popup.bounds.left + cell / 2
        assertEquals(keyCenter, firstCenter, 0.01f)
        val lift = Math.round((anchor.bottom - anchor.top) * PopupGeometry.MAXIMUM_LIFT).toFloat()
        assertEquals(anchor.top - gap - lift, popup.bounds.bottom, 0.01f)
        assertEquals(cellHeight, popup.bounds.bottom - popup.bounds.top, 0.01f)
    }

    @Test
    fun aKeyAtTheRightEdgeMirrorsSoTheFirstAlternateStaysAboveIt() {
        val anchor = key(left = 360f, top = 200f)
        val popup = PopupGeometry.alternates(anchor, 4, cell, cellHeight, layerWidth, 0f, gap)
        assertTrue(popup.mirrored)
        assertTrue(popup.bounds.right <= layerWidth)
        val keyCenter = (anchor.left + anchor.right) / 2
        val lastCenter = popup.bounds.right - cell / 2
        assertEquals(keyCenter, lastCenter, 12f)
        assertEquals(0, popup.optionAt(3, 4))
        assertEquals(3, popup.optionAt(0, 4))
        assertEquals(3, popup.cellOf(0, 4))
    }

    @Test
    fun aKeyAtTheLeftEdgeStaysInsideTheLayer() {
        val popup = PopupGeometry.alternates(key(left = 0f, top = 200f), 4, cell, cellHeight, layerWidth, 0f, gap)
        assertEquals(0f, popup.bounds.left, 0.01f)
        assertFalse(popup.mirrored)
    }

    @Test
    fun theFirstRowPopupRisesIntoTheBarInsteadOfBeingClipped() {
        // Key area top at 72 in layer coordinates (the candidate bar above it): the popup still fits.
        val popup = PopupGeometry.alternates(key(left = 100f, top = 72f), 3, cell, cellHeight, layerWidth, 0f, gap)
        assertTrue(popup.bounds.top >= 0f)
        assertEquals(cellHeight, popup.bounds.bottom - popup.bounds.top, 0.01f)
    }

    @Test
    fun aPopupAtTheTopOfTheLayerShrinksButKeepsATouchableHeight() {
        val popup = PopupGeometry.alternates(key(left = 100f, top = 40f), 3, cell, cellHeight, layerWidth, 0f, gap)
        assertEquals(0f, popup.bounds.top, 0.01f)
        assertTrue(popup.bounds.bottom - popup.bounds.top >= 34f)
    }

    @Test
    fun manyAlternatesShrinkToTheLayerWidth() {
        val popup = PopupGeometry.alternates(key(left = 100f, top = 200f), 12, cell, cellHeight, layerWidth, 0f, gap)
        assertEquals(layerWidth, popup.bounds.right - popup.bounds.left, 0.01f)
        assertEquals(0f, popup.bounds.left, 0.01f)
    }

    @Test
    fun thePreviewIsCentredAboveTheKeyAndStaysInsideTheLayer() {
        val anchor = key(left = 100f, top = 200f)
        val preview = PopupGeometry.preview(anchor, 56f, 52f, layerWidth, 0f, 3f)
        assertEquals((anchor.left + anchor.right) / 2, (preview.left + preview.right) / 2, 0.01f)
        val lift = Math.round((anchor.bottom - anchor.top) * PopupGeometry.MAXIMUM_LIFT).toFloat()
        assertEquals(anchor.top - 3f - lift, preview.bottom, 0.01f)
        val edge = PopupGeometry.preview(key(left = 390f, top = 200f), 56f, 52f, layerWidth, 0f, 3f)
        assertTrue(edge.right <= layerWidth)
    }

    @Test
    fun thePreviewRisesClearOfTheFingerOnlyAsFarAsTheRoomAllows() {
        val anchor = key(left = 100f, top = 200f)
        val keyHeight = anchor.bottom - anchor.top
        val free = PopupGeometry.preview(anchor, 56f, 52f, layerWidth, 0f, 3f)
        assertTrue(anchor.top - free.bottom > 3f + keyHeight / 3)
        // A top-row key with only the bubble's own height above it gets no lift and keeps its full height.
        val tight = PopupGeometry.preview(key(left = 100f, top = 55f), 56f, 52f, layerWidth, 0f, 3f)
        assertEquals(52f, tight.bottom, 0.01f)
        assertEquals(0f, tight.top, 0.01f)
    }

    @Test
    fun theAlternatesRiseLikeThePreviewTheyExpandFrom() {
        val anchor = key(left = 100f, top = 200f)
        val preview = PopupGeometry.preview(anchor, 56f, cellHeight, layerWidth, 0f, gap)
        val popup = PopupGeometry.alternates(anchor, 3, cell, cellHeight, layerWidth, 0f, gap)
        assertEquals(preview.bottom, popup.bounds.bottom, 0.01f)
    }
}
