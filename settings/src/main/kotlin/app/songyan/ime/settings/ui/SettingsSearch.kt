package app.songyan.ime.settings.ui

import android.content.Context
import androidx.annotation.StringRes
import app.songyan.ime.settings.R

/** One place a setting can be found: the page [route] that holds it, named by its own title. */
data class SearchEntry(
    val route: String,
    @StringRes val title: Int,
    /** The page title shown under the result, so "模糊音" reads as part of 输入选项. */
    @StringRes val section: Int?,
    /** More words that should find the entry; shown nowhere. */
    @StringRes val keywords: Int? = null,
)

/** An entry with its strings in the language the user sees. */
data class SearchResult(
    val route: String,
    val title: String,
    val section: String?,
    val keywords: String = "",
)

object SettingsSearch {
    /** Every setting worth finding by name. Pages are found by their own title. */
    val entries: List<SearchEntry> =
        listOf(
            SearchEntry(
                SettingsRoutes.APPEARANCE,
                R.string.row_appearance_title,
                R.string.section_keyboard,
                R.string.row_appearance_subtitle,
            ),
            SearchEntry(SettingsRoutes.APPEARANCE, R.string.appearance_dynamic_title, R.string.row_appearance_title),
            SearchEntry(
                SettingsRoutes.OPTIONS,
                R.string.row_options_title,
                R.string.section_keyboard,
                R.string.row_options_subtitle,
            ),
            SearchEntry(SettingsRoutes.OPTIONS, R.string.options_candidate_size_section, R.string.row_options_title),
            SearchEntry(SettingsRoutes.OPTIONS, R.string.options_key_height_section, R.string.row_options_title),
            SearchEntry(SettingsRoutes.OPTIONS, R.string.options_bottom_gap_title, R.string.row_options_title),
            SearchEntry(SettingsRoutes.OPTIONS, R.string.options_engine_section, R.string.row_options_title),
            SearchEntry(SettingsRoutes.OPTIONS, R.string.options_one_hand_section, R.string.row_options_title),
            SearchEntry(SettingsRoutes.OPTIONS, R.string.options_feedback_section, R.string.row_options_title),
            SearchEntry(SettingsRoutes.OPTIONS, R.string.options_sound_title, R.string.row_options_title),
            SearchEntry(
                SettingsRoutes.OPTIONS,
                R.string.options_haptics_title,
                R.string.row_options_title,
                R.string.search_keywords_haptics,
            ),
            SearchEntry(SettingsRoutes.OPTIONS, R.string.options_key_flick_title, R.string.row_options_title),
            SearchEntry(SettingsRoutes.OPTIONS, R.string.options_double_space_title, R.string.row_options_title),
            SearchEntry(SettingsRoutes.OPTIONS, R.string.options_long_press_title, R.string.row_options_title),
            SearchEntry(
                SettingsRoutes.LAYOUTS,
                R.string.layouts_title,
                R.string.row_keyboards_title,
                R.string.layouts_entry_body,
            ),
            SearchEntry(
                SettingsRoutes.PAGES,
                R.string.pages_title,
                R.string.row_keyboards_title,
                R.string.pages_entry_body,
            ),
            SearchEntry(
                SettingsRoutes.COMPOSITION,
                R.string.composition_title,
                R.string.row_keyboards_title,
                R.string.composition_entry_body,
            ),
            SearchEntry(
                SettingsRoutes.LAYOUTS,
                R.string.layouts_switch_key_title,
                R.string.layouts_title,
                R.string.search_keywords_switch_key,
            ),
            SearchEntry(SettingsRoutes.OPTIONS, R.string.options_key_preview_title, R.string.row_options_title),
            SearchEntry(SettingsRoutes.OPTIONS, R.string.options_release_haptic_title, R.string.row_options_title),
            SearchEntry(SettingsRoutes.FEATURES, R.string.features_title, R.string.row_options_title),
            SearchEntry(SettingsRoutes.SHUANGPIN, R.string.shuangpin_title, R.string.row_options_title),
            SearchEntry(SettingsRoutes.KEYBOARD_MENU, R.string.keyboard_menu_title, R.string.row_keyboards_title),
            SearchEntry(SettingsRoutes.SHUANGPIN, R.string.shuangpin_switch, R.string.shuangpin_title),
            SearchEntry(
                SettingsRoutes.CANDIDATE_BAR,
                R.string.candidate_bar_hide_toolbar_title,
                R.string.candidate_bar_title,
            ),
            SearchEntry(SettingsRoutes.TOOLBAR, R.string.toolbar_title, R.string.row_options_title),
            SearchEntry(SettingsRoutes.FEATURES, R.string.features_typo_title, R.string.features_title),
            SearchEntry(SettingsRoutes.FEATURES, R.string.features_fuzzy_title, R.string.features_title),
            SearchEntry(SettingsRoutes.FEATURES, R.string.features_prediction_title, R.string.features_title),
            SearchEntry(SettingsRoutes.FEATURES, R.string.features_grammar_title, R.string.features_title),
            SearchEntry(
                SettingsRoutes.TRY_OUT,
                R.string.row_tryout_title,
                R.string.section_keyboard,
                R.string.row_tryout_subtitle,
            ),
            SearchEntry(
                SettingsRoutes.SCHEMES,
                R.string.row_schemes_title,
                R.string.section_content,
                R.string.row_schemes_subtitle,
            ),
            SearchEntry(
                SettingsRoutes.LEARNING,
                R.string.row_learning_title,
                R.string.section_content,
                R.string.row_learning_subtitle,
            ),
            SearchEntry(
                SettingsRoutes.SCHEMES,
                R.string.rime_config_section,
                R.string.row_schemes_title,
                R.string.rime_config_body,
            ),
            SearchEntry(SettingsRoutes.SCHEMES, R.string.schemes_dictionary_section, R.string.row_schemes_title),
            SearchEntry(SettingsRoutes.ADD_SCHEME, R.string.schemes_scheme_import_file, R.string.schemes_add_title),
            SearchEntry(SettingsRoutes.ADD_SCHEME, R.string.schemes_recommended_section, R.string.schemes_add_title),
            SearchEntry(SettingsRoutes.SCHEMES, R.string.schemes_sources_section, R.string.row_schemes_title),
            SearchEntry(SettingsRoutes.SCHEMES, R.string.schemes_methods_section, R.string.row_schemes_title),
            SearchEntry(
                SettingsRoutes.KEYBOARDS,
                R.string.row_keyboards_title,
                R.string.section_keyboard,
                R.string.row_keyboards_subtitle,
            ),
            SearchEntry(SettingsRoutes.NINE_KEY_EDITOR, R.string.t9_symbols_title, R.string.layouts_title),
            SearchEntry(
                SettingsRoutes.quickContent(),
                R.string.row_quick_content_title,
                R.string.section_content,
                R.string.row_quick_content_subtitle,
            ),
            SearchEntry(
                SettingsRoutes.quickContent(),
                R.string.quick_content_clipboard_section,
                R.string.row_quick_content_title,
            ),
            SearchEntry(
                SettingsRoutes.quickContent(),
                R.string.quick_content_phrases_section,
                R.string.row_quick_content_title,
            ),
            SearchEntry(
                SettingsRoutes.quickContent(),
                R.string.quick_content_symbols_section,
                R.string.row_quick_content_title,
            ),
            SearchEntry(
                SettingsRoutes.ONBOARDING,
                R.string.row_onboarding_title,
                R.string.section_other,
                R.string.row_onboarding_subtitle,
            ),
            SearchEntry(
                SettingsRoutes.ABOUT,
                R.string.row_about_title,
                R.string.section_other,
                R.string.row_about_subtitle,
            ),
            SearchEntry(SettingsRoutes.ABOUT, R.string.about_privacy_title, R.string.row_about_title),
            SearchEntry(SettingsRoutes.ABOUT, R.string.about_diagnostics, R.string.row_about_title),
            SearchEntry(SettingsRoutes.LICENSES, R.string.licenses_title, R.string.row_about_title),
        )

    fun resolve(context: Context): List<SearchResult> =
        entries.map {
            SearchResult(
                route = it.route,
                title = context.getString(it.title),
                section = it.section?.let(context::getString),
                keywords = it.keywords?.let(context::getString).orEmpty(),
            )
        }

    /**
     * The entries that contain every word of [query] in their title, section or keywords, ignoring
     * case; titles that match come before those that only match elsewhere. A blank query matches nothing.
     */
    fun filter(
        results: List<SearchResult>,
        query: String,
    ): List<SearchResult> {
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return results
            .filter { entry ->
                val haystack = listOfNotNull(entry.title, entry.section, entry.keywords).joinToString("\n")
                words.all { haystack.contains(it, ignoreCase = true) }
            }.sortedBy { entry -> if (words.all { entry.title.contains(it, ignoreCase = true) }) 0 else 1 }
    }
}
