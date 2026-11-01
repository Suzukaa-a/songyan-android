package app.songyan.ime.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from LayoutKitTests/PhoneKeypadMetricsTests: pure geometry facts of the phone shell. */
class PhoneKeypadMetricsTest {
    private data class Device(
        val name: String,
        val screen: Double,
        val content: Double,
    )

    /** Screen width and the content width after the panel 4 dp insets on each side. */
    private val devices =
        listOf(
            Device("320", 320.0, 312.0),
            Device("375", 375.0, 367.0),
            Device("393", 393.0, 385.0),
            Device("390", 390.0, 382.0),
            Device("402", 402.0, 394.0),
            Device("420", 420.0, 412.0),
            Device("430", 430.0, 422.0),
            Device("440", 440.0, 432.0),
        )

    @Test
    fun contentWidthIsExactlyRailsPlusGapsPlusDigits() {
        for (device in devices) {
            val m = PhoneKeypadMetrics.resolve(device.content)
            assertEquals(device.name, m.contentWidth, m.computedContentWidth, 0.0001)
            assertEquals(device.name, m.contentWidth, m.railWidth * 2 + m.mainWidth + m.spacing * 2, 0.0001)
            assertEquals(device.name, m.digitWidth * 3 + m.spacing * 2, m.mainWidth, 0.0001)
            assertEquals(device.name, device.content, m.contentWidth + m.outerInset * 2, 0.0001)
        }
    }

    @Test
    fun bottomControlRowSharesTheGridContentWidth() {
        for (device in devices) {
            val m = PhoneKeypadMetrics.resolve(device.content)
            val control = m.controlRowRect()
            assertEquals(device.name, m.contentWidth, control.width, 0.0001)
            assertEquals(device.name, m.columnRect(PhoneKeypadMetrics.Column.LeadingRail).minX, control.minX, 0.0001)
            assertEquals(
                device.name,
                m.columnRect(PhoneKeypadMetrics.Column.TrailingRail).maxX,
                control.maxX,
                0.0001,
            )
            // No centring inset may be inserted between the grid and the control row.
            assertEquals(device.name, 0.0, m.outerInset, 0.0001)
        }
    }

    @Test
    fun everySlotIsAtLeast44dpOnSupportedPhones() {
        for (device in devices) {
            val m = PhoneKeypadMetrics.resolve(device.content)
            assertTrue(device.name, m.railWidth >= 44.0 - 0.0001)
            assertTrue(device.name, m.digitWidth >= 44.0 - 0.0001)
            assertTrue(device.name, m.rowHeight >= 44.0 - 0.0001)
        }
    }

    @Test
    fun railsNeverOutScaleTheDigits() {
        for (device in devices) {
            val m = PhoneKeypadMetrics.resolve(device.content)
            assertTrue(device.name, m.railWidth <= m.digitWidth + 0.0001)
        }
    }

    @Test
    fun railsAreMirrorSymmetricAboutTheCentreline() {
        for (device in devices) {
            val m = PhoneKeypadMetrics.resolve(device.content)
            assertTrue(device.name, m.railsAreSymmetric())
            val left = m.columnRect(PhoneKeypadMetrics.Column.LeadingRail)
            val right = m.columnRect(PhoneKeypadMetrics.Column.TrailingRail)
            assertEquals(device.name, left.width, right.width, 0.0001)
            assertEquals(device.name, m.contentWidth, left.midX + right.midX, 0.0001)
        }
    }

    @Test
    fun digitColumnsAreEvenlyPitchedAndSymmetric() {
        for (device in devices) {
            val m = PhoneKeypadMetrics.resolve(device.content)
            val d0 = m.columnRect(PhoneKeypadMetrics.Column.Digit(0))
            val d1 = m.columnRect(PhoneKeypadMetrics.Column.Digit(1))
            val d2 = m.columnRect(PhoneKeypadMetrics.Column.Digit(2))
            assertEquals(device.name, m.centerlineX, d1.midX, 0.0001)
            assertEquals(device.name, d0.width, d1.width, 0.0001)
            assertEquals(device.name, d1.width, d2.width, 0.0001)
            assertEquals(device.name, d1.minX - d0.minX, d2.minX - d1.minX, 0.0001)
            assertEquals(device.name, m.contentWidth, d0.minX + d2.maxX, 0.0001)
        }
    }

