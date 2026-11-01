package app.songyan.ime.keyboard.display

import app.songyan.ime.keyboard.engine.ReadingCandidate

/**
 * Spells a candidate that has no provable reading of its own (a sentence the engine joined from
 * several words, 一金贵呀) for a numeric keypad field. Pure value logic.
 */
object SentenceSpelling {
    /** Syllables in a keypad field are joined like mainstream 9-key pinyin (`ni'hao`). */
    const val SEPARATOR = "'"

    /**
     * [segments] are the engine's syllable segments of the highlighted code (`94 546 484 92`). Every
     * segment shows letters, so each press stays visible: the reading proven for the text's
     * character at that position (this code's candidates, then readings proven earlier in the
     * session, [knownReadings]; only when the text has one character per segment), else the
     * best-ranked proven syllable spelling those keys, else the most common Mandarin syllable that
     * does. A segment that only begins a syllable shows the letters of its keys (`ni'h`); the
     * engine's swapped-key correction (`442` -> 还) shows the syllable it chose. Only keys no syllable
     * can spell show as the keys themselves. Display only: commits never read this. Null only when
     * the inputs cannot describe a keypad code at all.
     */
    fun spell(
        segments: List<String>,
        text: String,
        entries: List<ReadingCandidate>,
        keyForLetter: Map<Char, Char>,
        knownReadings: Map<Char, List<String>> = emptyMap(),
    ): String? {
        if (segments.isEmpty() || keyForLetter.isEmpty()) return null
        if (!segments.all { segment -> segment.isNotEmpty() && segment.all { it.isDigit() } }) return null
        val characters = text.toList()
        val aligned = characters.size == segments.size
        val byCharacter = HashMap<Char, MutableList<String>>()
        val vocabulary = ArrayList<String>()
        val known = HashSet<String>()
        for (entry in entries) {
            if (!entry.readingsComplete) continue
            for (path in entry.readingPaths.orEmpty()) {
                for (syllable in path) if (known.add(syllable)) vocabulary += syllable
                if (entry.text.length != path.size) continue
                entry.text.forEachIndexed { index, character ->
                    byCharacter.getOrPut(character) { ArrayList() } += path[index]
                }
            }
        }
        val common = keyIndex(keyForLetter)
        val spelled = ArrayList<String>(segments.size)
        for ((offset, segment) in segments.withIndex()) {
            val last = offset == segments.size - 1
            val character = if (aligned) characters[offset] else null
            val proven = character?.let { byCharacter[it] }.orEmpty()
            val remembered = character?.let { knownReadings[it] }.orEmpty()

            fun keys(syllable: String): String? {
                val mapped = syllable.lowercase().mapNotNull { keyForLetter[it] }
                return if (mapped.size == syllable.length) String(mapped.toCharArray()) else null
            }

            fun exact(syllable: String): Boolean = keys(syllable) == segment

            fun begun(syllable: String): Boolean = keys(syllable)?.startsWith(segment) == true

            fun transposed(syllable: String): Boolean =
                keys(syllable)?.let { it.length == segment.length && it.toList().sorted() == segment.toList().sorted() }
                    ?: false
            // The last segment may still be being typed. A finished-looking last segment (`6` for
            // 哦) is still the whole syllable more often than the start of an unrelated common one.
            val fits: (String) -> Boolean = if (last) ::begun else ::exact
            val evidence = proven + remembered
            val sorted = String(segment.toList().sorted().toCharArray())
            val chosen =
                proven.firstOrNull(fits)
                    ?: remembered.firstOrNull(fits)
                    ?: vocabulary.firstOrNull(fits)
                    ?: common.exact[segment]
                    ?: (if (last) common.begun[segment] else null)
                    ?: evidence.firstOrNull(::begun)
                    ?: common.begun[segment]
                    ?: evidence.firstOrNull(::transposed)
                    ?: common.transposed[sorted]
            spelled +=
                when {
                    chosen == null -> segment
                    begun(chosen) && !exact(chosen) -> chosen.take(segment.length)
                    else -> chosen
                }
        }
        return spelled.joinToString(SEPARATOR)
    }

    /**
     * [COMMON_SYLLABLES] by the keys that spell them under one key map, the most common syllable
     * first: whole keys, every leading part of them, and the keys in sorted order (swapped-key
     * corrections). Built once per map; every lookup is a hash hit.
     */
    private class KeyIndex(
        val exact: Map<String, String>,
        val begun: Map<String, String>,
        val transposed: Map<String, String>,
    )

    private val keyIndexes = HashMap<Map<Char, Char>, KeyIndex>()

