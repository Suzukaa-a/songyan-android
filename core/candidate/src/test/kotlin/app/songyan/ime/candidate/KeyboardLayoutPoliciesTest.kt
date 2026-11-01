package app.songyan.ime.candidate

import org.junit.Test

/** One-handed narrowing and wide-surface capping: exact widths and every incompatibility fallback. */
class KeyboardLayoutPoliciesTest {
    @Test
    fun oneHandModeResolvesMissingAndUnknownToOff() {
        assertEquals(KeyboardOneHandMode.OFF, KeyboardOneHandMode.resolve(null))
        assertEquals(KeyboardOneHandMode.OFF, KeyboardOneHandMode.resolve(""))
        assertEquals(KeyboardOneHandMode.OFF, KeyboardOneHandMode.resolve("Off"))
        assertEquals(KeyboardOneHandMode.OFF, KeyboardOneHandMode.resolve("center"))
        for (mode in KeyboardOneHandMode.entries) {
            assertEquals(mode, KeyboardOneHandMode.resolve(mode.wire))
        }
    }

    @Test
    fun offModeStaysFullWidthAtEveryPanelWidth() {
        for (width in listOf(320.0, 375.0, 390.0, 428.0, 600.0, 664.0, 744.0, 1024.0, 1366.0)) {
            for (mode in listOf(KeyboardOneHandMode.LEFT, KeyboardOneHandMode.RIGHT)) {
                assertEquals(
                    KeyboardOneHandPlan.inactive(width),
                    KeyboardOneHandLayoutPolicy.plan(mode = mode, panelWidth = width, isPhonePortrait = false),
                    "width " + width,
                )
            }
            assertEquals(
                KeyboardOneHandPlan.inactive(width),
                KeyboardOneHandLayoutPolicy.plan(
                    mode = KeyboardOneHandMode.OFF,
                    panelWidth = width,
                    isPhonePortrait = true,
                ),
            )
        }
    }

    @Test
    fun availabilityFollowsTheSurfaceRatherThanTheIdiom() {
        assertTrue(
            KeyboardOneHandLayoutPolicy.isAvailable(402.0, isPhone = true, compactHeight = false, regularWidth = false),
        )
        assertFalse(
            KeyboardOneHandLayoutPolicy.isAvailable(402.0, isPhone = true, compactHeight = false, regularWidth = true),
        )
        assertFalse(
            KeyboardOneHandLayoutPolicy.isAvailable(402.0, isPhone = true, compactHeight = true, regularWidth = false),
        )
        for (width in listOf(320.0, 402.0, 744.0, 1024.0)) {
            assertFalse(
                KeyboardOneHandLayoutPolicy.isAvailable(
                    width,
                    isPhone = false,
                    compactHeight = false,
                    regularWidth = false,
                ),
            )
        }
    }

    @Test
    fun commonAndroidPhoneWidthsOfferTheModeAndTooNarrowOnesDoNot() {
        for (width in listOf(360.0, 392.7, 411.4)) {
            assertTrue(
                KeyboardOneHandLayoutPolicy.isAvailable(
                    width,
                    isPhone = true,
                    compactHeight = false,
                    regularWidth = false,
                ),
                "width " + width,
            )
        }
        for (width in listOf(320.0, 331.9)) {
            assertFalse(
                KeyboardOneHandLayoutPolicy.isAvailable(
                    width,
                    isPhone = true,
                    compactHeight = false,
                    regularWidth = false,
                ),
                "width " + width,
            )
            assertEquals(
                KeyboardOneHandPlan.inactive(width),
                KeyboardOneHandLayoutPolicy.plan(KeyboardOneHandMode.LEFT, width, isPhonePortrait = true),
            )
        }
    }

    @Test
    fun standardPortraitWidthsProduceExactContentAndGutter() {
        val cases =
            listOf(
                332.0 to 288.0, // 282.2 floored is 282, so the 288 floor wins; gutter 44
                340.0 to 289.0,
                // The common Android phone width, also what a 393 dp phone becomes at a larger display size.
                360.0 to 306.0,
                375.0 to 318.0, // 318.75 -> 318
                390.0 to 331.0, // 331.5 -> 331
                393.0 to 334.0,
                402.0 to 341.0,
                414.0 to 351.0,
                428.0 to 363.0,
                430.0 to 365.0,
                440.0 to 374.0,
                450.0 to 382.0,
                466.0 to 396.0,
                600.0 to 510.0,
            )
        for ((width, content) in cases) {
            for (mode in listOf(KeyboardOneHandMode.LEFT, KeyboardOneHandMode.RIGHT)) {
                val plan = KeyboardOneHandLayoutPolicy.plan(mode = mode, panelWidth = width, isPhonePortrait = true)
                assertTrue(plan.active, "width " + width)
                assertEquals(content, plan.contentWidth, 0.001, "width " + width)
                assertEquals(width - content, plan.gutterWidth, 0.001, "width " + width)
                assertTrue(plan.gutterWidth >= KeyboardOneHandLayoutPolicy.MINIMUM_GUTTER_WIDTH)
                assertTrue(plan.contentWidth >= KeyboardOneHandLayoutPolicy.MINIMUM_CONTENT_WIDTH)
                assertEquals(width, plan.contentWidth + plan.gutterWidth, 0.001)
                // The content hugs the chosen edge: right-handed mode keeps the gutter on the left.
                assertEquals(mode == KeyboardOneHandMode.RIGHT, plan.gutterOnLeft, "width " + width)
            }
        }
    }