    @Test
    fun horizontalGapsAreAllExactlyTheSharedSpacing() {
        for (device in devices) {
            val m = PhoneKeypadMetrics.resolve(device.content)
            assertEquals(device.name, PhoneKeypadMetrics.SPACING, m.spacing, 0.0001)
            val d0 = m.columnRect(PhoneKeypadMetrics.Column.Digit(0))
            val d1 = m.columnRect(PhoneKeypadMetrics.Column.Digit(1))
            val d2 = m.columnRect(PhoneKeypadMetrics.Column.Digit(2))
            assertEquals(device.name, m.spacing, d1.minX - d0.maxX, 0.0001)
            assertEquals(device.name, m.spacing, d2.minX - d1.maxX, 0.0001)
            assertEquals(
                device.name,
                m.spacing,
                d0.minX - m.columnRect(PhoneKeypadMetrics.Column.LeadingRail).maxX,
                0.0001,
            )
            assertEquals(
                device.name,
                m.spacing,
                m.columnRect(PhoneKeypadMetrics.Column.TrailingRail).minX - d2.maxX,
                0.0001,
            )
        }
    }

    @Test
    fun railSlotsShareTheDigitRowBaselines() {
        for (device in devices) {
            val m = PhoneKeypadMetrics.resolve(device.content)
            val leading = m.railSlots(PhoneKeypadMetrics.Rail.LEADING, listOf(1, 1, 1))
            assertEquals(3, leading.size)
            for (row in 0 until 3) {
                assertEquals(device.name, m.rowY(row), leading[row].minY, 0.0001)
                assertEquals(device.name, m.rowHeight, leading[row].height, 0.0001)
            }
            val trailing = m.railSlots(PhoneKeypadMetrics.Rail.TRAILING, listOf(2, 1))
            assertEquals(2, trailing.size)
            assertEquals(device.name, m.rowY(0), trailing[0].minY, 0.0001)
            assertEquals(device.name, m.rowY(1) + m.rowHeight, trailing[0].maxY, 0.0001)
            assertEquals(device.name, m.rowY(2), trailing[1].minY, 0.0001)
            assertEquals(device.name, m.mainHeight, trailing[1].maxY, 0.0001)
        }
    }

    @Test
    fun rowPitchIsUniformAndMatchesSpacing() {
        for (device in devices) {
            val m = PhoneKeypadMetrics.resolve(device.content)
            assertEquals(device.name, m.spacing, m.rowY(1) - (m.rowY(0) + m.rowHeight), 0.0001)
            assertEquals(device.name, m.spacing, m.rowY(2) - (m.rowY(1) + m.rowHeight), 0.0001)
            assertEquals(device.name, m.rowHeight * 3 + m.spacing * 2, m.mainHeight, 0.0001)
        }
    }

    @Test
    fun doubleHeightAndSingleRowRailVisualTopsMatchTheirDigitRows() {
        for (device in devices) {
            val m = PhoneKeypadMetrics.resolve(device.content, rowHeight = 54.0)
            val insetX = 2.0
            val insetY = 2.0
            val slots = m.railSlots(PhoneKeypadMetrics.Rail.TRAILING, listOf(2, 1))
            val row0 = m.digitCellRect(row = 0, column = 0).visualRect(insetX, insetY)
            val row2 = m.digitCellRect(row = 2, column = 0).visualRect(insetX, insetY)
            assertEquals(device.name, row0.minY, slots[0].visualRect(insetX, insetY).minY, 0.0001)
            assertEquals(device.name, row2.minY, slots[1].visualRect(insetX, insetY).minY, 0.0001)
            val leading = m.railSlots(PhoneKeypadMetrics.Rail.LEADING, listOf(1, 1, 1))
            for (row in 0 until 3) {
                val railVisual = leading[row].visualRect(insetX, insetY)
                val digitVisual = m.digitCellRect(row = row, column = 0).visualRect(insetX, insetY)
                assertEquals(device.name, digitVisual.minY, railVisual.minY, 0.0001)
                assertEquals(device.name, digitVisual.maxY, railVisual.maxY, 0.0001)
            }
        }
    }

