package app.songyan.ime.rime

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.runner.Description
import org.junit.runner.manipulation.Filter

/**
 * Leaves out the offline replay classes whose real archives were not handed to this run.
 *
 * The connected test task names this filter instead of listing the classes in `notClass`: the
 * Android Gradle plugin passes only the first entry of a comma-separated runner argument to the
 * device. An assumption is no way out either, because the Gradle report counts it as a failure.
 */
class ExternalFixtureFilter : Filter() {
    private val excluded: Set<String> =
        buildSet {
            val arguments = InstrumentationRegistry.getArguments()
            if (arguments.getString("traditionalArchivesDir") == null) {
                add(TraditionalReplayDeviceTest::class.java.name)
            }
            if (arguments.getString(JapaneseSchemeFixture.ARG_FIXTURE) == null) {
                add(JapaneseReplayTest::class.java.name)
                add(JapaneseLearningTest::class.java.name)
                add(KanaRomajiTableTest::class.java.name)
            }
        }

    override fun shouldRun(description: Description): Boolean = description.className !in excluded

    override fun describe(): String = "replay classes whose archives were handed to this run"
}
