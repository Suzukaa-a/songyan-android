package app.songyan.ime.state

import app.songyan.ime.deploy.DeployState
import app.songyan.ime.settings.state.EngineStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** The home screen claimed 「松烟已就绪」 while the deploy process was killed and restarted in a loop. */
class EngineStatusTest {
    private fun status(
        deployState: DeployState,
        running: Boolean = false,
        usable: Boolean = true,
        pending: Boolean = false,
    ) = engineStatus(
        usable = usable,
        published = usable,
        companionsPending = pending,
        deployState = deployState,
        deployRunning = running,
    )

    @Test
    fun aUsableGenerationIsNotReadyWhileTheDeployProcessWorksOnIt() {
        assertEquals(EngineStatus.Preparing(0), status(DeployState.PREPARING, running = true))
        assertEquals(EngineStatus.Preparing(0), status(DeployState.READY, pending = true))
    }

    @Test
    fun aDeployThatDiedOrFailedOnTheLayoutsStillMissingIsAFailure() {
        assertEquals(EngineStatus.Failed, status(DeployState.PREPARING, running = false))
        assertEquals(EngineStatus.Failed, status(DeployState.FAILED, pending = true))
    }

    @Test
    fun aCompleteGenerationIsReadyEvenAfterAnUnrelatedFailure() {
        assertEquals(EngineStatus.Ready, status(DeployState.READY))
        // A rejected import leaves the generation in use as it was; the import reports its own failure.
        assertEquals(EngineStatus.Ready, status(DeployState.FAILED))
        assertEquals(EngineStatus.Unknown, status(DeployState.IDLE, usable = false))
    }
}