    @Test
    fun bottomControlRowIsOutsideTheThreeRowGrid() {
        for (device in devices) {
            val m = PhoneKeypadMetrics.resolve(device.content)
            assertEquals(device.name, m.mainHeight + m.spacing, m.controlRowRect().minY, 0.0001)
            assertEquals(device.name, m.mainHeight + m.spacing + m.rowHeight, m.totalHeight, 0.0001)
            for (row in 0 until 3) assertTrue(device.name, m.mainRowRect(row).maxY <= m.controlRowY + 0.0001)
        }
    }

    @Test
    fun railSlotsAreDeterministicRegardlessOfTheOtherRail() {
        assertEquals(listOf(3), PhoneKeypadMetrics.slotSpans(1))
        assertEquals(listOf(2, 1), PhoneKeypadMetrics.slotSpans(2))
        assertEquals(listOf(1, 1, 1), PhoneKeypadMetrics.slotSpans(3))
        assertEquals(listOf(1, 1, 1, 1), PhoneKeypadMetrics.slotSpans(4))
        assertTrue(PhoneKeypadMetrics.slotSpans(0).isEmpty())

        val m = PhoneKeypadMetrics.resolve(412.0)
        val oneKey = m.railSlotsForKeyCount(PhoneKeypadMetrics.Rail.TRAILING, 1)
        val twoKeys = m.railSlotsForKeyCount(PhoneKeypadMetrics.Rail.TRAILING, 2)
        assertEquals(1, oneKey.size)
        assertEquals(2, twoKeys.size)
        assertEquals(m.mainHeight, oneKey[0].height, 0.0001)
        assertEquals(oneKey[0].minY, twoKeys[0].minY, 0.0001)
        assertEquals(m.rowY(2), twoKeys[1].minY, 0.0001)
        assertEquals(m.mainHeight, twoKeys[1].maxY, 0.0001)
    }

    @Test
    fun visualInsetNeverChangesTheLayoutFrameOrHitRegion() {
        for (device in devices) {
            val m = PhoneKeypadMetrics.resolve(device.content)
            for (
            column in
            listOf(
                PhoneKeypadMetrics.Column.LeadingRail,
                PhoneKeypadMetrics.Column.Digit(0),
                PhoneKeypadMetrics.Column.Digit(2),
                PhoneKeypadMetrics.Column.TrailingRail,
            )
            ) {
                val slot = m.columnRect(column)
                for (inset in listOf(0.0, 2.0, 4.0)) {
                    val visual = slot.visualRect(inset, inset)
                    assertEquals(device.name, slot.minX + inset, visual.minX, 0.0001)
                    assertEquals(device.name, maxOf(0.0, slot.width - inset * 2), visual.width, 0.0001)
                    assertEquals(device.name, slot.minY + inset, visual.minY, 0.0001)
                    assertEquals(device.name, maxOf(0.0, slot.height - inset * 2), visual.height, 0.0001)
                }
            }
        }
    }

