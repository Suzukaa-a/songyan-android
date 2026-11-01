package app.songyan.ime.keyboard.engine

import app.songyan.ime.keyboard.layout.BuiltinTemplateSchemas

/**
 * A clearly labeled offline engine over an embedded demo vocabulary.
 *
 * It exists so the UI flow, the onboarding trial field and the unit tests run without librime; it
 * produces no learning data and connects to nothing. Because its candidates are hand-written, the UI
 * must label [EngineBackendKind.DEMO_SIMULATOR] candidates as demo data and must never present them
 * as real Rime candidates.
 *
 * Demo semantics: keys accumulate the current layout's code (26-key `nihao`, 14-key `bugc`,
 * 9-key `64426`); candidates are the demo words whose full code starts with the typed code; space
 * and return confirm the first candidate, or commit the raw code when nothing matches.
 */
class DemoEngine : EngineBackend {
    override val kind: EngineBackendKind = EngineBackendKind.DEMO_SIMULATOR
    override val inputUnavailableReason: String? = null
    override val availableSchemas = emptyList<KeyboardSchemaDescriptor>()

    /** No candidate spelling probing: the demo vocabulary has no syllable decode. */
    override var spellingDemand: (() -> Boolean)? = null

    private var schemaID = BuiltinTemplateSchemas.QUANPIN
    private var encoding = LayoutCodeProjector.Encoding.FULL_PINYIN
    private var composedCode = ""

    /** State produced by the last operation; [currentSnapshot] reads it without replaying a commit. */
    private var state = EngineSnapshot()

    override suspend fun setup(
        sharedDirectory: String,
        userDirectory: String,
    ) {
        // The demo engine does not deploy; it only records the directories for the UI.
        sharedDirectoryPath = sharedDirectory
        userDirectoryPath = userDirectory
    }

    private var sharedDirectoryPath: String? = null
    private var userDirectoryPath: String? = null

    override suspend fun selectSchema(schemaID: String) {
        encoding = LayoutCodeProjector.Encoding.forSchemaID(schemaID)
            ?: throw EngineBackendError.UnsupportedSchema(schemaID)
        this.schemaID = schemaID
        composedCode = ""
        state = produce()
    }

    override val statusSummary: String
        get() = "demo engine · schema $schemaID"

    override suspend fun processKey(keyValue: String): EngineSnapshot =
        apply(
            when (keyValue) {
                EngineBackend.BACKSPACE_KEY -> {
                    if (composedCode.isNotEmpty()) composedCode = composedCode.dropLast(1)
                    produce()
                }

                EngineBackend.SPACE_KEY -> confirmOrCommitRaw(emptyCommitText = " ")

                EngineBackend.RETURN_KEY -> confirmOrCommitRaw(emptyCommitText = "\n")

                EngineBackend.ESC_KEY -> {
                    composedCode = ""
                    produce()
                }

                else -> {
                    if (alphabetContains(keyValue)) composedCode += keyValue
                    produce()
                }
            },
        )

    override suspend fun selectCandidate(index: Int): EngineSnapshot {
        val match = computeMatches().getOrNull(index) ?: throw EngineBackendError.NotReady()
        composedCode = composedCode.drop(match.code.length)
        return apply(produce(committed = match.word.text))
    }

    override suspend fun nextPage(): EngineSnapshot = currentSnapshot()

    override suspend fun previousPage(): EngineSnapshot = currentSnapshot()

    /** Reads the current state; a commit belongs to the operation that produced it, not to a read. */
    override fun currentSnapshot(): EngineSnapshot = state.copy(committed = "")

    override suspend fun shutdown() {
        composedCode = ""
        sharedDirectoryPath = null
        userDirectoryPath = null
        state = EngineSnapshot()
    }

    private fun apply(next: EngineSnapshot): EngineSnapshot {
        state = next
        return next
    }

    private fun alphabetContains(value: String): Boolean = value.length == 1 && encoding.accepts(value[0])

    private fun computeMatches(): List<Match> {
        if (composedCode.isEmpty()) return emptyList()
        val matches =
            DEMO_VOCABULARY.mapNotNull { word ->
                val code = LayoutCodeProjector.composeCode(word.syllables, encoding)
                if (code.isEmpty() || !code.startsWith(composedCode)) null else Match(word, code)
            }
        // A whole-word hit ranks first (the whole code typed); the rest keep dictionary order.
        return matches
            .sortedWith(compareBy({ it.code.length != composedCode.length }, { it.code.length }))
            .take(9)
    }

