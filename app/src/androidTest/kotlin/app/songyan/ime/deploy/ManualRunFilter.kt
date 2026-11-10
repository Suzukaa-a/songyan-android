package app.songyan.ime.deploy

import app.songyan.ime.TraditionalCatalogInstallDeviceTest
import org.junit.runner.Description
import org.junit.runner.manipulation.Filter

/**
 * Leaves the manually run device tests out of the connected test tasks.
 *
 * They take minutes or need a package pushed to the device first, so they run through
 * `am instrument -e class`, which does not name this filter. The task names a filter rather than
 * listing the classes in `notClass`: the Android Gradle plugin passes only the first entry of a
 * comma-separated runner argument to the device, and the Gradle report counts a JUnit assumption
 * as a failure.
 */
class ManualRunFilter : Filter() {
    private val manual =
        setOf(
            DeploySpeedBenchmark::class.java.name,
            KagiroiDeploymentDeviceTest::class.java.name,
            KagiroiPublicationDeviceTest::class.java.name,
            KagiroiRuntimeDeviceTest::class.java.name,
            KagiroiInstallDeviceTest::class.java.name,
            TraditionalCatalogInstallDeviceTest::class.java.name,
        )

    override fun shouldRun(description: Description): Boolean = description.className !in manual

    override fun describe(): String = "device tests the connected task runs"
}