    @Test
    fun compactAndPortraitRowHeightsKeepTheSameHorizontalGeometry() {
        val portrait = PhoneKeypadMetrics.resolve(412.0, rowHeight = 54.0)
        val landscape = PhoneKeypadMetrics.resolve(812.0, rowHeight = 44.0)
        assertEquals(54.0 * 3 + 4 * 2, portrait.mainHeight, 0.0001)
        assertEquals(44.0 * 3 + 4 * 2, landscape.mainHeight, 0.0001)
        val other = PhoneKeypadMetrics.resolve(412.0, rowHeight = 44.0)
        assertEquals(portrait.railWidth, other.railWidth, 0.0001)
        assertEquals(portrait.digitWidth, other.digitWidth, 0.0001)
    }

    @Test
    fun geometryIsInvariantAcrossSupportedRowHeights() {
        val reference = PhoneKeypadMetrics.resolve(412.0, rowHeight = 54.0)
        var rowHeight = 44.0
        while (rowHeight <= 60.0) {
            val m = PhoneKeypadMetrics.resolve(412.0, rowHeight = rowHeight)
            assertEquals(rowHeight.toString(), reference.railWidth, m.railWidth, 0.0001)
            assertEquals(rowHeight.toString(), reference.digitWidth, m.digitWidth, 0.0001)
            assertEquals(rowHeight.toString(), rowHeight * 3 + 8, m.mainHeight, 0.0001)
            assertEquals(rowHeight.toString(), (rowHeight + 4) * 2, m.rowY(2), 0.0001)
            rowHeight += 1.0
        }
    }

    @Test
    fun tinierOrInvalidWidthsAreFiniteAndNeverOverflow() {
        for (width in listOf(0.0, 44.0, 100.0, 180.0, 250.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val m = PhoneKeypadMetrics.resolve(width)
            assertTrue(m.spacing.isFinite() && m.spacing >= 0.0)
            assertTrue(width.toString(), m.railWidth.isFinite() && m.railWidth >= 0.0)
            assertTrue(m.digitWidth.isFinite() && m.digitWidth >= 0.0)
            assertTrue(m.outerInset.isFinite() && m.outerInset >= 0.0)
            assertTrue(m.railWidth <= m.digitWidth + 0.0001)
            val expected = if (width.isFinite()) maxOf(0.0, width) else 0.0
            assertTrue(width.toString(), m.contentWidth <= expected + 0.0001)
        }
        for (rowHeight in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, 0.0)) {
            val m = PhoneKeypadMetrics.resolve(412.0, rowHeight = rowHeight)
            assertTrue(m.rowHeight.isFinite() && m.rowHeight >= 0.0)
            assertTrue(m.mainHeight.isFinite() && m.mainHeight >= 0.0)
        }
    }

    @Test
    fun veryNarrowPreviewsScaleAsOneUnitAndStillFillTheWidth() {
        val minimumContent = 44.0 * 5 + 4 * 4
        for (width in listOf(44.0, 100.0, 180.0, minimumContent - 1)) {
            val m = PhoneKeypadMetrics.resolve(width)
            assertEquals(width.toString(), width, m.contentWidth, 0.0001)
            assertEquals(width.toString(), m.digitWidth, m.railWidth, 0.0001)
            assertEquals(width.toString(), width, m.contentWidth + m.outerInset * 2, 0.0001)
        }
        val justAbove = PhoneKeypadMetrics.resolve(minimumContent + 1)
        assertTrue(justAbove.railWidth >= 44.0 - 0.0001)
        assertTrue(justAbove.digitWidth >= 44.0 - 0.0001)
        assertEquals(minimumContent + 1, justAbove.contentWidth, 0.0001)
    }

    @Test
    fun contentWidthIsMonotonicAcrossWidths() {
        var previous = 0.0
        var width = 0.0
        while (width <= 1200.0) {
            val m = PhoneKeypadMetrics.resolve(width)
            assertTrue(width.toString(), m.contentWidth >= previous - 0.0001)
            assertTrue(width.toString(), m.contentWidth <= maxOf(0.0, width) + 0.0001)
            previous = m.controlRowRect().maxX
            width += 7.0
        }
    }

