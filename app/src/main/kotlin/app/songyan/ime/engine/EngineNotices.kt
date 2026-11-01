package app.songyan.ime.engine

import app.songyan.ime.ime.EngineNotice
import app.songyan.ime.ime.EngineNoticeAction
import app.songyan.ime.ime.EngineNoticeKind

/**
 * What the keyboard says about the engine while it cannot take Chinese input; null once it can. The
 * keyboard shows no keys in any of these states, so this only decides the notice and its buttons. Deployment reports no percentage, so there is no progress to show.
 *
 * - First install and a slow preparation offer the settings screen, where the deployment can be
 *   watched; a quick preparation (an engine swap, a learning merge) shows no buttons at all.
 * - A failure offers a retry, which rebuilds the active schemes, and the settings screen.
 *
 * @param settingsOffered whether a preparation has lasted long enough to offer the settings path
 */
fun engineNoticeFor(
    state: EngineRuntimeState,
    settingsOffered: Boolean,
): EngineNotice? =
    when (state) {
        EngineRuntimeState.READY -> null
        EngineRuntimeState.FAILED ->
            EngineNotice(EngineNoticeKind.FAILED, listOf(EngineNoticeAction.RETRY, EngineNoticeAction.SETTINGS))
        EngineRuntimeState.NO_PUBLICATION ->
            EngineNotice(EngineNoticeKind.PREPARING, listOf(EngineNoticeAction.SETTINGS))
        EngineRuntimeState.PREPARING ->
            EngineNotice(
                EngineNoticeKind.PREPARING,
                if (settingsOffered) listOf(EngineNoticeAction.SETTINGS) else emptyList(),
            )
    }
