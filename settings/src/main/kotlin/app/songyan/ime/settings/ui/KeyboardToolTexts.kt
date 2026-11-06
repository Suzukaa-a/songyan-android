package app.songyan.ime.settings.ui

import android.content.Context
import app.songyan.ime.content.ToolDefinitionLimits
import app.songyan.ime.settings.R
import app.songyan.ime.settings.state.ToolProblem
import app.songyan.ime.settings.state.ToolStepKind

/**
 * The user-facing sentence for a refused tool action.
 *
 * Every reason is worded here and nowhere else, so an import or save never shows a developer
 * string and the same refusal reads the same on every page.
 */
fun toolProblemText(
    context: Context,
    problem: ToolProblem,
): String =
    when (problem) {
        ToolProblem.Unreadable -> context.getString(R.string.tools_error_unreadable)
        ToolProblem.NotAToolFile -> context.getString(R.string.tools_error_not_a_tool)
        is ToolProblem.TooLarge -> context.getString(R.string.tools_error_too_large, problem.maximumKilobytes)
        is ToolProblem.UnsupportedVersion -> context.getString(R.string.tools_error_version)
        is ToolProblem.UnsupportedStep ->
            context.getString(R.string.tools_error_step_type, problem.step, problem.type)
        is ToolProblem.UnavailableConfig ->
            context.getString(R.string.tools_error_opencc, problem.step, problem.config)
        is ToolProblem.InvalidRegex -> context.getString(R.string.tools_error_regex, problem.step)
        is ToolProblem.InvalidStep -> context.getString(R.string.tools_error_invalid_step, problem.step)
        ToolProblem.InvalidName -> context.getString(R.string.tools_error_name)
        ToolProblem.InvalidIdentifier, ToolProblem.InvalidShape -> context.getString(R.string.tools_error_invalid_shape)
        ToolProblem.NoSteps -> context.getString(R.string.tools_error_no_steps)
        is ToolProblem.TooManySteps ->
            context.getString(
                R.string.tools_error_too_many_steps,
                ToolDefinitionLimits.MAXIMUM_STEPS,
                problem.count,
            )
        is ToolProblem.PinLimit -> context.getString(R.string.tools_pin_limit, problem.maximum)
        is ToolProblem.TooManyTools -> context.getString(R.string.tools_error_too_many_tools, problem.maximum)
        ToolProblem.BuiltinImmutable -> context.getString(R.string.tools_error_builtin)
        ToolProblem.NothingToUndo, ToolProblem.Unknown -> context.getString(R.string.tools_error_unknown)
        ToolProblem.StorageUnavailable -> context.getString(R.string.tools_error_storage)
    }

/** The name of a step type in the editor's "add step" list and step headers. */
fun toolStepKindText(
    context: Context,
    kind: ToolStepKind,
): String =
    context.getString(
        when (kind) {
            ToolStepKind.OPENCC -> R.string.tools_step_type_opencc
            ToolStepKind.REPLACE -> R.string.tools_step_type_replace
            ToolStepKind.MAP -> R.string.tools_step_type_map
            ToolStepKind.CASE -> R.string.tools_step_type_case
            ToolStepKind.WIDTH -> R.string.tools_step_type_width
            ToolStepKind.TEMPLATE -> R.string.tools_step_type_template
        },
    )

/** The interface-language labels of the four names a tool file may carry. */
fun toolNameLanguageText(
    context: Context,
    tag: String,
): String =
    context.getString(
        when (tag) {
            "zh-Hans" -> R.string.tools_name_zh_hans
            "zh-Hant" -> R.string.tools_name_zh_hant
            "ja" -> R.string.tools_name_ja
            else -> R.string.tools_name_en
        },
    )

/** The label of one letter-case mode. */
fun toolCaseModeText(
    context: Context,
    mode: String,
): String =
    context.getString(
        when (mode) {
            "lower" -> R.string.tools_case_lower
            "capitalize" -> R.string.tools_case_capitalize
            else -> R.string.tools_case_upper
        },
    )

/** The label of one width-conversion direction. */
fun toolWidthModeText(
    context: Context,
    mode: String,
): String = context.getString(if (mode == "toFull") R.string.tools_width_to_full else R.string.tools_width_to_half)
