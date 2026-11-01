package app.songyan.ime.ime

import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImeViewOwnersTest {
    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    @Test
    fun showAndHideAfterDestroyAreIgnoredInsteadOfKillingTheProcess() {
        // The framework calls onFinishInputView while it destroys the service. Moving a destroyed
        // lifecycle throws, which crashed the input method process on every switch to another
        // keyboard and made the next switch back start cold (a blank keyboard for seconds).
        onMain {
            val owners = ImeViewOwners()
            owners.show()
            owners.destroy()
            owners.hide()
            owners.show()
            assertEquals(Lifecycle.State.DESTROYED, owners.lifecycle.currentState)
        }
    }

    @Test
    fun theVisibleLifetimeFollowsShowAndHide() {
        onMain {
            val owners = ImeViewOwners()
            owners.show()
            assertEquals(Lifecycle.State.RESUMED, owners.lifecycle.currentState)
            owners.hide()
            assertEquals(Lifecycle.State.CREATED, owners.lifecycle.currentState)
            owners.destroy()
        }
    }
}
