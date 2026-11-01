package app.songyan.ime.content

import android.content.Context
import app.songyan.ime.R
import java.text.NumberFormat

/** The counts of a Rime dictionary file merge, worded once for every place that reports one. */
internal object LearningFilesText {
    fun dictionaries(
        context: Context,
        count: Int,
    ): String = context.resources.getQuantityString(R.plurals.learning_files_dictionaries, count, count)

    fun entries(
        context: Context,
        count: Long,
    ): String {
        val formatted = NumberFormat.getIntegerInstance().format(count)
        val quantity = count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return context.resources.getQuantityString(R.plurals.learning_files_entries, quantity, formatted)
    }

    fun merged(
        context: Context,
        dictionaryCount: Int,
        entryCount: Long,
    ): String =
        context.getString(
            R.string.learning_files_merged,
            dictionaries(context, dictionaryCount),
            entries(context, entryCount),
        )
}
