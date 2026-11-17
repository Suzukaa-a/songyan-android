package app.songyan.ime.content

import android.content.Context
import app.songyan.ime.R
import app.songyan.ime.packages.MissingFeature

/** The note a successful scheme import shows for what its input methods go without. */
internal object MissingFeatureMessages {
    /** [name] (a file or a scheme) followed by the upstream repository that provides it, when well known. */
    fun named(
        context: Context,
        name: String,
    ): String =
        MissingFeature.repositoryOfPath(name)?.let {
            context.getString(R.string.scheme_import_missing_name_with_repository, name, it)
        } ?: name

    /** One line per missing feature and a closing hint; null when nothing is missing. */
    fun format(
        context: Context,
        features: List<MissingFeature>,
    ): String? {
        if (features.isEmpty()) return null
        val separator = context.getString(R.string.import_path_separator)
        val lines =
            features.map { feature ->
                val names =
                    feature.names.joinToString(separator) { name -> named(context, name) }
                val res =
                    when (feature.kind) {
                        MissingFeature.Kind.REVERSE_LOOKUP -> R.string.scheme_import_missing_lookup
                        MissingFeature.Kind.PRESET_VOCABULARY -> R.string.scheme_import_missing_vocabulary
                        MissingFeature.Kind.OTHER -> R.string.scheme_import_missing_other
                    }
                context.getString(res, names)
            }
        return (lines + context.getString(R.string.scheme_import_missing_hint)).joinToString("\n")
    }
}
