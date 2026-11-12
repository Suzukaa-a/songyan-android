// SPDX-License-Identifier: Apache-2.0
package app.songyan.ime.addon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Only Songyan may bind" is a policy, not a vibe: the release rule needs the host package and a
 * pinned certificate, and the debug rule is limited to debuggable addon builds.
 */
class AddonCallerPolicyTest {
    private val songyanSigner = "aa".repeat(32)
    private val releasePolicy =
        AddonCallerPolicy(
            acceptedHostPackages = setOf(AddonProtocol.HOST_PACKAGE),
            acceptedHostSignerSha256 = setOf(songyanSigner),
            allowDebugCallers = false,
        )

    private fun caller(
        packageName: String = AddonProtocol.HOST_PACKAGE,
        signer: String = songyanSigner,
    ): CallerFacts =
        CallerFacts(
            uid = 10_000,
            packages = listOf(CallerPackageFacts(packageName, listOf(signer), debuggable = false)),
            addonDebuggable = false,
        )

    @Test
    fun theReleaseKeyboardIsAccepted() {
        val decision = releasePolicy.evaluate(caller())
        assertEquals(CallerDecision.Accepted(verified = true), decision)
    }

    @Test
    fun theCertificateIsCaseInsensitive() {
        val decision = releasePolicy.evaluate(caller(signer = songyanSigner.uppercase()))
        assertEquals(CallerDecision.Accepted(verified = true), decision)
    }

    @Test
    fun anotherAppWithTheKeyboardPackageNameIsRejected() {
        val decision = releasePolicy.evaluate(caller(signer = "bb".repeat(32)))
        assertTrue(decision is CallerDecision.Rejected)
    }

    @Test
    fun anotherPackageWithTheRightCertificateIsRejected() {
        val decision = releasePolicy.evaluate(caller(packageName = "app.songyan.ime.fake"))
        assertTrue(decision is CallerDecision.Rejected)
    }

    @Test
    fun theEmptyDefaultRejectsEveryone() {
        assertTrue(AddonCallerPolicy.DEFAULT.evaluate(caller()) is CallerDecision.Rejected)
    }

    @Test
    fun aDebugAddonMayAcceptUnverifiedCallersButOnlyWhenItSaysSo() {
        val debugPolicy = AddonCallerPolicy(allowDebugCallers = true)
        val debugAddonFacts = caller(packageName = "some.test.host").copy(addonDebuggable = true)
        assertEquals(CallerDecision.Accepted(verified = false), debugPolicy.evaluate(debugAddonFacts))

        val releaseAddonFacts = caller(packageName = "some.test.host").copy(addonDebuggable = false)
        assertTrue(debugPolicy.evaluate(releaseAddonFacts) is CallerDecision.Rejected)
    }

    @Test
    fun aCallerWithSeveralPackagesOnlyNeedsOneMatchingOne() {
        val facts =
            CallerFacts(
                uid = 42,
                packages =
                    listOf(
                        CallerPackageFacts("unrelated.app", listOf("cc".repeat(32)), debuggable = false),
                        CallerPackageFacts(AddonProtocol.HOST_PACKAGE, listOf(songyanSigner), debuggable = false),
                    ),
                addonDebuggable = false,
            )
        assertEquals(CallerDecision.Accepted(verified = true), releasePolicy.evaluate(facts))
    }
}
