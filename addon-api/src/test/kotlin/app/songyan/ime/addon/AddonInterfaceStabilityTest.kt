// SPDX-License-Identifier: Apache-2.0
package app.songyan.ime.addon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The cross-process interfaces are append-only: a method may be added at the end, never removed,
 * renamed or reordered, because an installed addon built against an older protocol version must
 * keep working. This test compares the AIDL files with the frozen method table and fails on any
 * edit that would silently break installed addons.
 *
 * Changing a line here is a protocol version bump, and it must come with a new AddonProtocol
 * version and a migration note in docs/ARCHITECTURE.md.
 */
class AddonInterfaceStabilityTest {
    private val expected =
        mapOf(
            "ISongyanAddonService.aidl" to
                listOf(
                    "negotiate(Bundle)",
                    "openSession(Bundle,ISongyanAddonHost)",
                    "disconnect()",
                ),
            "ISongyanAddonSession.aidl" to
                listOf(
                    "tools()",
                    "runTextTool(String,String,Bundle)",
                    "cancelRun(String)",
                    "startVoice(String,Bundle)",
                    "stopVoice()",
                    "openPanel(String,Bundle,ISongyanAddonPanelHost)",
                    "updatePanelContext(Bundle)",
                    "closePanel()",
                    "close()",
                ),
            "ISongyanAddonHost.aidl" to
                listOf(
                    "onToolResults(String,List<String>)",
                    "onToolFailed(String,Bundle)",
                    "onVoiceText(String,boolean)",
                    "onVoiceState(String)",
                    "onWarning(String)",
                ),
            "ISongyanAddonPanelHost.aidl" to
                listOf(
                    "onSurfaceReady(SurfacePackage,int,int)",
                    "insertText(Bundle)",
                    "readText(Bundle)",
                    "panelContext()",
                    "requestPanelSize(int,int)",
                    "closePanel()",
                ),
        )

    @Test
    fun everyPublishedInterfaceKeepsItsFrozenMethodTable() {
        val directory = aidlDirectory()
        for ((file, methods) in expected) {
            assertEquals("interface method list changed in " + file, methods, signatures(File(directory, file)))
        }
    }

    @Test
    fun theHostCallbacksStayOneway() {
        val host = File(aidlDirectory(), "ISongyanAddonHost.aidl").readText()
        assertTrue(host.contains("oneway interface ISongyanAddonHost"))
    }

    @Test
    fun everyAidlFileCarriesTheApacheHeader() {
        for (file in expected.keys) {
            assertTrue(
                file,
                File(aidlDirectory(), file).readText().startsWith("// SPDX-License-Identifier: Apache-2.0"),
            )
        }
    }

    private fun aidlDirectory(): File {
        val direct = File("src/main/aidl/app/songyan/ime/addon")
        if (direct.isDirectory) return direct
        // Some Gradle configurations run unit tests from the root project directory.
        return File("addon-api/src/main/aidl/app/songyan/ime/addon")
    }

    private fun signatures(file: File): List<String> {
        val method = Regex("^\\s+(?:oneway\\s+)?([\\w.]+(?:<[\\w.<>, ]+>)?)\\s+(\\w+)\\(([^)]*)\\);\\s*$")
        return file
            .readLines()
            .map { it.substringBefore("//").trimEnd() }
            .mapNotNull { line -> method.find(line) }
            .map { match ->
                val parameters =
                    match.groupValues[3]
                        .split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .joinToString(",") { parameter ->
                            parameter
                                .removePrefix("in ")
                                .removePrefix("out ")
                                .removePrefix("inout ")
                                .trim()
                                .substringBeforeLast(' ')
                        }
                match.groupValues[2] + "(" + parameters + ")"
            }
    }
}