    @Test
    fun aNonFinitePanelWidthNeverActivatesThePlan() {
        for (width in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            for (mode in listOf(KeyboardOneHandMode.LEFT, KeyboardOneHandMode.RIGHT)) {
                val plan = KeyboardOneHandLayoutPolicy.plan(mode = mode, panelWidth = width, isPhonePortrait = true)
                assertFalse(plan.active, "width " + width + " must stay inactive")
                assertFalse(plan.contentWidth.isNaN(), "content must be finite for " + width)
                assertEquals(0.0, plan.gutterWidth, 0.001)
            }
        }
    }

    @Test
    fun foldingAndResizingRecomputeWithoutChangingTheSavedMode() {
        for (width in listOf(402.0, 744.0, 1024.0, 600.1, 402.0)) {
            val available =
                KeyboardOneHandLayoutPolicy.isAvailable(
                    width,
                    isPhone = true,
                    compactHeight = false,
                    regularWidth = false,
                )
            val plan =
                KeyboardOneHandLayoutPolicy.plan(
                    mode = KeyboardOneHandMode.RIGHT,
                    panelWidth = width,
                    isPhonePortrait = available,
                )
            if (width == 402.0) {
                assertTrue(plan.active)
                assertEquals(341.0, plan.contentWidth, 0.001)
                assertTrue(plan.gutterOnLeft)
            } else {
                assertEquals(KeyboardOneHandPlan.inactive(width), plan)
            }
        }
    }

    @Test
    fun thePlanTilesThePanelExactlyOnBothEdges() {
        var width = KeyboardOneHandLayoutPolicy.MINIMUM_PANEL_WIDTH
        while (width <= 600.0) {
            for (mode in listOf(KeyboardOneHandMode.LEFT, KeyboardOneHandMode.RIGHT)) {
                val plan = KeyboardOneHandLayoutPolicy.plan(mode = mode, panelWidth = width, isPhonePortrait = true)
                assertTrue(plan.active, "width " + width)
                assertEquals(width, plan.contentWidth + plan.gutterWidth, 0.001)
                assertFalse(plan.contentWidth.isNaN())
            }
            width += 1.0
        }
    }

    @Test
    fun dockResolvesMissingAndUnknownToCenter() {
        assertEquals(KeyboardContentDock.CENTER, KeyboardContentDock.resolve(null))
        assertEquals(KeyboardContentDock.CENTER, KeyboardContentDock.resolve(""))
        assertEquals(KeyboardContentDock.CENTER, KeyboardContentDock.resolve("Center"))
        assertEquals(KeyboardContentDock.CENTER, KeyboardContentDock.resolve("LEFT"))
        assertEquals(KeyboardContentDock.LEFT, KeyboardContentDock.resolve("left"))
        assertEquals(KeyboardContentDock.RIGHT, KeyboardContentDock.resolve("right"))
        assertEquals(
            listOf(KeyboardContentDock.LEFT, KeyboardContentDock.CENTER, KeyboardContentDock.RIGHT),
            KeyboardContentDock.entries,
        )
    }

    @Test
    fun phoneSizedSurfacesStayFullWidthForEveryDockAndCap() {
        for (width in listOf(0.0, 1.0, 320.0, 375.0, 600.0)) {
            for (cap in listOf(null, 444.0, 520.0)) {
                for (dock in KeyboardContentDock.entries) {
                    val plan =
                        KeyboardWideContentPolicy.plan(
                            panelWidth = width,
                            maximumContentWidth = cap,
                            dock = dock,
                        )
                    val inactive = KeyboardWideContentPlan.inactive(width)
                    assertEquals(inactive, plan, "width " + width + " cap " + cap + " dock " + dock)
                    assertEquals(width, plan.contentWidth, 0.001)
                    assertEquals(0.0, plan.leadingInset, 0.001)
                    assertEquals(0.0, plan.trailingInset, 0.001)
                }
            }
        }
    }