    private fun confirmOrCommitRaw(emptyCommitText: String): EngineSnapshot {
        if (composedCode.isEmpty()) return produce(committed = emptyCommitText)
        val matches = computeMatches()
        if (matches.isEmpty()) {
            val raw = composedCode
            composedCode = ""
            return produce(committed = raw)
        }
        val match = matches[0]
        composedCode = composedCode.drop(match.code.length)
        return produce(committed = match.word.text)
    }

    private fun produce(committed: String = ""): EngineSnapshot {
        val matches = computeMatches()
        val candidates =
            matches.mapIndexed { offset, match ->
                // The comment shows the word's full input code, spaced per syllable, for the demo.
                Candidate(
                    index = offset,
                    text = match.word.text,
                    comment = match.code.map { it.toString() }.joinToString(" "),
                )
            }
        val isComposing = composedCode.isNotEmpty()
        return EngineSnapshot(
            schemaID = schemaID,
            committed = committed,
            candidates = candidates,
            preedit = composedCode,
            rawInput = composedCode,
            isComposing = isComposing,
            highlightedIndex = if (isComposing && candidates.isNotEmpty()) 0 else -1,
            pageSize = if (candidates.isEmpty()) 0 else candidates.size,
            preeditSelEnd = composedCode.toByteArray(Charsets.UTF_8).size,
            preeditCaretByte = composedCode.toByteArray(Charsets.UTF_8).size,
            preeditSelection =
                app.songyan.ime.keyboard.engine
                    .TextSelection(composedCode.length, 0),
        )
    }

    private data class Match(
        val word: DemoWord,
        val code: String,
    )

    companion object {
        /** Demo vocabulary entry: word form plus canonical pinyin syllables. */
        data class DemoWord(
            val text: String,
            val syllables: List<String>,
        )

        /**
         * The 43 multi-character words of the built-in dictionary fixture; original data, used by the
         * demo engine only. Real candidates and learning always come from the Rime bridge.
         */
        val DEMO_VOCABULARY: List<DemoWord> =
            listOf(
                DemoWord("你好", listOf("ni", "hao")),
                DemoWord("我们", listOf("wo", "men")),
                DemoWord("中国", listOf("zhong", "guo")),
                DemoWord("中国人", listOf("zhong", "guo", "ren")),
                DemoWord("大家", listOf("da", "jia")),
                DemoWord("同学", listOf("tong", "xue")),
                DemoWord("学习", listOf("xue", "xi")),
                DemoWord("工作", listOf("gong", "zuo")),
                DemoWord("手机", listOf("shou", "ji")),
                DemoWord("电脑", listOf("dian", "nao")),
                DemoWord("键盘", listOf("jian", "pan")),
                DemoWord("公交车", listOf("gong", "jiao", "che")),
                DemoWord("音乐", listOf("yin", "yue")),
                DemoWord("月亮", listOf("yue", "liang")),
                DemoWord("学习拼音", listOf("xue", "xi", "pin", "yin")),
                DemoWord("双拼实验", listOf("shuang", "pin", "shi", "yan")),
                DemoWord("白键盘", listOf("bai", "jian", "pan")),
                DemoWord("银行", listOf("yin", "hang")),
                DemoWord("长大", listOf("zhang", "da")),
                DemoWord("长度", listOf("chang", "du")),
                DemoWord("快乐", listOf("kuai", "le")),
                DemoWord("行走", listOf("xing", "zou")),
                DemoWord("行列", listOf("hang", "lie")),
                DemoWord("重要", listOf("zhong", "yao")),
                DemoWord("重复", listOf("chong", "fu")),
                DemoWord("还是", listOf("hai", "shi")),
                DemoWord("归还", listOf("gui", "huan")),
                DemoWord("绿色", listOf("lv", "se")),
                DemoWord("女孩", listOf("nv", "hai")),
                DemoWord("二月", listOf("er", "yue")),
                DemoWord("爱好", listOf("ai", "hao")),
                DemoWord("平安", listOf("ping", "an")),
                DemoWord("西安", listOf("xi", "an")),
                DemoWord("帮忙", listOf("bang", "mang")),
                DemoWord("声音", listOf("sheng", "yin")),
                DemoWord("明天", listOf("ming", "tian")),
                DemoWord("生活", listOf("sheng", "huo")),
                DemoWord("健康", listOf("jian", "kang")),
                DemoWord("长城", listOf("chang", "cheng")),
                DemoWord("长江", listOf("chang", "jiang")),
                DemoWord("成长", listOf("cheng", "zhang")),
                DemoWord("山水", listOf("shan", "shui")),
                DemoWord("上车", listOf("shang", "che")),
            )
    }
}
