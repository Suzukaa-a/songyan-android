package app.songyan.ime.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songyan.ime.benchmark.ImeJourney.LAYOUT_26
import app.songyan.ime.benchmark.ImeJourney.LETTERS
import app.songyan.ime.benchmark.ImeJourney.PACKAGE
import app.songyan.ime.benchmark.ImeJourney.ensureChinese
import app.songyan.ime.benchmark.ImeJourney.hideKeyboard
import app.songyan.ime.benchmark.ImeJourney.openTryout
import app.songyan.ime.benchmark.ImeJourney.pickCandidate
import app.songyan.ime.benchmark.ImeJourney.prepareDevice
import app.songyan.ime.benchmark.ImeJourney.showKeyboard
import app.songyan.ime.benchmark.ImeJourney.type
import app.songyan.ime.benchmark.ImeJourney.useLayout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the code the keyboard runs on show, on the first taps and on candidate refresh, plus the
 * settings start, into the app's Baseline Profile and startup profile.
 * Run: ./gradlew :app:generateBaselineProfile (writes app/src/release/generated/baselineProfiles/).
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() =
        rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
            prepareDevice()
            useLayout(LAYOUT_26)
            openTryout()
            val keyboard = showKeyboard()
            ensureChinese(keyboard)
            repeat(2) {
                pickCandidate(type(keyboard, LETTERS))
            }
            hideKeyboard()
        }
}
