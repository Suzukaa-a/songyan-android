package app.songyan.ime.packages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeclarativeRecipeTest {
    private fun parse(text: String): DeclarativeRecipe =
        DeclarativeRecipe.parse("demo.recipe.yaml", text.toByteArray(Charsets.UTF_8))

    @Test
    fun parsesEverySupportedSection() {
        val text =
            """
            recipe:
              Rx: demo
              args:
                - name=alpha
              description: Demo recipe
                continued
            download_files:
              - models.gram::https://example.com/models.gram
            install_files: >-
              a.schema.yaml
              b.dict.yaml
            patch_files:
              a.custom.yaml:
                - patch/+:
                    translator/enable_correction: true
              b.custom.yaml:
                - patch/+: {}

            """.trimIndent()
        val recipe = parse(text)
        assertEquals("demo", recipe.declaredName)
        assertEquals(listOf("name=alpha"), recipe.declaredArguments)
        assertEquals("Demo recipe continued", recipe.displayDescription)
        assertEquals(1, recipe.downloads.size)
        assertEquals("models.gram", recipe.downloads[0].path)
        assertEquals("https://example.com/models.gram", recipe.downloads[0].url.toString())
        assertEquals(listOf("a.schema.yaml", "b.dict.yaml"), recipe.installPatterns)
        assertEquals(listOf("a.custom.yaml", "b.custom.yaml"), recipe.patchTargets)
        assertEquals(
            "  - patch/+:\n      translator/enable_correction: true\n",
            recipe.patches[0].body,
        )
    }

    @Test
    fun refusesTextTheIOSFormatDoesNotAccept() {
        // CRLF, tabs and NUL bytes are refused before any parsing.
        assertThrows(RecipeInstallError.InvalidManifest::class.java) { parse("recipe:\r\n  Rx: x\r\n") }
        assertThrows(RecipeInstallError.InvalidManifest::class.java) { parse("recipe:\n  Rx:\tx\n") }
        assertThrows(RecipeInstallError.InvalidManifest::class.java) { parse("recipe:\n  Rx: x\u0000\n") }
        // Anything outside the four top-level sections is unsupported.
        assertThrows(RecipeInstallError.InvalidManifest::class.java) { parse("bogus:\n  x: 1\n") }
        // A section may be declared only once and must exist.
        assertThrows(RecipeInstallError.InvalidManifest::class.java) { parse("recipe:\n  Rx: a\nrecipe:\n  Rx: b\n") }
        assertThrows(RecipeInstallError.InvalidManifest::class.java) {
            parse("patch_files:\n  a.custom.yaml:\n    - patch/+: {}\n")
        }
        // Indented content before any top-level declaration has nowhere to go.
        assertThrows(RecipeInstallError.InvalidManifest::class.java) { parse("  Rx: a\n") }
        // Unknown metadata statements are refused, not ignored.
        assertThrows(RecipeInstallError.InvalidManifest::class.java) { parse("recipe:\n  Rx: a\n  shell: rm -rf /\n") }
        // A patch target must carry a list.
        assertThrows(RecipeInstallError.InvalidManifest::class.java) {
            parse("recipe:\n  Rx: a\npatch_files:\n  a.custom.yaml:\n    translator/enable_correction: true\n")
        }
        // Wrong indentation inside patch_files is refused.
        assertThrows(RecipeInstallError.InvalidManifest::class.java) {
            parse("recipe:\n  Rx: a\npatch_files:\n  a.custom.yaml:\n   - patch/+: {}\n")
        }
    }

    @Test
    fun refusesUnsafeDownloadsAndPaths() {
        fun recipeWithDownloads(list: String): DeclarativeRecipe = parse("recipe:\n  Rx: a\ndownload_files:\n" + list)
        assertThrows(RecipeInstallError.InvalidManifest::class.java) {
            recipeWithDownloads("  - file.bin::ftp://example.com/file.bin\n")
        }
        assertThrows(RecipeInstallError.InvalidManifest::class.java) {
            recipeWithDownloads(
                "  - file.bin::https://example.com/file.bin\n  - file.bin::https://b.example/file.bin\n",
            )
        }
        // Quotes and whitespace are not accepted inside a declared item.
        assertThrows(RecipeInstallError.InvalidManifest::class.java) {
            recipeWithDownloads("  - \"https://example.com/file.bin\"\n")
        }
        assertEquals(
            "file.bin",
            recipeWithDownloads("  - https://example.com/file.bin\n").downloads[0].path,
        )
    }

    @Test
    fun checkPathRejectsEscapesAndUserData() {
        RecipePlanSupport.checkPath("a.yaml")
        RecipePlanSupport.checkPath("lua/module/file.lua")
        RecipePlanSupport.checkPath("build/.gitkeep")
        for (path in listOf("", "/absolute.yaml", "a/../b.yaml", "a.userdb.txt", "build/output.yaml", "a\\b.yaml")) {
            assertThrows("path: " + path, RecipeInstallError.InvalidResource::class.java) {
                RecipePlanSupport.checkPath(path)
            }
        }
        RecipePlanSupport.checkPath("lua/*.lua", glob = true)
        assertThrows(RecipeInstallError.InvalidResource::class.java) {
            RecipePlanSupport.checkPath("a/../*.lua", glob = true)
        }
    }

    @Test
    fun expandSupportsTheDeclaredVariableForms() {
        val variables = mapOf("name" to "alpha", "empty" to "")
        assertEquals("alpha.custom.yaml", RecipePlanSupport.expand("\u0024name.custom.yaml", variables))
        assertEquals("alpha.custom.yaml", RecipePlanSupport.expand("\u0024{name}.custom.yaml", variables))
        assertEquals("fallback", RecipePlanSupport.expand("\u0024{missing:-fallback}", variables))
        assertEquals("fallback", RecipePlanSupport.expand("\u0024{empty:-fallback}", variables))
        assertEquals("", RecipePlanSupport.expand("\u0024{missing}", variables))
        // A dollar sign at the very end is not a variable form.
        assertThrows(RecipeInstallError.InvalidManifest::class.java) {
            RecipePlanSupport.expand("\u0024", emptyMap())
        }
        for (text in listOf("\u0024{unclosed", "\u0024{name:-a:-b}", "\u0024{1bad}", "\u0024{a} \\", "\u0024{a} `")) {
            assertThrows("text: " + text, RecipeInstallError.InvalidManifest::class.java) {
                RecipePlanSupport.expand(text, variables)
            }
        }
    }

    @Test
    fun patchInsertsReplacesAndPreservesContent() {
        val body = "  - patch/+:\n      translator/enable_correction: true\n"
        assertEquals(
            "__patch:\n# Rx: user/pkg:demo: {\n  - patch/+:\n      translator/enable_correction: true\n# }\n",
            RecipePlanSupport.patch("", body, "user/pkg:demo:"),
        )
        // User content before the managed block survives, as does a foreign invocation's block.
        val existing =
            "patch:\n  menu/page_size: 7\n__patch:\n# Rx: other/recipe: {\n  - patch/+:\n      x: 1\n# }\n"
        val merged = RecipePlanSupport.patch(existing, body, "user/pkg:demo:")
        assertTrue(merged.contains("menu/page_size: 7"))
        assertTrue(merged.contains("# Rx: other/recipe: {"))
        assertEquals(1, merged.split("# Rx: user/pkg:demo: {").size - 1)
        // Re-running replaces our block instead of stacking a second one.
        val again = RecipePlanSupport.patch(merged, body, "user/pkg:demo:")
        assertEquals(1, again.split("# Rx: user/pkg:demo: {").size - 1)
        // A patch target must be LF-only and newline terminated.
        assertThrows(RecipeInstallError.InvalidResource::class.java) {
            RecipePlanSupport.patch("patch: {}\r\n", body, "rx:")
        }
        assertThrows(RecipeInstallError.InvalidResource::class.java) {
            RecipePlanSupport.patch("patch: {}", body, "rx:")
        }
        // Unclosed managed blocks are never modified.
        assertThrows(RecipeInstallError.InvalidResource::class.java) {
            RecipePlanSupport.patch("__patch:\n# Rx: user/pkg:demo: {\n  - patch/+: {}\n", body, "user/pkg:demo:")
        }
        // Only one __patch key is supported, and it must be a block list.
        assertThrows(RecipeInstallError.InvalidResource::class.java) {
            RecipePlanSupport.patch("__patch:\n__patch:\n", body, "rx:")
        }
        assertThrows(RecipeInstallError.InvalidResource::class.java) {
            RecipePlanSupport.patch("__patch: {}\n", body, "rx:")
        }
    }

    @Test
    fun anUnclosedManagedBlockNeverConsumesAnotherInvocationsBlock() {
        val body = "  - patch/+:\n      x: 2\n"
        // Our block lost its terminator; a scan to the next "# }" would delete the block owned by
        // the other invocation, so the patch must refuse and leave the file untouched.
        val damaged =
            "__patch:\n# Rx: user/pkg:demo: {\n  - patch/+:\n      x: 1\n" +
                "# Rx: other/recipe: {\n  - patch/+:\n      foreign: 9\n# }\n"
        assertThrows(RecipeInstallError.InvalidResource::class.java) {
            RecipePlanSupport.patch(damaged, body, "user/pkg:demo:")
        }
    }

    @Test
    fun dottedPackageReferencesAndArgumentValuesAreAccepted() {
        val recipe =
            parse(
                "recipe:\n  Rx: demo\npatch_files:\n" +
                    "  a.custom.yaml:\n    - patch/+:\n" +
                    "        translator/enable_correction: true\n",
            )
        val plan =
            recipe.applyPatches(
                existingFiles = emptyMap(),
                packageReference = "com.example.pkg",
                selectedRecipe = "demo",
                arguments = listOf(RecipeArgument("version", "1.5")),
            )
        // The managed-block marker is matched literally, so '.' is an ordinary character.
        assertEquals("com.example.pkg:demo:version=1.5", plan.rx)
        assertEquals(listOf("a.custom.yaml"), plan.changedPaths)
        assertTrue(
            String(plan.files.getValue("a.custom.yaml"), Charsets.UTF_8)
                .contains("# Rx: com.example.pkg:demo:version=1.5 {"),
        )
    }

    @Test
    fun applyPatchesReportsIdentityAndChanges() {
        // The patch target and body carry a \u0024-variable so argument expansion is exercised.
        val recipe =
            parse(
                "recipe:\n  Rx: demo\n  args:\n    - name=alpha\npatch_files:\n" +
                    "  \u0024{name}.custom.yaml:\n    - patch/+:\n        translator/enable_correction: true\n",
            )
        val existing = mapOf("alpha.custom.yaml" to "patch:\n  menu/page_size: 7\n".toByteArray())
        val plan =
            recipe.applyPatches(
                existingFiles = existing,
                packageReference = "user/pkg",
                selectedRecipe = "demo",
                arguments = listOf(RecipeArgument("name", "alpha")),
            )
        assertEquals("user/pkg:demo:name=alpha", plan.rx)
        assertEquals(listOf("alpha.custom.yaml"), plan.changedPaths)
        assertEquals(emptyList<String>(), plan.replacedManagedBlocks)
        val updated = String(plan.files.getValue("alpha.custom.yaml"), Charsets.UTF_8)
        assertTrue(updated.contains("translator/enable_correction: true"))
        // The same application again replaces its own block and reports no changes.
        val again =
            recipe.applyPatches(
                existingFiles = plan.files,
                packageReference = "user/pkg",
                selectedRecipe = "demo",
                arguments = listOf(RecipeArgument("name", "alpha")),
            )
        assertEquals(listOf("alpha.custom.yaml"), again.replacedManagedBlocks)
        assertEquals(emptyList<String>(), again.changedPaths)
        // Identity, argument and target validation mirror the iOS plan.
        assertThrows(RecipeInstallError.InvalidManifest::class.java) {
            recipe.applyPatches(plan.files, "user/pkg", "other")
        }
        assertThrows(RecipeInstallError.InvalidManifest::class.java) {
            recipe.applyPatches(plan.files, "user/pkg", "demo", listOf(RecipeArgument("bad name", "x")))
        }
        assertThrows(RecipeInstallError.InvalidManifest::class.java) {
            recipe.applyPatches(plan.files, "user pkg", "demo", listOf(RecipeArgument("name", "alpha")))
        }
        // An empty selected recipe keeps the iOS "implicit selection" form; it is not an error.
        assertEquals(
            "user/pkg::",
            recipe.applyPatches(plan.files, "user/pkg", "").rx,
        )
        val textTarget =
            parse("recipe:\n  Rx: demo\npatch_files:\n  a.txt:\n    - patch/+: {}\n")
        assertThrows(RecipeInstallError.InvalidManifest::class.java) {
            textTarget.applyPatches(emptyMap(), "user/pkg", "demo")
        }
        // A retained foreign invocation is reported, our own block is not.
        val foreign =
            parse("recipe:\n  Rx: demo\npatch_files:\n  a.custom.yaml:\n    - patch/+: {}\n")
        val foreignPlan =
            foreign.applyPatches(
                mapOf("a.custom.yaml" to "__patch:\n# Rx: other/recipe: {\n# }\n".toByteArray()),
                "user/pkg",
                "demo",
            )
        assertEquals(listOf("a.custom.yaml"), foreignPlan.retainedDifferentInvocations)
    }
}
