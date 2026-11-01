package app.songyan.ime.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songyan.ime.benchmark.ImeJourney.COMMIT_SECTION
import app.songyan.ime.benchmark.ImeJourney.KEY_SECTION
import app.songyan.ime.benchmark.ImeJourney.LETTERS
import app.songyan.ime.benchmark.ImeJourney.PACKAGE
import app.songyan.ime.benchmark.ImeJourney.SHOW_SECTION
import app.songyan.ime.benchmark.ImeJourney.ensureChinese
import app.songyan.ime.benchmark.ImeJourney.hideKeyboard
import app.songyan.ime.benchmark.ImeJourney.openTryout
import app.songyan.ime.benchmark.ImeJourney.pickCandidate
import app.songyan.ime.benchmark.ImeJourney.prepareDevice
import app.songyan.ime.benchmark.ImeJourney.showKeyboard
import app.songyan.ime.benchmark.ImeJourney.type
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The input latencies, read from the ImePerf trace sections of a
 * benchmarkRelease build: keyboard show to first frame, key down to preedit applied, candidate up
 * to commit applied. Each iteration starts with a warm IME process and a hidden keyboard.
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class KeyboardBenchmarks {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun keyboardShow() =
        rule.measureRepeated(
            packageName = PACKAGE,
            metrics = listOf(TraceSectionMetric(SHOW_SECTION, TraceSectionMetric.Mode.First)),
            compilationMode = CompilationMode.Partial(),
            iterations = ITERATIONS,
            setupBlock = {
                prepareDevice()
                openTryout()
                // Warm the IME process and the engine, then start from a hidden keyboard.
                showKeyboard()
                hideKeyboard()
            },
        ) {
            showKeyboard()
            hideKeyboard()
        }

    @Test
    fun typingAndCommit() =
        rule.measureRepeated(
            packageName = PACKAGE,
            metrics =
                listOf(
                    TraceSectionMetric(KEY_SECTION, TraceSectionMetric.Mode.Average),
                    TraceSectionMetric(KEY_SECTION, TraceSectionMetric.Mode.Max),
                    TraceSectionMetric(KEY_SECTION, TraceSectionMetric.Mode.Count),
                    TraceSectionMetric(COMMIT_SECTION, TraceSectionMetric.Mode.First),
                ),
            compilationMode = CompilationMode.Partial(),
            iterations = ITERATIONS,
            setupBlock = {
                prepareDevice()
                openTryout()
                ensureChinese(showKeyboard())
                hideKeyboard()
            },
        ) {
            val keyboard = showKeyboard()
            pickCandidate(type(keyboard, LETTERS))
            hideKeyboard()
        }

    private companion object {
        const val ITERATIONS = 15
    }
}
