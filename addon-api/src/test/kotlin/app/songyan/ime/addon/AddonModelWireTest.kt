// SPDX-License-Identifier: Apache-2.0
package app.songyan.ime.addon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire codecs are pure maps, so the whole cross-process payload contract runs on the JVM.
 * These tests are the contract's regression suite: a renamed key, a lost field or a changed
 * default fails here before any device test.
 */
class AddonModelWireTest {
    @Test
    fun hostInfoSurvivesARoundTrip() {
        val info =
            AddonHostInfo(
                protocolMinVersion = 1,
                protocolMaxVersion = 2,
                hostPackage = AddonProtocol.HOST_PACKAGE,
                hostVersionName = "0.1.0",
                hostVersionCode = 42L,
                sdkInt = 36,
                capabilities = setOf(AddonCapability.TEXT, AddonCapability.PANEL),
            )
        assertEquals(info, addonHostInfoFromWire(info.toWire()))
    }

    @Test
    fun anEmptyPayloadFallsBackToSafeDefaults() {
        val info = addonHostInfoFromWire(emptyMap())
        assertEquals(AddonProtocol.MIN_VERSION, info.protocolMinVersion)
        assertEquals(AddonProtocol.MAX_VERSION, info.protocolMaxVersion)
        assertEquals("", info.hostPackage)
        assertTrue(info.capabilities.isEmpty())

        val handshake = addonHandshakeFromWire(emptyMap())
        assertFalse(handshake.isCompatible)
        assertEquals(AddonProtocol.MIN_VERSION, handshake.supportedMinVersion)
        assertEquals(AddonProtocol.MAX_VERSION, handshake.supportedMaxVersion)
        assertNull(handshake.settingsComponent)
    }

    @Test
    fun handshakeCarriesToolsCapabilitiesAndTheSettingsPage() {
        val handshake =
            AddonHandshake(
                protocolVersion = 1,
                addonId = "sample.uppercase",
                addonName = "示例 Addon",
                addonVersionName = "1.2",
                addonVersionCode = 7L,
                capabilities = setOf(AddonCapability.TEXT, AddonCapability.PANEL, AddonCapability.AUDIO),
                tools =
                    listOf(
                        AddonToolDescriptor(id = "uppercase", title = "大写"),
                        AddonToolDescriptor(
                            id = "bookTitle",
                            title = "加书名号",
                            requiredCapabilities = setOf(AddonCapability.TEXT),
                        ),
                    ),
                supportsPanel = true,
                supportsVoice = true,
                settingsComponent = "app.songyan.ime.addon.sample/.SampleSettingsActivity",
            )
        val decoded = addonHandshakeFromWire(handshake.toWire())
        assertEquals(handshake, decoded)
        assertTrue(decoded.isCompatible)
        assertEquals(setOf(AddonCapability.TEXT, AddonCapability.PANEL, AddonCapability.AUDIO), decoded.capabilities)
        assertEquals(2, decoded.tools.size)
    }

    @Test
    fun anIncompatibleHandshakeSaysWhyAndCarriesItsRange() {
        val handshake =
            AddonHandshake.incompatible(
                addonId = "old.addon",
                addonName = "Old Addon",
                reason = "no common protocol version",
                supportedMinVersion = 1,
                supportedMaxVersion = 1,
            )
        val decoded = addonHandshakeFromWire(handshake.toWire())
        assertFalse(decoded.isCompatible)
        assertEquals("no common protocol version", decoded.incompatibleReason)
        assertEquals(1, decoded.supportedMaxVersion)
    }

    @Test
    fun panelContextSurvivesARoundTrip() {
        val context =
            AddonPanelContext(
                hostWindowToken = null,
                displayId = 0,
                widthPx = 1080,
                heightPx = 900,
                densityDpi = 440,
                fontScale = 1.15f,
                insets = AddonInsets(leftPx = 0, topPx = 24, rightPx = 0, bottomPx = 48),
                inputType = 0x00000001,
                imeOptions = 0x00000006,
                darkMode = true,
                keyboardColor = 0xFF1B1B1F.toInt(),
            )
        assertEquals(context, addonPanelContextFromWire(context.toWire()))
        assertNull(addonPanelContextFromWire(emptyMap()).hostWindowToken)
        assertEquals(1f, addonPanelContextFromWire(emptyMap()).fontScale)
    }

    @Test
    fun textReadsAndEditsRoundTripWithTypedReasons() {
        val query = AddonTextQuery(AddonTextScope.SELECTION, maxUnits = 512)
        assertEquals(query, addonTextQueryFromWire(query.toWire()))
        assertNull(addonTextQueryFromWire(emptyMap()))

        val read = AddonTextResult(text = "你好", scope = AddonTextScope.BEFORE_CURSOR, isTruncated = true)
        assertEquals(read, addonTextResultFromWire(read.toWire()))
        val blocked = AddonTextResult(blockReason = AddonReadBlockReason.SECURE_ENTRY)
        assertEquals(blocked, addonTextResultFromWire(blocked.toWire()))

        val write = AddonTextWrite(text = "替换后", deleteBefore = 3)
        assertEquals(write, addonTextWriteFromWire(write.toWire()))
        // A delete-only edit (empty text) is legal.
        assertEquals(
            AddonTextWrite(text = "", deleteBefore = 2),
            addonTextWriteFromWire(AddonTextWrite("", 2).toWire()),
        )
        assertNull(addonTextWriteFromWire(mapOf("write.deleteBefore" to -1)))

        assertEquals(AddonWriteResult.APPLIED, addonWriteResultFromWire(AddonWriteResult.APPLIED.toWire()))
        assertEquals(
            AddonWriteResult(applied = false, blockReason = AddonWriteBlockReason.SECURE_ENTRY),
            addonWriteResultFromWire(AddonWriteResult(false, AddonWriteBlockReason.SECURE_ENTRY).toWire()),
        )
    }

    @Test
    fun failuresKeepTheirCodeAndMessage() {
        val failure = AddonFailure(AddonFailureCode.TOOL_ERROR, "网络不通")
        assertEquals(failure, addonFailureFromWire(failure.toWire()))
        assertEquals(AddonFailureCode.INTERNAL_ERROR, addonFailureFromWire(emptyMap()).code)
        for (code in AddonFailureCode.entries) {
            assertEquals(code, AddonFailureCode.fromWireName(code.wireName))
        }
        for (state in AddonVoiceState.entries) {
            assertEquals(state, AddonVoiceState.fromWireName(state.wireName))
        }
    }

    @Test
    fun unknownKeysAndUnknownCapabilityNamesAreIgnored() {
        val map = mapOf("future.key" to "value", "host.capabilities" to listOf("text", "futureCapability"))
        val info = addonHostInfoFromWire(map)
        assertEquals(setOf(AddonCapability.TEXT), info.capabilities)
    }

    @Test
    fun toolIdsAndTitlesAreValidated() {
        assertTrue(runCatching { AddonToolDescriptor(id = " ", title = "x") }.isFailure)
        assertTrue(runCatching { AddonToolDescriptor(id = "x", title = " ") }.isFailure)
        assertEquals(setOf(AddonCapability.TEXT), AddonToolDescriptor("x", "X").requiredCapabilities)
    }
}
