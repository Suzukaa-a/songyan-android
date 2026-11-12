// SPDX-License-Identifier: GPL-3.0-only
package app.songyan.ime.settings.ui

import app.songyan.ime.settings.state.AddonToolInfo

/**
 * Display rules of the Extensions page that must not depend on a device or the build type:
 *
 * A debug build of an extension declares tools it would never ship (the repository's sample adds
 * "Delayed result (debug only)" and "Crash the addon (debug only)"). The settings page keeps those
 * rows in a debuggable host, where the user is the developer running them, and drops them from a
 * release host, so a shipped keyboard does not advertise tools that cannot exist there.
 */
object ExtensionsUiModel {
    /** The marker the sample and the API docs use in a developer-only tool title. */
    private const val DEBUG_MARKER = "debug only"

    fun isDebugOnlyTool(title: String): Boolean = title.contains(DEBUG_MARKER, ignoreCase = true)

    /** The tools a host should list: all of them when [releaseHost] is false, shipped ones otherwise. */
    fun visibleTools(
        tools: List<AddonToolInfo>,
        releaseHost: Boolean,
    ): List<AddonToolInfo> = if (releaseHost) tools.filterNot { isDebugOnlyTool(it.title) } else tools
}
