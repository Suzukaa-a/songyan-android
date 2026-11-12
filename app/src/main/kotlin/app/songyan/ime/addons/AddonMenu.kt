// SPDX-License-Identifier: GPL-3.0-only
package app.songyan.ime.addons

/** The extension rows of the input-method menu, as (package name, label). */
internal object AddonMenu {
    /**
     * Enabled, compatible extensions that offer a panel. This reads the registry's in-memory
     * snapshot only: the service refreshes it when the keyboard shows and when the menu opens, so
     * an extension enabled in settings appears on the next open without restarting the keyboard.
     */
    fun entries(registry: AddonRegistry?): List<Pair<String, String>> =
        registry
            ?.enabledCompatible()
            ?.mapNotNull { entry ->
                if (entry.handshake?.supportsPanel == true) {
                    entry.packageName to entry.facts.label.ifBlank { entry.packageName }
                } else {
                    null
                }
            }.orEmpty()
}
