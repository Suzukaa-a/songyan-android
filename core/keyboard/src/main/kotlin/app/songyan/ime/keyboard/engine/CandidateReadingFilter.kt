package app.songyan.ime.keyboard.engine

import app.songyan.ime.keyboard.display.ReadingInputCoverage
import app.songyan.ime.keyboard.display.SentenceSpelling

/**
 * Read-only, incremental filtering of actual candidates by their engine-proven readings. It is not
 * a complete syllable enumerator and never rewrites the engine's input. The frontier always
 * advances through the unfiltered absolute list, including pages with no matches.
 *
 * Not thread safe: one owner (the keyboard controller) drives it, and every update is tied to the
 * composition revision it was read for.
 */
class CandidateReadingFilter {
    var revision: Int = -1
        private set
    var entries: List<ReadingCandidate> = emptyList()
        private set

    /** The reading syllables chosen so far, oldest first. */
    var prefix: List<String> = emptyList()
        private set

    /**
     * The last chosen reading is only the letters typed so far of a longer syllable (`h` for
     * 好/会), so it matches every syllable it starts.
     */
    var prefixEndsTyped: Boolean = false
        private set
    var hasMore: Boolean = true
        private set
    var readFailed: Boolean = false
        private set

    /**
     * What was actually typed. The engine may rank longer completions (你好吗 for `ni'hao`); their
     * extra syllables are never offered as reading choices.
     */
    var coverage: ReadingInputCoverage? = null
        private set

    val nextIndex: Int get() = entries.size
    val isFiltering: Boolean get() = prefix.isNotEmpty()
    val unclassifiedCount: Int get() = entries.count { !it.readingsComplete }

    /** The candidates the chosen readings leave; every entry while nothing is chosen. */
    val matches: List<ReadingCandidate>
        get() = if (isFiltering) entries.filter(::matchesPrefix) else entries

    private class Choice(
        val value: String,
        val syllable: String,
        val spelledInFull: Boolean,
    )

    private class Rank(
        val group: Int,
        val length: Int,
        val rank: Int,
        val order: Int,
    )

    /**
     * The readings offered for the next syllable. Syllables whose every letter is a typed key come
     * first, longer ones before shorter (`4264` gives `gang`, `hang`, then `ha`), then syllables the
     * scheme reaches another way (an initial standing for the syllable, `mo` for `6`; a derived
     * spelling, `chang` for `4264`), then the letters of a syllable still being typed (`g`/`h` for
     * one last key, not the guessed 好/会/还 readings). Within a group the more common Mandarin
     * syllable leads when every reading is a Mandarin syllable; otherwise the engine's order is
     * kept. Only readings of real candidates are offered, so every choice still filters to
     * something. A choice that already spells every typed key offers no further syllable from a
     * longer completion (你好坏).
     */
    val options: List<String>
        get() {
            val list = choices()
            val common = SyllableOrder.commonRank(list.map { it.syllable })
            val best = LinkedHashMap<String, Rank>()
            list.forEachIndexed { order, choice ->
                val group =
                    when {
                        choice.value != choice.syllable -> 2
                        choice.spelledInFull -> 0
                        else -> 1
                    }
                // Length only says something when the typed keys are known.
                val length = if (group == 0 && coverage != null) choice.syllable.length else 0
                val rank = common[choice.syllable] ?: Int.MAX_VALUE
                val known = best[choice.value]
                if (known == null) {
                    best[choice.value] = Rank(group, length, rank, order)
                } else if (group <= known.group) {
                    best[choice.value] =
                        Rank(group, length, if (group == known.group) minOf(rank, known.rank) else rank, known.order)
                }
            }
            return best.entries
                .sortedWith { left, right ->
                    val a = left.value
                    val b = right.value
                    when {
                        a.group != b.group -> a.group.compareTo(b.group)
                        a.length != b.length -> b.length.compareTo(a.length)
                        a.rank != b.rank -> a.rank.compareTo(b.rank)
                        else -> a.order.compareTo(b.order)
                    }
                }.map { it.key }
        }

