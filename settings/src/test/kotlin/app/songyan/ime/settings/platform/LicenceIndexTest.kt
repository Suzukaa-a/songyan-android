package app.songyan.ime.settings.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LicenceIndexTest {
    @Test
    fun textsAreResolvedByFileName() {
        val index = LicenceIndexBuilder.build(FIXTURE, assets = listOf(BSL, GPL))
        val librime = index.components.first { it.title.startsWith("librime") }
        assertEquals(1, librime.texts.size)
        assertEquals("librime 1.17.0", librime.texts[0].label)
        assertTrue(librime.texts[0].text.startsWith("BSD 3-Clause License"))
    }

    @Test
    fun aReferenceToABundledFileIsResolvedFromTheAssets() {
        val index = LicenceIndexBuilder.build(FIXTURE, assets = listOf(BSL, GPL))
        val doublePinyin = index.components.first { it.title.startsWith("rime-double-pinyin") }
        assertEquals(1, doublePinyin.texts.size)
        assertEquals("GPL-3.0", doublePinyin.texts[0].label)
    }

    @Test
    fun unusedFilesStayReadableAsOtherTexts() {
        val index = LicenceIndexBuilder.build(FIXTURE, assets = listOf(BSL, GPL, LGPL))
        assertEquals(listOf("LGPL-3.0"), index.extraTexts.map { it.label })
    }

    @Test
    fun aMissingBundledPathFallsBackToTheNamedLicence() {
        val notices =
            """
            1. rime-prelude
               - License: LGPL-3.0
               - Full text: PackageManager_PackageManager.bundle/RimePrelude/LICENSE
            """.trimIndent()
        val index = LicenceIndexBuilder.build(notices, assets = listOf(LGPL))
        assertEquals(1, index.components[0].texts.size)
        assertEquals("LGPL-3.0", index.components[0].texts[0].label)
    }

    @Test
    fun aComponentNamedOnlyByTheAppendixFindsItsText() {
        val notices =
            """
            1. librime 1.17.0
               - License: BSD 3-Clause

            ===== Appendix: Verbatim license texts =====

            ================================================================
            % licenses/librime-1.17.0-LICENSE.txt
            ================================================================

            BSD 3-Clause License

            Redistribution and use in source and binary forms.
            """.trimIndent()
        val index = LicenceIndexBuilder.build(notices, assets = emptyList())
        assertEquals(1, index.components[0].texts.size)
        assertEquals("librime 1.17.0", index.components[0].texts[0].label)
    }

    @Test
    fun aComponentWithoutATextIsKeptWithNoTexts() {
        val notices =
            """
            1. mystery-lib 1.0
               - License: MIT
            """.trimIndent()
        val index = LicenceIndexBuilder.build(notices, assets = emptyList())
        assertEquals(1, index.components.size)
        assertTrue(index.components[0].texts.isEmpty())
    }

    @Test
    fun aMissingVersionIsTakenFromThePinnedLine() {
        val notices =
            """
            1. yaml-cpp (master snapshot after 0.8.0)
               - Pinned: commit 2f86d13775d119edbb69af52e5f566fd65c6953b (2025-04-29)
               - License: MIT

            2. Lua
               - Pinned: tag ver.5.4.9
               - License: MIT

            3. librime 1.17.0
               - Pinned: commit 33e78140250125871856cdc5b42ddc6a5fcd3cd4 (release 1.17.0)
               - License: BSD 3-Clause

            4. X11 keysym definitions
               - License: MIT
            """.trimIndent()
        val titles = LicenceIndexBuilder.build(notices, assets = emptyList()).components.map { it.displayTitle }
        assertEquals(listOf("yaml-cpp 2f86d13", "Lua 5.4.9", "librime 1.17.0", "X11 keysym definitions"), titles)
    }

    @Test
    fun noFileNameReachesTheScreen() {
        assertEquals("librime 1.17.0", LicenceLabels.textLabel("librime-1.17.0-LICENSE.txt"))
        assertEquals("boost 1.89.0", LicenceLabels.textLabel("boost-1.89.0-LICENSE_1_0.txt"))
        assertEquals("marisa-trie 0.3.1", LicenceLabels.textLabel("marisa-trie-0.3.1-COPYING.md"))
        assertEquals("GPL-3.0", LicenceLabels.textLabel("GPL-3.0.txt"))
        assertEquals("X11-keysym", LicenceLabels.textLabel("X11-keysym-NOTICE.txt"))
        assertEquals("libyaml", LicenceLabels.textLabel("libyaml-LICENSE.txt"))
    }

    private companion object {
        val BSL = LicenceAsset("legal/licenses/librime-1.17.0-LICENSE.txt", "BSD 3-Clause License\ntext")
        val GPL = LicenceAsset("legal/licenses/GPL-3.0.txt", "GNU GENERAL PUBLIC LICENSE\ntext")
        val LGPL = LicenceAsset("legal/licenses/LGPL-3.0.txt", "GNU LESSER GENERAL PUBLIC LICENSE\ntext")

        val FIXTURE =
            """
            1. librime 1.17.0
               - License: BSD 3-Clause
               - Full text: licenses/librime-1.17.0-LICENSE.txt

            2. rime-double-pinyin (schema rules)
               - License: GPL-3.0; full text: licenses/GPL-3.0.txt

            ===== Appendix: Verbatim license texts =====

            ================================================================
            % licenses/librime-1.17.0-LICENSE.txt
            ================================================================

            BSD 3-Clause License

            Redistribution and use in source and binary forms.
            """.trimIndent()
    }
}
