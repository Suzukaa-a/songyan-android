package app.songyan.ime.settings.ui

import android.os.Build
import app.songyan.ime.settings.state.EngineStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPromptTest {
    private val preparing = EngineStatus.Preparing(percent = 10)

    private fun explain(
        engine: EngineStatus = preparing,
        loaded: Boolean = true,
        shown: Boolean = false,
        sdk: Int = Build.VERSION_CODES.TIRAMISU,
        granted: Boolean = false,
    ) = shouldExplainNotifications(engine, loaded, shown, sdk, granted)

    @Test
    fun itIsExplainedWhenTheFirstDeploymentRuns() {
        assertTrue(explain())
    }

    @Test
    fun itIsNeverExplainedBeforeAnythingNeedsDeploying() {
        assertFalse(explain(engine = EngineStatus.Ready))
        assertFalse(explain(engine = EngineStatus.Unknown))
    }

    @Test
    fun itIsAskedAboutOnlyOnce() {
        assertFalse(explain(shown = true))
    }

    @Test
    fun itWaitsForTheStoredFlagBeforeDeciding() {
        assertFalse(explain(loaded = false))
    }

    @Test
    fun olderAndroidVersionsNeedNoPermission() {
        assertFalse(explain(sdk = Build.VERSION_CODES.S))
    }

    @Test
    fun anAlreadyGrantedPermissionIsNotAskedForAgain() {
        assertFalse(explain(granted = true))
    }
}
