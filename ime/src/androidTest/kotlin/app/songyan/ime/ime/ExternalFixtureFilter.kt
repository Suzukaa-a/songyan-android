package app.songyan.ime.ime

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.runner.Description
import org.junit.runner.manipulation.Filter

/**
 * Leaves out the device tests whose target or real archives were not handed to this run.
 *
 * The connected test task names this filter instead of listing the classes in `notClass`: the
 * Android Gradle plugin passes only the first entry of a comma-separated runner argument to the
 * device. An assumption is no way out either, because the Gradle report counts it as a failure.
 */
class ExternalFixtureFilter : Filter() {
    // Tests that need a real archive but live in a class that also runs without one.
    private val excludedMethods: Set<String> =
        buildSet {
            if (InstrumentationRegistry.getArguments().getString("kagiroiFixture") == null) {
                add(
                    RealEngineEditorTest::class.java.name +
                        "#withTheChoiceOnAKana12SidewaysFlickStillTypesKanaAtTheCaret",
                )
            }
        }

    private val excluded: Set<String> =
        buildSet {
            val arguments = InstrumentationRegistry.getArguments()
            // First-install acceptance requires an explicitly selected isolated target.
            if (arguments.getString("songyan.bootstrap.target") == null) {
                add(BuiltinScriptDefaultDeviceTest::class.java.name)
            }
            // Offline catalogue tests need archives staged in this runner's own cache.
            if (arguments.getString("traditionalArchivesDir") == null) {
                add(ZhuyinKeyboardDeviceTest::class.java.name)
                add(CangjieKeyboardDeviceTest::class.java.name)
            }
            if (arguments.getString("kagiroiFixture") == null) {
                add(JapaneseRomajiDeviceTest::class.java.name)
                add(KanaFlickDeviceTest::class.java.name)
                add(JapaneseBoardMenuDeviceTest::class.java.name)
            }
        }

    override fun shouldRun(description: Description): Boolean =
        description.className !in excluded &&
            (description.className + "#" + description.methodName) !in excludedMethods

    override fun describe(): String = "device tests whose fixtures were handed to this run"
}
