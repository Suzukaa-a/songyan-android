package app.songyan.ime.deploy

import app.songyan.ime.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildStampsTest {
    @Test
    fun aReleaseStampDoesNotChangeWithTheInstallTime() {
        val first = BuildStamps.appVersion("1.0", 7, lastUpdateTime = 100, debug = false)
        val reinstalled = BuildStamps.appVersion("1.0", 7, lastUpdateTime = 200, debug = false)
        assertEquals("7", first)
        assertEquals("reinstalling or restoring the same build keeps its generations", first, reinstalled)
        assertTrue(BuildStamps.appVersion("1.1", 8, lastUpdateTime = 200, debug = false) != first)
    }

    @Test
    fun aDebugStampChangesWithEveryInstall() {
        val first = BuildStamps.appVersion("1.0-debug", 7, lastUpdateTime = 100, debug = true)
        val reinstalled = BuildStamps.appVersion("1.0-debug", 7, lastUpdateTime = 200, debug = true)
        assertTrue(first != reinstalled)
    }

    @Test
    fun theDataAndEngineHashesAreBakedIntoTheBuild() {
        val hex = Regex("^[0-9a-f]{16}$")
        assertTrue(BuildConfig.RIME_DATA_HASH, hex.matches(BuildConfig.RIME_DATA_HASH))
        assertTrue(BuildConfig.ENGINE_SOURCE_HASH, hex.matches(BuildConfig.ENGINE_SOURCE_HASH))
    }
}