    @Synchronized
    private fun keyIndex(keyForLetter: Map<Char, Char>): KeyIndex {
        keyIndexes[keyForLetter]?.let { return it }
        val exact = HashMap<String, String>()
        val begun = HashMap<String, String>()
        val transposed = HashMap<String, String>()
        for (syllable in COMMON_SYLLABLES) {
            val mapped = syllable.mapNotNull { keyForLetter[it] }
            if (mapped.size != syllable.length) continue
            val keys = String(mapped.toCharArray())
            exact.putIfAbsent(keys, syllable)
            transposed.putIfAbsent(String(keys.toList().sorted().toCharArray()), syllable)
            for (length in 1..keys.length) begun.putIfAbsent(keys.take(length), syllable)
        }
        val index = KeyIndex(exact, begun, transposed)
        if (keyIndexes.size > MAX_CACHED_KEY_MAPS) keyIndexes.clear()
        keyIndexes[keyForLetter] = index
        return index
    }

    private const val MAX_CACHED_KEY_MAPS = 8

    /** Position of each syllable in [COMMON_SYLLABLES]; lower is more common. */
    val commonSyllableRank: Map<String, Int> by lazy {
        val ranks = HashMap<String, Int>(COMMON_SYLLABLES.size * 2)
        COMMON_SYLLABLES.forEachIndexed { index, syllable -> ranks.putIfAbsent(syllable, index) }
        ranks
    }

    /**
     * Every Mandarin pinyin syllable (ü as `v`; also interjections such as `n`, `m`), most used
     * first, so keys several syllables share (`64` ni/mi, `424` hai/gai) fall back to the likelier.
     */
    val COMMON_SYLLABLES: List<String> =
        """
        de shi yi bu le zai ren you wo ta zhe ge men lai dao shang da wei he guo zhong sheng zi hui yao ye jiu ke
        dui chu xia ni neng jia nian duo fa hao zuo xue fen zhi xing qi li mei ba fang ji guan dong yu xian kan qu
        xin hou wu na mian ming tong gong xiang dian zhu qian jian jin gao ying cheng ru wen cong gei yong ben ran
        shen bian dang huo ding shuo bie zheng hai xiao bei san tian quan mo ma ai an o lu liang si ti chang mu biao
        jiao zhao zhan ce dan hua shou xi tou jie ju xu wang yuan yue zou zhun qing ri lao kai gai hen jing kou lun
        ling bao pin tiao ci che la ruo qie zao suo shu ban tai mao lei nan hu pao jun shan yin bai jue mi feng wan
        nei kuai po bi die su min chi zhuang du fu rang zu tu cai shui chuan deng dou song guang huan zhou qiu ya
        xie ze wa za pi gu e ou ei er n m ng a ang ao en eng bang beng bin bing bo pa pai pan pang pei pen peng pian
        piao pie ping pou pu mai man mang me meng miao mie miu mou fan fei fo fou dai dei den di dia diao diu duan
        dun tan tang tao te tei teng tie ting tuan tui tun tuo nai nang nao ne nen niang niao nie nin ning niu nong
        nou nu nuan nun nuo nv nve nue lan lang leng lia lian liao lie lin liu lo long lou luan luo lv lve lue ga
        gan gang gen geng gou gua guai gui gun ka kang kao kei ken keng kong ku kua kuan kuang kui kun kuo ha han
        hang hei heng hong huai huang hun jiang jiong juan qia qiang qiao qin qiong que qun xiong xiu xuan xun zha
        zhai zhang zhei zhen zhua zhuai zhuan zhui zhuo cha chai chan chao chen chong chou chua chuai chuang chui
        chun chuo sha shai shao she shei shua shuai shuan shuang shun rao re reng rong rou rua ruan rui run zan zang
        zei zen zeng zong zuan zui zun ca can cang cao cen ceng cou cu cuan cui cun cuo sa sai sang sao se sen seng
        sou suan sui sun yan yang yo yun wai weng hm hng
        """.trim().split(Regex("\\s+"))
}

/**
 * Typed compact keys that a reading path must fit into. A syllable is typed in full, abbreviated to
 * its initial (`n`, `zh`/`z`), or still being typed when it reaches the end of the input; a
 * separator ends a syllable. Pure value.
 */