    /**
     * Each offered reading with the whole syllable it stands for. Without a trusted key map every
     * reading stays offered, and the plain map still tells which ones the typed keys spell letter
     * for letter.
     */
    private fun choices(): List<Choice> {
        val bound = trustedCoverage()
        val start = bound?.farthestEnd(prefix)
        if (bound != null && start != null && start >= bound.keyCount) return emptyList()
        val literalStart = coverage?.farthestEnd(prefix)
        val result = ArrayList<Choice>()
        for (entry in matches) {
            if (!entry.readingsComplete) continue
            for (syllables in entry.readingPaths.orEmpty()) {
                if (prefix.size !in syllables.indices || !startsWithPrefix(syllables)) continue
                val syllable = syllables[prefix.size]
                if (bound == null || start == null) {
                    val known = coverage
                    val literal = known == null || (literalStart != null && known.spellsInFull(syllable, literalStart))
                    result += Choice(syllable, syllable, literal)
                    continue
                }
                if (!bound.fits(syllables.subList(prefix.size, prefix.size + 1), start)) continue
                result += Choice(bound.typedChoice(syllable, start), syllable, bound.spellsInFull(syllable, start))
            }
        }
        return result
    }

    /**
     * The key map is only trusted when the engine's first complete candidate spells exactly the
     * typed keys; a scheme whose codes are not its readings (for example double pinyin) keeps every
     * engine reading rather than losing real choices.
     */
    private fun trustedCoverage(): ReadingInputCoverage? {
        val bound = coverage ?: return null
        val first = entries.firstOrNull { it.readingsComplete } ?: return null
        return bound.takeIf { first.readingPaths.orEmpty().any { path -> it.fits(path, consumingAll = true) } }
    }

    /** How many of [page] would match the current reading prefix. */
    fun matchCount(page: List<ReadingCandidate>): Int = if (isFiltering) page.count(::matchesPrefix) else page.size

    /**
     * A candidate matches when its reading continues the chosen syllables, or spells their first
     * 1..n syllables (课程 and 可 for a chosen `ke cheng xing`): choosing a reading narrows the
     * list, it does not force a word of exactly that length.
     */
    private fun matchesPrefix(entry: ReadingCandidate): Boolean =
        entry.readingsComplete &&
            entry.readingPaths.orEmpty().any { path -> continues(path, prefix, prefixEndsTyped) }

    /** [path] reads as every chosen syllable (the last one possibly only begun). */
    fun startsWithPrefix(path: List<String>): Boolean =
        path.size >= prefix.size && continues(path, prefix, prefixEndsTyped)

    fun reset(revision: Int) {
        if (this.revision == revision) return
        this.revision = revision
        entries = emptyList()
        prefix = emptyList()
        prefixEndsTyped = false
        hasMore = true
        readFailed = false
        coverage = null
    }

    /** Appends one read page; a page that does not continue the list exactly is ignored. */
    fun append(
        page: List<ReadingCandidate>,
        requested: Int,
        revision: Int,
    ) {
        if (this.revision != revision || requested <= 0) return
        if (!page.withIndex().all { (offset, entry) -> entry.index == entries.size + offset }) return
        entries = entries + page
        hasMore = page.size == requested
        readFailed = false
    }

    fun failed() {
        readFailed = true
    }

    fun setCoverage(value: ReadingInputCoverage?) {
        coverage = value
    }

    /** Chooses [reading] as the next syllable; ignored unless it is a real offered choice. */
    fun select(reading: String) {
        val offered = choices().filter { it.value == reading }
        if (offered.isEmpty()) return
        prefix = prefix + reading
        prefixEndsTyped = offered.any { it.syllable != reading }
    }

    /** Only the last chosen reading can be a begun syllable, so undoing leaves whole ones. */
    fun undoSelection() {
        if (prefix.isNotEmpty()) prefix = prefix.dropLast(1)
        prefixEndsTyped = false
    }

    fun clearSelection() {
        prefix = emptyList()
        prefixEndsTyped = false
    }

    companion object {
        /**
         * [path] starts with [prefix], or is a non-empty leading part of it. With [endsTyped], the
         * last prefix element only has to begin its syllable.
         */
        fun continues(
            path: List<String>,
            prefix: List<String>,
            endsTyped: Boolean = false,
        ): Boolean {
            if (path.isEmpty()) return prefix.isEmpty()
            val count = minOf(path.size, prefix.size)
            for (index in 0 until count) {
                val same = path[index] == prefix[index]
                val begun = endsTyped && index == prefix.size - 1 && path[index].startsWith(prefix[index])
                if (!same && !begun) return false
            }
            return true
        }
    }
}

