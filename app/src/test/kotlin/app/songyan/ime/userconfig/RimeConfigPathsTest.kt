package app.songyan.ime.userconfig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Document ids of the Rime configuration provider never reach outside their folders. */
class RimeConfigPathsTest {
    @Test
    fun theFoldersAndTheirFilesParse() {
        assertEquals(RimeConfigPaths.Area.ROOT, RimeConfigPaths.parse("root")?.area)
        assertEquals(listOf("rime_ice.custom.yaml"), RimeConfigPaths.parse("edit/rime_ice.custom.yaml")?.segments)
        assertEquals(listOf("lua", "date.lua"), RimeConfigPaths.parse("active/lua/date.lua")?.segments)
        assertEquals(
            listOf("scheme-0123456789abcdef", "default.yaml"),
            RimeConfigPaths.parse("sources/scheme-0123456789abcdef/default.yaml")?.segments,
        )
    }

    @Test
    fun theUsersFolderMirrorsTheSourcesLayout() {
        assertEquals(listOf("lua", "date.lua"), RimeConfigPaths.parse("edit/lua/date.lua")?.segments)
        assertEquals(listOf("lua"), RimeConfigPaths.parse("edit/lua")?.segments)
        assertNull("a script is never made by a file manager", RimeConfigPaths.newFileName("date.lua", "text/plain"))
        assertNull(RimeConfigPaths.newFileName("date", "application/x-lua"))
    }

    @Test
    fun traversalAndOddSegmentsAreRefused() {
        for (id in listOf(
            "",
            "edit/../applied/x.yaml",
            "active/../../../shared_prefs/x.xml",
            "sources/..",
            "sources/a//b",
            "active/.",
            "active/a\\..\\b",
            "active/a\u0000b",
            "edit/",
            "elsewhere/x.yaml",
            "/edit/x.yaml",
            "edit/build/x.yaml",
            "edit/lua/../x.yaml",
            "edit/lua/.hidden.lua",
            "edit/a/b/c/d/e/x.yaml",
            "edit/.hidden.yaml",
            "a".repeat(2000),
        )) {
            assertNull(id, RimeConfigPaths.parse(id))
        }
    }

    @Test
    fun aResolvedFileStaysInsideItsFolderEvenThroughALink() {
        val root = Files.createTempDirectory("paths").toFile()
        val base = File(root, "source").also { it.mkdirs() }
        File(base, "lua").mkdirs()
        File(base, "lua/date.lua").writeText("x")
        val outside = File(root, "secret").also { it.mkdirs() }
        Files.createSymbolicLink(File(base, "escape").toPath(), outside.toPath())

        assertNotNull(RimeConfigPaths.inside(base, listOf("lua", "date.lua")))
        assertNull(RimeConfigPaths.inside(base, listOf("escape")))
        assertNull(RimeConfigPaths.inside(base, listOf("..", "secret")))
        assertNull("the app's own files are not shown", RimeConfigPaths.inside(base, listOf("songyan-source.json")))
        assertNull(RimeConfigPaths.inside(base, listOf(".previous")))
        assertNull(RimeConfigPaths.inside(base, emptyList()))
    }

    @Test
    fun childrenAreTheDocumentsBelowAFolderOnly() {
        assertTrue(RimeConfigPaths.isChild("root", "edit/a.yaml"))
        assertTrue(RimeConfigPaths.isChild("edit", "edit/a.yaml"))
        assertTrue(RimeConfigPaths.isChild("active", "active/lua/x.lua"))
        assertFalse(RimeConfigPaths.isChild("edit", "editor/a.yaml"))
        assertFalse(RimeConfigPaths.isChild("active", "sources/x"))
        assertFalse(RimeConfigPaths.isChild("edit", "edit/../x.yaml"))
    }

    @Test
    fun aNewFileGetsATextNameAndNeverReplacesAnother() {
        assertEquals("rime_ice.custom.yaml", RimeConfigPaths.newFileName("rime_ice.custom.yaml", "text/plain"))
        assertEquals("custom_phrase.txt", RimeConfigPaths.newFileName("custom_phrase", "text/plain"))
        assertEquals("default.custom.yaml", RimeConfigPaths.newFileName("default.custom", "application/x-yaml"))
        assertNull(RimeConfigPaths.newFileName("photo", "image/png"))
        assertNull(RimeConfigPaths.newFileName("../x", "text/plain"))
        assertNull("the deployment's own file", RimeConfigPaths.newFileName("songyan_imported.dict.yaml", "text/plain"))

        val taken = setOf("rime_ice.custom.yaml", "rime_ice.custom-2.yaml")
        assertEquals("rime_ice.custom-3.yaml", RimeConfigPaths.unique("rime_ice.custom.yaml") { it in taken })
        assertEquals("a.txt", RimeConfigPaths.unique("a.txt") { false })
        assertNull(RimeConfigPaths.unique("a.txt") { true })
    }

    @Test
    fun encodedAndNulIdsNeverResolveOutsideTheirFolder() {
        val base = Files.createTempDirectory("tree").toFile()
        File(base, "a.yaml").writeText("x: 1\n")
        val outside = File(base.parentFile, "outside-" + System.nanoTime()).also { it.writeText("secret") }
        for (id in listOf(
            "active/%2e%2e/%2e%2e/x",
            "active/..%2f..%2fx",
            "active/%2e%2e%5c%2e%2e",
            "active/a.yaml%00.txt",
            "active/a.yaml\u0000",
            "edit/%2e%2e/x.yaml",
            "edit/lua/..%2fx.yaml",
            "edit/a\u0000.yaml",
            "sources/%2e%2e",
        )) {
            val path = RimeConfigPaths.parse(id)
            val resolved = path?.let { RimeConfigPaths.inside(base, it.segments) }
            assertTrue(id, resolved == null || resolved.canonicalPath.startsWith(base.canonicalPath + File.separator))
            assertFalse(id, resolved?.canonicalPath == outside.canonicalPath)
        }
    }

    @Test
    fun aLinkInsideATreeIsNeverFollowed() {
        val base = Files.createTempDirectory("tree").toFile()
        val inside = File(base, "real.yaml").also { it.writeText("x: 1\n") }
        val outside = File(base.parentFile, "outside-" + System.nanoTime()).also { it.writeText("secret") }
        Files.createSymbolicLink(File(base, "link.yaml").toPath(), outside.toPath())
        Files.createSymbolicLink(File(base, "inner.yaml").toPath(), inside.toPath())
        File(base, "dir").mkdirs()
        Files.createSymbolicLink(File(base, "dir-link").toPath(), base.parentFile.toPath())

        assertNull(RimeConfigPaths.inside(base, listOf("link.yaml")))
        assertNull("not even a link that stays inside", RimeConfigPaths.inside(base, listOf("inner.yaml")))
        assertNull(RimeConfigPaths.inside(base, listOf("dir-link", outside.name)))
        assertNotNull(RimeConfigPaths.inside(base, listOf("real.yaml")))
    }
}
