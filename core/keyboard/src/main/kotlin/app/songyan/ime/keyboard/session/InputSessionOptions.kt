package app.songyan.ime.keyboard.session

import app.songyan.ime.keyboard.engine.EngineBackendError
import app.songyan.ime.keyboard.engine.EngineSnapshot
import kotlinx.coroutines.CancellationException

// Engine option and learning-protection handling for [InputSession], split out to keep the session file
// small. Bodies are moved verbatim; the public entry points stay on the class.

internal suspend fun InputSession.setOptionImpl(
    name: String,
    value: Boolean,
) {
    if (retired) throw EngineBackendError.NotReady()
    try {
        val updated = engine.setOption(name, value)
        options = engine.optionStates()
        // A script switch can change the input language the key faces follow.
        refreshInputLanguage()
        apply(if (mode == InputMode.ENGLISH) EngineSnapshot() else updated)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        // The backend may have changed state before a persistence failure.
        catchingNonCancellation { reloadOptions() }
        catchingNonCancellation {
            apply(
                if (mode ==
                    InputMode.ENGLISH
                ) {
                    EngineSnapshot()
                } else {
                    engine.currentSnapshot()
                },
            )
        }
        throw error
    }
}

internal suspend fun InputSession.setLearningEnabledImpl(enabled: Boolean): InputSession.LearningProtection {
    if (retired) return InputSession.LearningProtection.UNSUPPORTED
    if (enabled) {
        if (learningEnabled) return InputSession.LearningProtection.ENFORCED
        learningEnabled = true
        catchingNonCancellation { engine.setLearningSuppressed(false) }
        refresh()
        return InputSession.LearningProtection.ENFORCED
    }
    learningEnabled = false
    // Prediction is learning-derived output; drop it before the engine is asked to stop learning.
    catchingNonCancellation { dismissPrediction() }
    suppressPrediction()
    // Asked again on every preparation: the proof belongs to the engine state of this editor, not
    // to whatever an earlier editor managed to establish.
    val enforced =
        engine.supportsLearningSuppression &&
            catchingNonCancellation { engine.setLearningSuppressed(true) }.getOrDefault(false)
    onUpdate?.invoke()
    return if (enforced) InputSession.LearningProtection.ENFORCED else InputSession.LearningProtection.UNSUPPORTED
}
