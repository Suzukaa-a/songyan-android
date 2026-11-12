// SPDX-License-Identifier: GPL-3.0-only
package app.songyan.ime.addons

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import app.songyan.ime.addon.AddonCallers
import app.songyan.ime.addon.AddonProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One installed extension APK as the package manager describes it: identity, signing certificate
 * and the dangerous permissions it declares. This is what the Extensions page shows without binding.
 */
internal data class AddonPackageFacts(
    val packageName: String,
    /** The exported service class Songyan binds; explicit, never resolved by an implicit intent. */
    val serviceClassName: String,
    val label: String,
    val versionName: String,
    val signerSha256: String?,
    val permissionLabels: List<String>,
)

/** Finds the extension APKs installed on this device. Replaceable so registry logic is JVM-testable. */
internal fun interface AddonDiscovery {
    suspend fun discover(): List<AddonPackageFacts>
}

internal class AndroidAddonDiscovery(
    private val context: Context,
) : AddonDiscovery {
    override suspend fun discover(): List<AddonPackageFacts> =
        withContext(Dispatchers.IO) {
            val packageManager = context.packageManager
            val intent = Intent(AddonProtocol.SERVICE_ACTION)

            @Suppress("DEPRECATION")
            val services = packageManager.queryIntentServices(intent, PackageManager.GET_SERVICES)
            services
                .mapNotNull { info ->
                    info.serviceInfo?.let { service ->
                        facts(packageManager, service.packageName, service.name)
                    }
                }.distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
        }

    private fun facts(
        packageManager: PackageManager,
        packageName: String,
        serviceName: String,
    ): AddonPackageFacts? {
        @Suppress("DEPRECATION")
        val packageInfo =
            runCatching { packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS) }.getOrNull()
                ?: return null
        val applicationInfo = packageInfo.applicationInfo ?: return null
        val declared = packageInfo.requestedPermissions?.toList().orEmpty()
        return AddonPackageFacts(
            packageName = packageName,
            serviceClassName = serviceName,
            label = applicationInfo.loadLabel(packageManager).toString(),
            versionName = packageInfo.versionName.orEmpty(),
            signerSha256 = signingCertificate(packageManager, packageName),
            permissionLabels = declared.mapNotNull { permissionLabel(packageManager, it) }.distinct(),
        )
    }

    private fun signingCertificate(
        packageManager: PackageManager,
        packageName: String,
    ): String? {
        @Suppress("DEPRECATION")
        val info =
            runCatching {
                packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            }.getOrNull() ?: return null
        val signer = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return null
        return AddonCallers.sha256Hex(signer.toByteArray())
    }

    /** Only permissions that protect something from the user's point of view are worth showing. */
    private fun permissionLabel(
        packageManager: PackageManager,
        name: String,
    ): String? {
        @Suppress("DEPRECATION")
        val info =
            runCatching { packageManager.getPermissionInfo(name, 0) }.getOrNull()
                ?: return null
        val dangerous = (info.protectionFlags and PermissionInfo.PROTECTION_DANGEROUS) != 0
        if (!dangerous) return null
        return info.loadLabel(packageManager).toString()
    }
}
