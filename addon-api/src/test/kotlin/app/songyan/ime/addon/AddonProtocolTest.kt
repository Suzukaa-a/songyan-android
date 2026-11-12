// SPDX-License-Identifier: Apache-2.0
package app.songyan.ime.addon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The version negotiation is the whole compatibility rule, so it is pinned case by case. */
class AddonProtocolTest {
    @Test
    fun theHighestCommonVersionWins() {
        assertEquals(3, AddonProtocol.negotiatedVersion(1, 3, 2, 5))
        assertEquals(5, AddonProtocol.negotiatedVersion(1, 5, 5, 5))
        assertEquals(1, AddonProtocol.negotiatedVersion(1, 1, 1, 1))
    }

    @Test
    fun aHostNewerThanTheAddonUsesTheAddonsNewest() {
        assertEquals(2, AddonProtocol.negotiatedVersion(2, 9, 1, 2))
    }

    @Test
    fun anAddonNewerThanTheHostUsesTheHostsNewest() {
        assertEquals(1, AddonProtocol.negotiatedVersion(1, 1, 1, 4))
    }

    @Test
    fun disjointRangesHaveNoCommonVersion() {
        assertNull(AddonProtocol.negotiatedVersion(4, 6, 1, 3))
        assertNull(AddonProtocol.negotiatedVersion(1, 2, 3, 9))
    }

    @Test
    fun malformedRangesAreRejectedInsteadOfGuessed() {
        assertNull(AddonProtocol.negotiatedVersion(3, 2, 1, 4))
        assertNull(AddonProtocol.negotiatedVersion(1, 4, 5, 4))
        assertNull(AddonProtocol.negotiatedVersion(0, 4, 1, 4))
        assertNull(AddonProtocol.negotiatedVersion(1, 4, 0, 4))
    }

    @Test
    fun theProtocolConstantsArePinnedBecauseTheyAreTheCrossProcessContract() {
        assertEquals(1, AddonProtocol.VERSION)
        assertEquals(1, AddonProtocol.MIN_VERSION)
        assertEquals(1, AddonProtocol.MAX_VERSION)
        assertEquals("app.songyan.ime.addon.SERVICE", AddonProtocol.SERVICE_ACTION)
        assertEquals("app.songyan.ime", AddonProtocol.HOST_PACKAGE)
        assertEquals(setOf("text", "audio", "panel"), AddonCapability.entries.map { it.wireName }.toSet())
        assertNull(AddonCapability.fromWireName("candidates"))
    }
}
