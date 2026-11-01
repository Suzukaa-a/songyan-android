package app.songyan.ime.settings.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bundled notice document is iOS-authored plain text; parsing is what makes it readable. */
class ThirdPartyNoticesParserTest {
    @Test
    fun componentFieldsAreParsedFromTheBulletList() {
        val notices = ThirdPartyNoticesParser.parse(FIXTURE)
        assertEquals(2, notices.components.size)

        val librime = notices.components[0]
        assertEquals(1, librime.number)
        assertEquals("librime 1.17.0", librime.title)
        assertEquals("BSD 3-Clause", librime.licence)
        assertEquals("https://github.com/rime/librime", librime.upstream)
        assertEquals("(c) 2014, RIME Developers", librime.copyright)
        assertTrue(librime.role!!.contains("candidate lookup"))
        assertEquals(listOf("licenses/librime-1.17.0-LICENSE.txt"), librime.fullTextReferences)
    }

    @Test
    fun multiLineBulletsAreJoinedNotTruncated() {
        val librime = ThirdPartyNoticesParser.parse(FIXTURE).components[0]
        assertTrue(librime.role!!.contains("user-dictionary learning and sync"))
    }

    @Test
    fun aComponentWithoutFullTextFileKeepsItsNotes() {
        val gpl = ThirdPartyNoticesParser.parse(FIXTURE).components[1]
        assertEquals(2, gpl.number)
        assertEquals("GPL-3.0", gpl.licence)
        assertEquals(listOf("licenses/GPL-3.0.txt"), gpl.fullTextReferences)
    }

    @Test
    fun theDistributionNoteIsNotMistakenForAComponent() {
        val titles = ThirdPartyNoticesParser.parse(FIXTURE).components.map { it.title }
        assertTrue(titles.none { it.contains("Distribution note") })
    }

    @Test
    fun appendixTextsAreKeptVerbatim() {
        val notices = ThirdPartyNoticesParser.parse(FIXTURE)
        assertEquals(2, notices.appendix.size)
        val bsd = notices.appendix.getValue("licenses/librime-1.17.0-LICENSE.txt")
        assertTrue(bsd.startsWith("BSD 3-Clause License"))
        assertTrue(bsd.contains("Redistribution and use in source and binary forms."))
        assertNull(notices.appendix["licenses/missing.txt"])
    }

    @Test
    fun aHeadingRemarkMovesToTheNotesAndTheLicenceStaysBalanced() {
        val notices =
            """
            1. marisa-trie 0.3.1 (external, pinned)
               - License: BSD 2-Clause OR LGPL-2.1-or-later (dual; see appendix)
               - Role: trie.
            """.trimIndent()
        val component = ThirdPartyNoticesParser.parse(notices).components.single()
        assertEquals("marisa-trie 0.3.1", component.title)
        assertEquals("BSD 2-Clause OR LGPL-2.1-or-later", component.licence)
        assertEquals("(external, pinned)", component.notes)
    }

    private companion object {
        val FIXTURE =
            """
            Third-Party Notices
            ===================

            This application is built on the Rime input method engine.

            1. librime 1.17.0
               - Upstream: https://github.com/rime/librime
               - Pinned: commit 33e78140250125871856cdc5b42ddc6a5fcd3cd4 (release 1.17.0)
               - License: BSD 3-Clause
               - Copyright (c) 2014, RIME Developers
               - Role: input method engine (spelling, segmentation, candidate lookup,
                 user-dictionary learning and sync). Linked statically.
               - Full text: licenses/librime-1.17.0-LICENSE.txt

            2. rime-double-pinyin (schema rules)
               - Upstream: https://github.com/rime/rime-double-pinyin
               - License: GPL-3.0; full text: licenses/GPL-3.0.txt
               - Role: Flypy algebra reference.

            Distribution note
            -----------------
            The engine components are merged into one framework.

            ===== Appendix: Verbatim license texts =====


            ================================================================
            % licenses/librime-1.17.0-LICENSE.txt
            ================================================================

            BSD 3-Clause License

            Redistribution and use in source and binary forms.

            ================================================================
            % licenses/GPL-3.0.txt
            ================================================================

            GNU GENERAL PUBLIC LICENSE
            """.trimIndent()
    }
}
