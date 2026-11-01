package app.songyan.ime.settings.state

/** Whether the system has the Songyan keyboard enabled and selected as the current input method. */
data class ImeStatus(
    val enabled: Boolean,
    val selected: Boolean,
    val readFailed: Boolean = false,
) {
    companion object {
        /** Reading the system state failed; the UI must offer a retry instead of guessing. */
        fun failed(): ImeStatus = ImeStatus(enabled = false, selected = false, readFailed = true)
    }
}

/** Reads [ImeStatus] from the platform. */
fun interface ImeStatusSource {
    fun read(): ImeStatus
}

/** Ordered onboarding steps; each one is either done or the current action. */
enum class OnboardingStep { ENABLE_IME, SELECT_IME, TRY_OUT }

/** Step completion derived from system state, so the guidance never claims something untrue. */
data class OnboardingProgress(
    val enableDone: Boolean,
    val selectDone: Boolean,
    val tryOutDone: Boolean,
    val readFailed: Boolean,
) {
    val allDone: Boolean get() = enableDone && selectDone && tryOutDone

    /** The first step that still needs an action; null when the flow is complete or the read failed. */
    val nextStep: OnboardingStep?
        get() =
            when {
                readFailed -> null
                !enableDone -> OnboardingStep.ENABLE_IME
                !selectDone -> OnboardingStep.SELECT_IME
                !tryOutDone -> OnboardingStep.TRY_OUT
                else -> null
            }
}

/**
 * A selected keyboard is by definition enabled, so [ImeStatus.selected] implies enablement even
 * when the enabled list could not be read.
 *
 * The try-out only counts while Songyan is the current keyboard; text typed into the field with
 * another input method must not mark the flow as complete.
 */
fun onboardingProgress(
    status: ImeStatus,
    hasTyped: Boolean,
): OnboardingProgress {
    if (status.readFailed) {
        return OnboardingProgress(
            enableDone = false,
            selectDone = false,
            tryOutDone = hasTyped,
            readFailed = true,
        )
    }
    val enabled = status.enabled || status.selected
    return OnboardingProgress(
        enableDone = enabled,
        selectDone = status.selected,
        tryOutDone = hasTyped && status.selected,
        readFailed = false,
    )
}
