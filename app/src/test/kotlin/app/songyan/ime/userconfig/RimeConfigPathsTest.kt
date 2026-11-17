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
        assertEquals(
            listOf("scheme-1", "rime_ice.custom.yaml"),
            RimeConfigPaths.parse("edit/scheme-1/rime_ice.custom.yaml")?.segments,
        )
        assertEquals(listOf("lua", "date.lua"), RimeConfigPaths.parse("active/lua/date.lua")?.segments)
        assertEquals(
            listOf("scheme-0123456789abcdef", "default.yaml"),
            RimeConfigPaths.parse("sources/scheme-0123456789abcdef/default.yaml")?.segments,
        )
    }

    @Test
    fun theUsersFilesAreInOneFolderPerSource() {
        assertEquals(RimeConfigPaths.Area.EDIT, RimeConfigPaths.parse("edit")?.area)
        assertEquals(emptyList<String>(), RimeConfigPaths.parse("edit")?.segments)
        assertEquals(listOf("songyan-builtin"), RimeConfigPaths.parse("edit/songyan-builtin")?.segments)
        assertEquals("edit/scheme-1", RimeConfigPaths.editId("scheme-1"))
        assertEquals("edit/scheme-1/lua/date.lua", RimeConfigPaths.editId("scheme-1", "lua/date.lua"))
        for (id in listOf(
            "edit/rime_ice.custom.yaml",
            "edit/Scheme-1/x.yaml",
            "edit/-scheme/x.yaml",
            "edit/scheme_1/x.yaml",
            "edit/.scheme/x.yaml",
            "edit/" + "a".repeat(41) + "/x.yaml",
        )) {
            assertNull("not a source's folder: $id", RimeConfigPaths.parse(id))
        }
        assertFalse(RimeConfigPaths.isChild("edit/scheme-1", "edit/scheme-10/x.yaml"))
        assertTrue(RimeConfigPaths.isChild("edit/scheme-1", "edit/scheme-1/x.yaml"))
    }

    @Test
    fun theUsersFolderMirrorsTheSourcesLayout() {
        assertEquals(listOf("s", "lua", "date.lua"), RimeConfigPaths.parse("edit/s/lua/date.lua")?.segments)
        assertEquals(listOf("s", "lua"), RimeConfigPaths.parse("edit/s/lua")?.segments)
        assertNull("a script is never made by a file manager", RimeConfigPaths.newFileName("date.lua", "text/plain"))
        assertNull(RimeConfigPaths.newFileName("date", "application/x-lua"))
    }

    @Test
    fun traversalAndOddSegmentsAreRefused() {
        for (id in listOf(
            "",
            "edit/../applied/x.yaml",
            "edit/s/../applied/x.yaml",
            "edit/..",
            "active/../../../shared_prefs/x.xml",
            "sources/..",
            "sources/a//b",
            "active/.",
            "active/a\\..\\b",
            "active/a\u0000b",
            "edit/",
            "edit/s/",
            "elsewhere/x.yaml",
            "/edit/s/x.yaml",
            "edit/s/build/x.yaml",
            "edit/s/lua/../x.yaml",
            "edit/s/lua/.hidden.lua",
            "edit/s/a/b/c/d/e/x.yaml",
            "edit/s/.hidden.yaml",
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
        assertTrue(RimeConfigPaths.isChild("root", "edit/s/a.yaml"))
        assertTrue(RimeConfigPaths.isChild("edit", "edit/s/a.yaml"))
        assertTrue(RimeConfigPaths.isChild("active", "active/lua/x.lua"))
        assertFalse(RimeConfigPaths.isChild("edit", "editor/a.yaml"))
        assertFalse(RimeConfigPaths.isChild("active", "sources/x"))
        assertFalse(RimeConfigPaths.isChild("edit", "edit/s/../x.yaml"))
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
            "edit/s/%2e%2e/x.yaml",
            "edit/s/lua/..%2fx.yaml",
            "edit/s/a\u0000.yaml",
            "edit/%2e%2e/x.yaml",
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
