package app.songyan.ime.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The bounded wait the host implementation uses after starting the transparent activity. The answer
 * comes from [MicrophonePermission.publish] exactly as the activity delivers it; a request that never
 * gets an answer must return null instead of waiting forever (the system can refuse the activity).
 */
class MicrophonePermissionTest {
    @Test
    fun anAnsweredRequestResolvesTheWait() =
        runBlocking {
            MicrophonePermission.forgetPreviousOutcome()
            val wait = async(Dispatchers.Default) { MicrophonePermission.awaitOutcome(2_000) }
            delay(50)
            MicrophonePermission.publish(MicrophonePermission.Outcome.DENIED)
            assertEquals(MicrophonePermission.Outcome.DENIED, wait.await())
        }

    @Test
    fun aRequestWithoutAnAnswerTimesOut() =
        runBlocking {
            MicrophonePermission.forgetPreviousOutcome()
            assertNull(MicrophonePermission.awaitOutcome(100))
        }

    @Test
    fun anEarlierAnswerIsNotTakenForTheNextRequest() =
        runBlocking {
            MicrophonePermission.publish(MicrophonePermission.Outcome.GRANTED)
            MicrophonePermission.forgetPreviousOutcome()
            assertNull(MicrophonePermission.awaitOutcome(100))
        }
}