    @Test
    fun surfaceAboveTheThresholdActivatesAtTheCap() {
        for (width in listOf(600.1, 744.0, 834.0, 1024.0, 1366.0)) {
            for (cap in listOf(444.0, 520.0)) {
                for (dock in KeyboardContentDock.entries) {
                    val plan =
                        KeyboardWideContentPolicy.plan(
                            panelWidth = width,
                            maximumContentWidth = cap,
                            dock = dock,
                        )
                    assertTrue(plan.active, "width " + width + " cap " + cap)
                    assertEquals(cap, plan.contentWidth, 0.001)
                    assertEquals(width, plan.contentWidth + plan.leadingInset + plan.trailingInset, 0.001)
                    assertTrue(plan.leadingInset >= 0.0)
                    assertTrue(plan.trailingInset >= 0.0)
                }
            }
        }
    }

    @Test
    fun dockingPlacesTheCappedContentAtEachEdge() {
        val width = 834.0
        val cap = 520.0
        val left =
            KeyboardWideContentPolicy.plan(
                panelWidth = width,
                maximumContentWidth = cap,
                dock = KeyboardContentDock.LEFT,
            )
        assertEquals(0.0, left.leadingInset, 0.001)
        assertEquals(width - cap, left.trailingInset, 0.001)

        val right =
            KeyboardWideContentPolicy.plan(
                panelWidth = width,
                maximumContentWidth = cap,
                dock = KeyboardContentDock.RIGHT,
            )
        assertEquals(width - cap, right.leadingInset, 0.001)
        assertEquals(0.0, right.trailingInset, 0.001)

        val center =
            KeyboardWideContentPolicy.plan(
                panelWidth = width,
                maximumContentWidth = cap,
                dock = KeyboardContentDock.CENTER,
            )
        assertEquals((width - cap) / 2, center.leadingInset, 0.001)
        assertEquals((width - cap) / 2, center.trailingInset, 0.001)

        // Odd leftover pixels stay split within half a point and still tile exactly.
        val odd =
            KeyboardWideContentPolicy.plan(
                panelWidth = 833.0,
                maximumContentWidth = 444.0,
                dock = KeyboardContentDock.CENTER,
            )
        assertEquals(444.0, odd.contentWidth, 0.001)
        assertTrue(kotlin.math.abs(odd.leadingInset - odd.trailingInset) <= 0.5)
        assertEquals(833.0, odd.contentWidth + odd.leadingInset + odd.trailingInset, 0.001)
    }

    @Test
    fun missingOrOversizedCapStaysFullWidth() {
        for (width in listOf(602.0, 744.0, 834.0, 1366.0)) {
            for (cap in listOf(null, width, width + 1, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
                val plan =
                    KeyboardWideContentPolicy.plan(
                        panelWidth = width,
                        maximumContentWidth = cap,
                        dock = KeyboardContentDock.CENTER,
                    )
                assertEquals(KeyboardWideContentPlan.inactive(width), plan, "width " + width + " cap " + cap)
                assertEquals(width, plan.contentWidth, 0.001)
            }
        }
    }

    @Test
    fun malformedPanelWidthsNormalizeToFiniteFullWidth() {
        for (width in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0, -0.5)) {
            val plan =
                KeyboardWideContentPolicy.plan(
                    panelWidth = width,
                    maximumContentWidth = 444.0,
                    dock = KeyboardContentDock.LEFT,
                )
            assertFalse(plan.active)
            assertEquals(0.0, plan.contentWidth, 0.001)
            assertEquals(0.0, plan.leadingInset, 0.001)
            assertEquals(0.0, plan.trailingInset, 0.001)
        }
        assertEquals(
            KeyboardWideContentPlan.inactive(panelWidth = 0.0),
            KeyboardWideContentPlan.inactive(panelWidth = Double.NaN),
        )
    }

    @Test
    fun oneHandAndWideContentRangesNeverOverlap() {
        var width = 300.0
        while (width <= 1200.0) {
            val oneHand =
                KeyboardOneHandLayoutPolicy.plan(
                    mode = KeyboardOneHandMode.LEFT,
                    panelWidth = width,
                    isPhonePortrait = true,
                )
            val wide =
                KeyboardWideContentPolicy.plan(
                    panelWidth = width,
                    maximumContentWidth = 520.0,
                    dock = KeyboardContentDock.CENTER,
                )
            assertFalse(oneHand.active && wide.active, "width " + width)
            if (width > KeyboardWideContentPolicy.MINIMUM_ACTIVE_PANEL_WIDTH) {
                assertFalse(oneHand.active, "width " + width)
            } else {
                assertFalse(wide.active, "width " + width)
            }
            width += 1.0
        }
    }
}
