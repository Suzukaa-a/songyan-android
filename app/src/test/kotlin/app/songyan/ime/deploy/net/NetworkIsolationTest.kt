package app.songyan.ime.deploy.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The default process (input method and settings) never opens a network connection. Only the
 * `deploy.net` package may name network classes, and only the :deploy process constructs it.
 */
class NetworkIsolationTest {
    private val networkTokens =
        listOf(
            "HttpURLConnection",
            "HttpsURLConnection",
            "openConnection",
            "java.net.Socket",
            "okhttp3",
            "java.net.URL",
        ).map { Regex(Regex.escape(it)) } + Regex("\\bURL\\(")

    private val allowedPackage = "app/songyan/ime/deploy/net/"

    /** Sources of every module the default process loads. */
    private val roots =
        listOf("src/main/kotlin", "../ime/src/main/kotlin", "../settings/src/main/kotlin")
            .map { File(it) }
            .plus(File("../core").listFiles().orEmpty().map { File(it, "src/main/kotlin") })
            .filter { it.isDirectory }

    @Test
    fun onlyTheDeployNetworkPackageNamesNetworkClasses() {
        assertTrue("source roots not found from " + File(".").absolutePath, roots.any { it.path == "src/main/kotlin" })
        val offenders =
            roots.flatMap { root ->
                root
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .filter { !it.invariantSeparatorsPath.contains(allowedPackage) }
                    .filter { file -> file.readText().let { text -> networkTokens.any { it.containsMatchIn(text) } } }
                    .map { it.invariantSeparatorsPath }
                    .toList()
            }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun theNetworkPackageIsOnlyUsedByTheDeployProcess() {
        val users =
            File("src/main/kotlin")
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" && !it.invariantSeparatorsPath.contains(allowedPackage) }
                .filter { it.readText().contains("app.songyan.ime.deploy.net") }
                .map { it.name }
                .toSet()
        // DeployPipeline runs only inside DeployService (android:process=":deploy").
        assertEquals(setOf("DeployPipeline.kt"), users)
    }
}