/** Ordering shared by every rail list. Pure value logic. */
object SyllableOrder {
    /**
     * Mandarin frequency ranks, or none when any syllable is not Mandarin (Cantonese, a scheme's
     * own codes): those lists keep the engine's order.
     */
    fun commonRank(syllables: List<String>): Map<String, Int> {
        val table = SentenceSpelling.commonSyllableRank
        return if (syllables.all { it in table }) table else emptyMap()
    }

    /**
     * The engine's lockable spellings of one typed segment arrive alphabetically. A spelling whose
     * letters are exactly the typed keys (`gang`, `hang` for `4264`) comes before one the scheme
     * derives by dropping or moving letters (`chang` for `hcng`), each group the more common
     * Mandarin syllable first. Nothing is added or removed, so every option is still the engine's.
     */
    fun spellingOptions(
        options: List<String>,
        typedKeys: String,
        keyForLetter: Map<Char, Char>,
    ): List<String> {
        if (keyForLetter.isEmpty() || typedKeys.isEmpty()) return options
        val common = commonRank(options)

        fun literal(option: String): Boolean {
            val keys = option.lowercase().mapNotNull { keyForLetter[it] }
            return keys.size == option.length && String(keys.toCharArray()) == typedKeys
        }
        return options
            .withIndex()
            .sortedWith { left, right ->
                val a = literal(left.value)
                val b = literal(right.value)
                if (a != b) {
                    if (a) -1 else 1
                } else {
                    val x = common[left.value] ?: Int.MAX_VALUE
                    val y = common[right.value] ?: Int.MAX_VALUE
                    if (x != y) x.compareTo(y) else left.index.compareTo(right.index)
                }
            }.map { it.value }
    }

    /** The typed keys a report's segment covers; null when it does not lie in [input] (UTF-8 bytes). */
    fun typedKeys(
        report: AmbiguitySpellingsReport,
        input: String,
    ): String? {
        val bytes = input.toByteArray(Charsets.UTF_8)
        val segment = report.segment
        if (segment.first < 0 || segment.last + 1 > bytes.size || segment.isEmpty()) return null
        return String(bytes, segment.first, segment.last + 1 - segment.first, Charsets.UTF_8)
    }
}

/**
 * Selectable compact-key spellings come only from live engine reports or complete candidate
 * readings. Display-only sentence spelling is deliberately not an input.
 */
data class SpellingRailChoices(
    val canUndoLock: Boolean = false,
    val lockedOption: String? = null,
    val lockOptions: List<String> = emptyList(),
    val isFiltering: Boolean = false,
    val readingPrefix: List<String> = emptyList(),
    val readings: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = !canUndoLock && lockOptions.isEmpty() && !isFiltering && readings.isEmpty()

    companion object {
        fun resolve(
            eligible: Boolean,
            input: String,
            revision: Int,
            report: AmbiguitySpellingsReport?,
            reportRevision: Int,
            lockedOption: String? = null,
            lockedInput: String? = null,
            readings: CandidateReadingFilter,
        ): SpellingRailChoices {
            if (!eligible || input.isEmpty()) return SpellingRailChoices()
            var canUndoLock = false
            var locked: String? = null
            var lockOptions = emptyList<String>()
            if (!lockedOption.isNullOrEmpty() && lockedInput == input) {
                canUndoLock = true
                locked = lockedOption
            } else if (report != null &&
                reportRevision == revision &&
                report.revisionInput == input &&
                report.isComplete &&
                report.isAmbiguous &&
                report.isLockable &&
                report.segment.first >= 0 &&
                !report.segment.isEmpty() &&
                report.segment.last + 1 <= input.toByteArray(Charsets.UTF_8).size
            ) {
                val coverage = readings.coverage
                val typed = coverage?.let { SyllableOrder.typedKeys(report, input) }
                lockOptions =
                    if (coverage != null && typed != null) {
                        SyllableOrder.spellingOptions(report.options, typed, coverage.keyForLetter)
                    } else {
                        report.options
                    }
            }
            var filtering = false
            var prefix = emptyList<String>()
            var offered = emptyList<String>()
            if (readings.revision == revision) {
                filtering = readings.isFiltering
                prefix = readings.prefix
                // Prefer a real engine rewrite when the same reading is available both ways.
                val locks = lockOptions.toSet()
                offered = readings.options.filter { it !in locks }
            }
            return SpellingRailChoices(canUndoLock, locked, lockOptions, filtering, prefix, offered)
        }
    }
}
