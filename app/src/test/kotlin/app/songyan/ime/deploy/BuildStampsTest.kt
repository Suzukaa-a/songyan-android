package app.songyan.ime.deploy

import app.songyan.ime.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildStampsTest {
    // Regression: the stamp used to carry the version code, so every update recompiled every input
    // method and the first switch after it waited on 键盘准备中. Only content hashes may go in.
    @Test
    fun theStampIsBuiltFromContentHashesOnly() {
        val hex = Regex("^[0-9a-f]{16}$")
        assertTrue(BuildConfig.RIME_DATA_HASH, hex.matches(BuildConfig.RIME_DATA_HASH))
        assertTrue(BuildConfig.ENGINE_SOURCE_HASH, hex.matches(BuildConfig.ENGINE_SOURCE_HASH))
        assertTrue(BuildConfig.DEPLOY_SOURCE_HASH, hex.matches(BuildConfig.DEPLOY_SOURCE_HASH))
        assertEquals(BuildConfig.DEPLOY_SOURCE_HASH, BuildStamps.current().app)
    }
}