    @Test
    fun airWidthFillsTheControlRowInsteadOfLeavingSideSlack() {
        val m = PhoneKeypadMetrics.resolve(412.0, rowHeight = 54.0)
        assertEquals(412.0, m.contentWidth, 0.0001)
        assertEquals(PhoneKeypadMetrics.PREFERRED_DIGIT_WIDTH, m.digitWidth, 0.0001)
        // Rails absorb the surplus: (412 - 252 - 16) / 2 = 72.
        assertEquals(72.0, m.railWidth, 0.0001)
        assertEquals(0.0, m.outerInset, 0.0001)
        assertEquals(0.0, m.columnRect(PhoneKeypadMetrics.Column.LeadingRail).minX, 0.0001)
        assertEquals(72.0, m.columnRect(PhoneKeypadMetrics.Column.LeadingRail).maxX, 0.0001)
        assertEquals(76.0, m.columnRect(PhoneKeypadMetrics.Column.Digit(0)).minX, 0.0001)
        assertEquals(160.0, m.columnRect(PhoneKeypadMetrics.Column.Digit(0)).maxX, 0.0001)
        assertEquals(164.0, m.columnRect(PhoneKeypadMetrics.Column.Digit(1)).minX, 0.0001)
        assertEquals(336.0, m.columnRect(PhoneKeypadMetrics.Column.Digit(2)).maxX, 0.0001)
        assertEquals(340.0, m.columnRect(PhoneKeypadMetrics.Column.TrailingRail).minX, 0.0001)
        assertEquals(412.0, m.columnRect(PhoneKeypadMetrics.Column.TrailingRail).maxX, 0.0001)
        assertEquals(206.0, m.centerlineX, 0.0001)
    }
}

/** Ported from LayoutKitTests/MergedKeypadGeometryTests. */
class MergedKeypadGeometryTest {
    private fun weights(layout: LayoutDefinition) =
        CompactSpellingLayout.mainRows(layout).map { row -> row.keys.map { it.widthWeight } }

    @Test
    fun eighteenKeyCapWidthsOnPhones() {
        val rowsKeys = CompactSpellingLayout.mainRows(BuiltinLayouts.merged18).map { it.keys }
        for ((screen, side) in listOf(420.0 to 68.4, 375.0 to 60.3)) {
            val width = screen - 8
            val rows = MergedKeypadGeometry.rows(weights(BuiltinLayouts.merged18), width, 4.0, side)
            val letterWidth = (width - 9 * 4) / 10
            val twoUnitPair = 2 * letterWidth + 4
            for ((index, row) in rows.withIndex()) {
                val caps = rowsKeys[index].zip(row.keyWidths)
                val singles = caps.filter { it.first.label.length == 1 }.map { it.second }
                val pairs = caps.filter { it.first.label.length == 2 }.map { it.second }
                assertEquals(1, singles.map { Math.round(it * 1000) }.toSet().size)
                assertEquals(1, pairs.map { Math.round(it * 1000) }.toSet().size)
                assertTrue(screen.toString(), pairs[0] > singles[0] * 1.25)
                if (index >= 2) continue
                assertTrue(screen.toString(), singles[0] > letterWidth + 6)
                assertTrue(screen.toString(), twoUnitPair - pairs[0] in 8.0..16.0)
            }
            assertEquals(0.0, rows[0].leadingInset, 0.001)
            assertEquals(0.65 * (width + 4) / 10.3, rows[1].leadingInset, 0.001)
            val last = rows[2].keyWidths.sum() + (rows[2].keyWidths.size - 1) * 4
            assertEquals(width - 2 * (side + 4), last, 0.001)
        }
    }

