// SPDX-License-Identifier: Apache-2.0
package app.songyan.ime.addon

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import java.security.MessageDigest

/** Facts about one package that claims to be the caller, gathered by [AddonCallers.facts]. */
public data class CallerPackageFacts(
    public val packageName: String,
    /** SHA-256 of the current signing certificate, lowercase hex. */
    public val signerSha256: List<String>,
    public val debuggable: Boolean,
)

/** Everything the caller check needs. Kept as plain data so the rule is unit-testable. */
public data class CallerFacts(
    public val uid: Int,
    public val packages: List<CallerPackageFacts>,
    public val addonDebuggable: Boolean,
)

/** The outcome of [AddonCallerPolicy.evaluate]. */
public sealed interface CallerDecision {
    /** [verified] is false when the debug-only permissive rule accepted an unverified caller. */
    public data class Accepted(
        public val verified: Boolean,
    ) : CallerDecision

    public data class Rejected(
        public val reason: String,
    ) : CallerDecision
}

/**
 * Who may bind to an addon service: the Songyan keyboard, and only it.
 *
 * The normal path requires both an accepted host package and one of the accepted signing
 * certificate digests, so another app cannot impersonate the keyboard. A third-party addon fills
 * in Songyan's published certificate fingerprint when it ships; the empty default rejects every
 * caller. Debug addons may additionally accept any caller while the addon itself is debuggable:
 * that is what makes the repository's sample addon and its integration tests possible, and it is
 * never compiled into a release addon.
 */
public class AddonCallerPolicy(
    public val acceptedHostPackages: Set<String> = DEFAULT_HOST_PACKAGES,
    public val acceptedHostSignerSha256: Set<String> = emptySet(),
    public val allowDebugCallers: Boolean = false,
) {
    private val signers: Set<String> = acceptedHostSignerSha256.map { it.lowercase() }.toSet()

    public fun evaluate(facts: CallerFacts): CallerDecision {
        if (allowDebugCallers && facts.addonDebuggable) {
            return CallerDecision.Accepted(verified = false)
        }
        val matched =
            facts.packages.any { packageFacts ->
                packageFacts.packageName in acceptedHostPackages &&
                    packageFacts.signerSha256.any { it.lowercase() in signers }
            }
        return if (matched) {
            CallerDecision.Accepted(verified = true)
        } else {
            CallerDecision.Rejected("caller is not the Songyan keyboard")
        }
    }

    public companion object {
        /** The package names of Songyan's release and debug builds. */
        public val DEFAULT_HOST_PACKAGES: Set<String> =
            setOf(AddonProtocol.HOST_PACKAGE, AddonProtocol.HOST_DEBUG_PACKAGE)

        /** Rejects every caller until the addon fills in Songyan's certificate fingerprint. */
        public val DEFAULT: AddonCallerPolicy = AddonCallerPolicy()
    }
}

/** Reads the signing facts and applies the policy; the addon service base class uses this. */
public object AddonCallers {
    public fun verify(
        context: Context,
        callingUid: Int,
        policy: AddonCallerPolicy,
    ): CallerDecision = policy.evaluate(facts(context, callingUid))

    public fun facts(
        context: Context,
        callingUid: Int,
    ): CallerFacts {
        val packageManager = context.packageManager
        val packages =
            (packageManager.getPackagesForUid(callingUid) ?: emptyArray()).map { packageName ->
                CallerPackageFacts(
                    packageName = packageName,
                    signerSha256 = signerDigests(packageManager, packageName),
                    debuggable = isDebuggable(packageManager, packageName),
                )
            }
        return CallerFacts(uid = callingUid, packages = packages, addonDebuggable = isAddonDebuggable(context))
    }

    /** Lowercase SHA-256 hex of one signing certificate. */
    public fun sha256Hex(certificate: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(certificate)
            .joinToString("") { byte -> "%02x".format(byte) }

    @Suppress("DEPRECATION")
    private fun signerDigests(
        packageManager: PackageManager,
        packageName: String,
    ): List<String> =
        try {
            val packageInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            packageInfo.signingInfo?.apkContentsSigners?.map { sha256Hex(it.toByteArray()) } ?: emptyList()
        } catch (failure: PackageManager.NameNotFoundException) {
            emptyList()
        } catch (failure: RuntimeException) {
            Log.w(TAG, "cannot read signers of " + packageName, failure)
            emptyList()
        }

    @Suppress("DEPRECATION")
    private fun isDebuggable(
        packageManager: PackageManager,
        packageName: String,
    ): Boolean =
        try {
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (failure: PackageManager.NameNotFoundException) {
            false
        }

    private fun isAddonDebuggable(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private const val TAG = "SongyanAddon"
}