class ReadingInputCoverage private constructor(
    private val keys: CharArray,
    /** The key that types each letter, from the layout's own key labels. */
    val keyForLetter: Map<Char, Char>,
    /** Keys that type one letter exactly (`CompactFold.exactTokens`, token -> letter). */
    val exactKeys: Map<Char, Char>,
) {
    /** The typed keys, lowercased. */
    val input: String get() = String(keys)

    /** Whether [syllables] fit the typed keys, all of them when [consumingAll]. */
    fun fits(
        syllables: List<String>,
        consumingAll: Boolean,
    ): Boolean {
        val reachable = reachable(syllables, 0) ?: return false
        return !consumingAll || keys.size in reachable
    }

    /** Whether [syllables] fit the keys that follow position [start]. */
    fun fits(
        syllables: List<String>,
        start: Int,
    ): Boolean = reachable(syllables, start) != null

    /**
     * How [syllable] reads over the keys from [start]: whole when it fits, otherwise the letters
     * typed so far when it runs to the end of the input (`hao` over one last key reads `h`); an
     * abbreviation inside the input keeps the whole syllable.
     */
    fun typedChoice(
        syllable: String,
        start: Int,
    ): String {
        val position = skipSeparators(start)
        val letters = syllable.lowercase()
        val remaining = keys.size - position
        if (matches(letters, letters.length, position)) return syllable
        if (remaining <= 0 || remaining >= letters.length) return syllable
        return if (matches(letters, remaining, position)) letters.take(remaining) else syllable
    }

    /** Every letter of [syllable] was typed from [start], not just its initial. */
    fun spellsInFull(
        syllable: String,
        start: Int,
    ): Boolean {
        val letters = syllable.lowercase()
        return matches(letters, letters.length, skipSeparators(start))
    }

    /** The farthest key position any parse of [syllables] reaches (after separators); null when they do not fit. */
    fun farthestEnd(syllables: List<String>): Int? = reachable(syllables, 0)?.max()

    /** The key count; positions run `0..keyCount`. */
    val keyCount: Int get() = keys.size

    private fun reachable(
        syllables: List<String>,
        origin: Int,
    ): Set<Int>? {
        var reachable: Set<Int> = setOf(skipSeparators(origin))
        for (syllable in syllables) {
            val letters = syllable.lowercase()
            if (letters.isEmpty()) return null
            val lengths = sortedSetOf(1, letters.length)
            if (letters.length > 2 && letters[1] == 'h' && letters[0] in "zcs") lengths += 2
            val next = HashSet<Int>()
            for (start in reachable) {
                for (length in lengths + (keys.size - start)) {
                    if (length > 0 && length <= letters.length && matches(letters, length, start)) {
                        next += skipSeparators(start + length)
                    }
                }
            }
            if (next.isEmpty()) return null
            reachable = next
        }
        return reachable
    }

    /**
     * The letters of each syllable that the typed keys actually cover (`bu guo` for three keys ->
     * `bu`, `g`), preferring whole syllables. Null when no parse consumes every typed key, e.g. a
     * candidate covering only part of the input.
     */
    fun typedSpelling(syllables: List<String>): List<String>? {
        if (syllables.isEmpty()) return null
        val failed = HashSet<Long>()

        fun search(
            index: Int,
            start: Int,
        ): List<String>? {
            val position = skipSeparators(start)
            if (index >= syllables.size) return if (position == keys.size) emptyList() else null
            val key = index.toLong() shl 32 or position.toLong()
            if (key in failed) return null
            val letters = syllables[index].lowercase()
            val lengths = sortedSetOf(1, letters.length, keys.size - position)
            if (letters.length > 2 && letters[1] == 'h' && letters[0] in "zcs") lengths += 2
            for (length in lengths.descendingSet()) {
                if (length <= 0 || length > letters.length) continue
                if (!matches(letters, length, position)) continue
                val rest = search(index + 1, position + length) ?: continue
                return listOf(letters.take(length)) + rest
            }
            failed += key
            return null
        }
        return search(0, 0)
    }

    /**
     * [typedSpelling] as one string: syllables meet with [joiner], except where the user typed a
     * separator between them, which shows as `'`, and a separator typed last stays at the end.
     */
    fun typedText(
        syllables: List<String>,
        joiner: String,
    ): String? {
        val typed = typedSpelling(syllables) ?: return null
        val text = StringBuilder()
        var position = 0
        for ((index, letters) in typed.withIndex()) {
            val start = skipSeparators(position)
            if (index > 0) text.append(if (start > position) SEPARATOR.toString() else joiner)
            text.append(letters)
            position = start + letters.length
        }
        if (position < keys.size) text.append(SEPARATOR)
        return text.toString()
    }

    private fun matches(
        letters: String,
        length: Int,
        start: Int,
    ): Boolean {
        if (start + length > keys.size) return false
        for (offset in 0 until length) {
            val key = keys[start + offset]
            val letter = letters[offset]
            if (key == SEPARATOR) return false
            if (key != letter && keyForLetter[letter] != key && exactKeys[key] != letter) return false
        }
        return true
    }

    private fun skipSeparators(position: Int): Int {
        var index = position
        while (index < keys.size && keys[index] == SEPARATOR) index++
        return index
    }

    companion object {
        const val SEPARATOR: Char = '\''

        fun of(
            input: String,
            keyForLetter: Map<Char, Char>,
            exactKeys: Map<Char, Char> = emptyMap(),
        ): ReadingInputCoverage? {
            if (input.isEmpty() || keyForLetter.isEmpty()) return null
            return ReadingInputCoverage(input.lowercase().toCharArray(), keyForLetter, exactKeys)
        }
    }
}