    @Test
    fun fourteenKeyCapsStayEqual() {
        val rows = MergedKeypadGeometry.rows(weights(BuiltinLayouts.merged14), 390.0, 4.0, 60.0)
        assertEquals(listOf(5, 5, 4), rows.map { it.keyWidths.size })
        for (row in rows) {
            assertEquals(
                1,
                row.keyWidths
                    .map { Math.round(it * 1000) }
                    .toSet()
                    .size,
            )
        }
        assertEquals((390.0 - 4 * 4) / 5, rows[0].keyWidths[0], 0.001)
        assertEquals(rows[0].keyWidths[0], rows[1].keyWidths[0], 0.001)
        assertEquals((390.0 - 2 * 64 - 3 * 4) / 4, rows[2].keyWidths[0], 0.001)
    }
}

/** Ported from LayoutKitTests/LayoutRowWidthProjectionTests. */
class LayoutRowWidthProjectionTest {
    @Test
    fun importedExtremeWeightsKeepEqualKeysVisible() {
        val min = java.lang.Double.MIN_VALUE
        for (value in listOf(1e308, min)) {
            val definition =
                LayoutDefinition(
                    id = "extreme",
                    name = "Extreme",
                    input = LayoutInput(builtinLayout = LayoutInput.Builtin.QWERTY26),
                    rows =
                        listOf(
                            LayoutRow(
                                listOf(
                                    LayoutKey("a", width = value, action = LayoutAction.literal("a")),
                                    LayoutKey("b", width = value, action = LayoutAction.literal("b")),
                                ),
                            ),
                        ),
                )
            val imported = LayoutJson.decode(definition.encoded())
            val projected = LayoutRowWidthProjection.of(imported.rows[0].keys.map { it.widthWeight })
            assertEquals(listOf(0.5, 0.5), projected.keyFractions)
            assertEquals(listOf(195.0, 195.0), projected.keyFractions.map { 390 * it })
            assertEquals(0.0, projected.insetFraction, 1e-15)
        }
        val single = LayoutRowWidthProjection.of(listOf(Double.MAX_VALUE))
        assertEquals(listOf(1.0), single.keyFractions)
    }

    @Test
    fun ordinaryRatiosAndBundledInsetsPreserveGeometry() {
        val projected = LayoutRowWidthProjection.of(listOf(1.0, 2.0, 3.0), inset = 0.5)
        val expected = listOf(1.0 / 7, 2.0 / 7, 3.0 / 7)
        for ((fraction, want) in projected.keyFractions.zip(expected)) {
            assertEquals(want, fraction, 1e-15)
        }
        assertEquals(0.5 / 7, projected.insetFraction, 1e-15)
        assertEquals(1.0, projected.keyFractions.sum() + projected.insetFraction * 2, 1e-15)
        for (scale in listOf(1e-300, 1e300)) {
            val scaled =
                LayoutRowWidthProjection.of(
                    listOf(scale, 2 * scale, 3 * scale),
                    inset = 0.5 * scale,
                )
            for ((fraction, want) in scaled.keyFractions.zip(projected.keyFractions)) {
                assertEquals(want, fraction, 1e-15)
            }
            assertEquals(projected.insetFraction, scaled.insetFraction, 1e-15)
        }
    }

    @Test
    fun previewValuesCannotProduceNonFiniteFractions() {
        val min = java.lang.Double.MIN_VALUE
        for (
        weights in
        listOf(
            emptyList<Double>(),
            listOf(0.0, -1.0),
            listOf(Double.POSITIVE_INFINITY, Double.NaN, 1.0),
            listOf(1e308, min),
        )
        ) {
            val projected = LayoutRowWidthProjection.of(weights, inset = Double.POSITIVE_INFINITY)
            assertEquals(weights.size, projected.keyFractions.size)
            assertTrue(projected.keyFractions.all { it.isFinite() && it in 0.0..1.0 })
            assertTrue(projected.insetFraction.isFinite())
        }
        val inset = LayoutRowWidthProjection.of(listOf(1e308), inset = 1e308)
        assertEquals(listOf(1.0 / 3), inset.keyFractions)
        assertEquals(1.0 / 3, inset.insetFraction, 1e-15)
    }
}

