package app.songyan.ime.settings.ui.screens

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.songyan.ime.layout.LayoutException
import app.songyan.ime.layout.LayoutInvalidCode
import app.songyan.ime.settings.R

/** The sentence that tells the user why a layout or page edit was refused. */
@Composable
fun layoutErrorMessage(error: LayoutException): String = stringResource(layoutErrorRes(error))

@StringRes
internal fun layoutErrorRes(error: LayoutException): Int =
    when (error) {
        is LayoutException.Invalid -> codeRes(error.code)
        is LayoutException.DuplicateId -> R.string.layout_error_duplicate
        is LayoutException.UnknownId -> R.string.layout_error_unknown
    }

@StringRes
private fun codeRes(code: LayoutInvalidCode): Int =
    when (code) {
        LayoutInvalidCode.T9_FIRST_KEY_BEHAVIOR_REQUIRES_PHONE -> R.string.layout_error_t9_behavior
        LayoutInvalidCode.T9_QUICK_SYMBOLS_INVALID -> R.string.layout_error_t9_symbols
        LayoutInvalidCode.ASCII_VALUE_ONLY_ON_LITERAL -> R.string.layout_error_ascii_only_literal
        LayoutInvalidCode.CODE_REQUIRES_ONE_PRINTABLE_ASCII_CHARACTER -> R.string.layout_error_code_char
        LayoutInvalidCode.LITERAL_REQUIRES_NONEMPTY_VALUE -> R.string.layout_error_literal_empty
        LayoutInvalidCode.ASCII_VALUE_MUST_NOT_BE_EMPTY -> R.string.layout_error_ascii_empty
        LayoutInvalidCode.MODE_SWITCH_SUPPORTS_ONLY_NEXT_LAYOUT -> R.string.layout_error_mode_switch
        LayoutInvalidCode.PAGE_SWITCH_REQUIRES_KNOWN_TARGET -> R.string.layout_error_page_switch
        LayoutInvalidCode.LAYOUT_PAGE_SWITCH_REQUIRES_KNOWN_TARGET -> R.string.layout_error_layout_page_switch
        LayoutInvalidCode.LANGUAGE_SWITCH_REQUIRES_KNOWN_BEHAVIOR -> R.string.layout_error_language_switch
        LayoutInvalidCode.FUNCTION_KEY_TAKES_NO_VALUE,
        LayoutInvalidCode.KEY_OF_KIND_TAKES_NO_VALUE,
        -> R.string.layout_error_no_value
        LayoutInvalidCode.LONG_PRESS_DELAY_MUST_BE_POSITIVE_FINITE -> R.string.layout_error_long_press_delay
        LayoutInvalidCode.ID_AND_NAME_REQUIRED,
        LayoutInvalidCode.PAGE_ID_AND_NAME_REQUIRED,
        LayoutInvalidCode.PAGE_CATEGORY_ID_AND_NAME_REQUIRED,
        -> R.string.layout_error_name_required
        LayoutInvalidCode.SCHEMA_ID_MUST_NOT_BE_EMPTY,
        LayoutInvalidCode.INPUT_REQUIRES_SCHEMA_OR_BUILTIN,
        -> R.string.layout_error_input
        LayoutInvalidCode.ROWS_MUST_CONTAIN_KEYS,
        LayoutInvalidCode.PAGE_KEYPAD_REQUIRES_ROWS,
        -> R.string.layout_error_rows
        LayoutInvalidCode.READING_ROW_REQUIRES_CANDIDATE_AREA,
        LayoutInvalidCode.PHONE_LAYOUT_USES_LEADING_READING_RAIL,
        LayoutInvalidCode.CODE_KEYS_REQUIRE_CANDIDATE_AREA,
        LayoutInvalidCode.DISAMBIGUATION_KEYS_MUST_BE_ENGINE_SUPPLIED,
        -> R.string.layout_error_candidate_area
        LayoutInvalidCode.KEY_REQUIRES_LABEL_AND_POSITIVE_FINITE_WIDTH -> R.string.layout_error_key_label_width
        LayoutInvalidCode.LONG_PRESS_REQUIRES_LABEL -> R.string.layout_error_long_press_label
        LayoutInvalidCode.SPLIT_GAP_FRACTION_OUT_OF_RANGE -> R.string.layout_error_split_gap
        LayoutInvalidCode.SPLIT_BREAK_OUT_OF_RANGE -> R.string.layout_error_split_break
        LayoutInvalidCode.SPLIT_HALVES_ROW_COUNT_MISMATCH,
        LayoutInvalidCode.SPLIT_HALF_EMPTY,
        -> R.string.layout_error_split_halves
        LayoutInvalidCode.SPLIT_HALVES_ONLY_ON_LAYOUT -> R.string.layout_error_split_halves_page
        LayoutInvalidCode.RESOURCE_BUDGET_EXCEEDED,
        LayoutInvalidCode.CATALOG_TOTAL_EXCEEDED,
        LayoutInvalidCode.REGISTRY_PAGE_LIMIT,
        LayoutInvalidCode.REGISTRY_COMPOSITION_LIMIT,
        -> R.string.layout_error_too_large
        LayoutInvalidCode.MALFORMED_JSON -> R.string.layout_error_malformed
        LayoutInvalidCode.UNREADABLE_SOURCE -> R.string.layout_error_unreadable
        LayoutInvalidCode.DUPLICATE_LAYOUT_IDS,
        LayoutInvalidCode.PAGE_DUPLICATE_IDS,
        LayoutInvalidCode.PAGE_CATEGORY_IDS_DUPLICATED,
        -> R.string.layout_error_duplicate
        LayoutInvalidCode.PAGE_KEY_MUST_NOT_SEND_A_CODE -> R.string.layout_error_page_code
        LayoutInvalidCode.PAGE_UNKNOWN -> R.string.layout_error_page_unknown
        LayoutInvalidCode.PAGE_IN_USE -> R.string.layout_error_page_in_use
        LayoutInvalidCode.PAGE_PRESET_IS_READ_ONLY -> R.string.layout_error_page_preset
        LayoutInvalidCode.LAYOUT_PRESET_IS_READ_ONLY -> R.string.layout_error_layout_preset
        LayoutInvalidCode.LAYOUT_IN_USE -> R.string.layout_error_layout_in_use
        LayoutInvalidCode.COMPOSITION_KEYBOARD_ID_INVALID,
        LayoutInvalidCode.COMPOSITION_INPUT_METHOD_INCOMPATIBLE,
        LayoutInvalidCode.COMPOSITION_PAGE_KIND_MISMATCH,
        -> R.string.layout_error_pairing
        LayoutInvalidCode.PAGE_NUMERIC_PRESENTATION_ONLY_ON_NUMERIC_PAGE,
        LayoutInvalidCode.PAGE_AUTOMATIC_NUMERIC_TAKES_NO_CONTENT,
        LayoutInvalidCode.PAGE_CATEGORIES_ONLY_ON_SYMBOL_PAGE,
        LayoutInvalidCode.PAGE_CATEGORIES_NEED_CATEGORY_PRESENTATION,
        LayoutInvalidCode.PAGE_CATEGORY_PAGE_TAKES_NO_LEADING_RAIL,
        LayoutInvalidCode.PAGE_CATEGORIES_REQUIRED,
        LayoutInvalidCode.PAGE_RAIL_WIDTH_MUST_BE_POSITIVE,
        LayoutInvalidCode.PAGE_CATEGORY_COLUMNS_OUT_OF_RANGE,
        LayoutInvalidCode.PAGE_CATEGORY_KEYS_REQUIRED,
        -> R.string.layout_error_page_invalid
    }