/** Ported from LayoutKitTests/SplitSpaceRowGeometryTests. */
class SplitSpaceRowGeometryTest {
    @Test
    fun spaceWidthTakesPriorityOverTheGutter() {
        val geometry =
            SplitSpaceRowGeometry.resolve(
                width = 834.0,
                requestedGap = 310.0,
                leftKeyWidths = listOf(50.0, 50.0, 44.0),
                rightKeyWidths = listOf(44.0, 60.0),
                preferredSpaceWidth = 156.0,
            )!!
        assertEquals(156.0, geometry.leftSpaceWidth, 0.001)
        assertEquals(156.0, geometry.rightSpaceWidth, 0.001)
        assertEquals(250.0, geometry.gap, 0.001)
    }

    @Test
    fun duoWidthKeepsEqualLargeSpacesBeforeKeepingALargeGutter() {
        val geometry =
            SplitSpaceRowGeometry.resolve(
                width = 466.0,
                requestedGap = 150.0,
                leftKeyWidths = listOf(50.0, 50.0, 44.0),
                rightKeyWidths = listOf(44.0, 60.0),
                preferredSpaceWidth = 96.0,
            )!!
        assertEquals(93.0, geometry.leftSpaceWidth, 0.001)
        assertEquals(93.0, geometry.rightSpaceWidth, 0.001)
        assertEquals(8.0, geometry.gap, 0.001)
    }

    @Test
    fun narrowWindowsKeepAnUnbrokenSpaceBarInsteadOfCrushingControls() {
        assertNull(
            SplitSpaceRowGeometry.resolve(
                width = 320.0,
                requestedGap = 60.0,
                leftKeyWidths = listOf(50.0, 50.0, 44.0),
                rightKeyWidths = listOf(44.0, 60.0),
            ),
        )
        assertNull(
            SplitSpaceRowGeometry.resolve(
                width = 834.0,
                requestedGap = 0.0,
                leftKeyWidths = listOf(50.0, 50.0, 44.0),
                rightKeyWidths = listOf(44.0, 60.0),
            ),
        )
    }

    @Test
    fun authoredControlWidthsAndUnevenBreaksLeaveASpaceOnEachSide() {
        val geometry =
            SplitSpaceRowGeometry.resolve(
                width = 834.0,
                requestedGap = 266.0,
                leftKeyWidths = listOf(44.0, 44.0, 88.0, 44.0),
                rightKeyWidths = listOf(66.0),
            )!!
        assertTrue(geometry.leftSpaceWidth >= 44.0)
        assertTrue(geometry.rightSpaceWidth >= 44.0)
        assertEquals(834.0, geometry.leftWidth + geometry.gap + geometry.spacing + geometry.rightWidth, 0.001)
    }

    @Test
    fun bothHalvesKeepReachableSpaceBarsAcrossWidths() {
        for (width in listOf(390.0, 466.0, 669.0, 834.0, 1194.0, 1366.0)) {
            val forced = LayoutSplitConfiguration(activation = LayoutSplitConfiguration.Activation.ALWAYS)
            val requested = forced.gap(width, keyCount = 6, minimumKeyWidth = 44.0)
            val geometry =
                SplitSpaceRowGeometry.resolve(
                    width = width,
                    requestedGap = requested,
                    leftKeyWidths = listOf(44.0, 44.0),
                    rightKeyWidths = listOf(44.0, 60.0),
                    preferredSpaceWidth = StandardKeyRowGeometry.resolve(width, forced).keyWidth * 3,
                )
            assertTrue(width.toString(), geometry != null)
            val g = geometry!!
            assertTrue(width.toString(), g.leftSpaceWidth >= 44.0 - 0.001)
            assertTrue(width.toString(), g.rightSpaceWidth >= 44.0 - 0.001)
            assertEquals(width.toString(), g.leftSpaceWidth, g.rightSpaceWidth, 0.001)
            assertEquals(width.toString(), width, g.leftWidth + g.gap + g.spacing + g.rightWidth, 0.001)
        }
    }
}
