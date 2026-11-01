package app.songyan.ime.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** Ported from LayoutKitTests/LayoutKitTests: registry editing, import and persistence. */
class LayoutRegistryTest {
    @Test
    fun copyAndReplacePreserveOrderAndFailureSnapshot() {
        var registry = LayoutRegistry.create()
        val original = registry.layout("phone9")!!
        val path = LayoutKeyPath.Auxiliary(area = 0, key = 0)
        val key = original.key(path)!!
        val edited =
            original.replacingKey(
                path,
                LayoutKey(
                    key.label,
                    secondaryLabel = "问号",
                    width = 1.5,
                    action = key.action,
                    longPress = listOf(LayoutOption("？", LayoutAction.literal("？"))),
                ),
            )
        registry = registry.replace(edited)
        assertEquals(BuiltinLayouts.all.map { it.id }, registry.layouts.map { it.id })
        assertEquals(edited, LayoutJson.decode(edited.encoded()))

        val (withCopy, copy) = registry.copy(edited, newId = "copy-id")
        assertNotEquals(original.id, copy.id)
        assertEquals(edited.rows, copy.rows)
        assertEquals(edited.auxiliaryAreas, copy.auxiliaryAreas)
        assertEquals(BuiltinLayouts.all.map { it.id } + "copy-id", withCopy.layouts.map { it.id })

        val snapshot = registry
        val invalid = edited.replacingKey(path, LayoutKey("坏", action = LayoutAction.code("abc")))
        assertThrows(LayoutException::class.java) { registry.replace(invalid) }
        assertThrows(LayoutException::class.java) { registry.copy(invalid, newId = "x") }
        assertEquals(snapshot.layouts, registry.layouts)
        assertThrows(LayoutException::class.java) { registry.replace(edited.copyWithId("other")) }
        assertThrows(LayoutException::class.java) { withCopy.install(copy.encoded()) }

        // Importing the same id replaces in place, keeping the catalog order.
        val replaced = withCopy.install(copy.renamed("导入替换").encoded(), replaceExisting = true)
        assertEquals(withCopy.layouts.map { it.id }, replaced.layouts.map { it.id })
        assertEquals("导入替换", replaced.layout("copy-id")!!.name)
    }

    @Test
    fun importAppendsAndAUserLayoutCanBeRemovedAgain() {
        var registry = LayoutRegistry.create()
        val custom =
            LayoutDefinition(
                id = "DIY / a",
                name = "DIY",
                input = LayoutInput(schemaID = "installed_schema"),
                rows = BuiltinLayouts.qwerty26.rows,
            )
        registry = registry.install(custom.encoded())
        assertEquals(custom.id, registry.layouts.last().id)
        assertThrows(LayoutException::class.java) { registry.install(custom.encoded()) }
        registry = registry.remove(custom.id)
        assertEquals(BuiltinLayouts.all.map { it.id }, registry.layouts.map { it.id })
    }

    @Test
    fun aLayoutAKeyboardPairingUsesCannotBeRemoved() {
        val custom = BuiltinLayouts.qwerty26.copyWithId("mine", "我的 26 键")
        var registry = LayoutRegistry.create().install(custom.encoded())
        val key = KeyboardCompositionKey("songyan_quanpin", LayoutInput.Builtin.QWERTY26)
        registry = registry.withComposition(KeyboardComposition(mainLayoutID = "mine"), key.rawValue)
        val error = assertThrows(LayoutException::class.java) { registry.remove("mine") }
        assertEquals(LayoutInvalidCode.LAYOUT_IN_USE, (error as LayoutException.Invalid).code)
        assertEquals(listOf(key), registry.keyboardsUsingLayout("mine"))
    }

    @Test
    fun aKeyboardWithoutAPairingUsesTheDefaultMainLayout() {
        val key = KeyboardCompositionKey("songyan_quanpin", LayoutInput.Builtin.MERGED14)
        val registry = LayoutRegistry.create()
        assertEquals("merged14", registry.defaultMainLayoutId(key))
        assertEquals("merged14", registry.composition(key).mainLayoutID)
        assertEquals(listOf(key), registry.keyboardsUsingLayout("merged14", among = listOf(key)))
        // A layout bound to the input method and the shape wins, the smallest id first.
        val bound =
            BuiltinLayouts.merged14
                .copyWithId("bound-b", "B")
                .copy(input = LayoutInput(schemaID = "songyan_quanpin", builtinLayout = LayoutInput.Builtin.MERGED14))
        val earlier = bound.copyWithId("bound-a", "A")
        val withBound = registry.install(bound.encoded()).install(earlier.encoded())
        assertEquals("bound-a", withBound.defaultMainLayoutId(key))
    }

    @Test
    fun theMainLayoutCandidatesAreTheLayoutsOfThatShape() {
        val key = KeyboardCompositionKey("songyan_quanpin", LayoutInput.Builtin.QWERTY26)
        val mine = BuiltinLayouts.qwerty26.copyWithId("mine", "我的")
        val registry = LayoutRegistry.create().install(mine.encoded())
        assertEquals(listOf("qwerty26", "mine"), registry.mainLayoutCandidates(key).map { it.id })
    }

    @Test
    fun aCompositionKeyParsesWithAColonInTheInputMethodId() {
        val key = KeyboardCompositionKey.parse("pkg:schema:merged14")!!
        assertEquals("pkg:schema", key.inputMethodId)
        assertEquals(LayoutInput.Builtin.MERGED14, key.layout)
        assertEquals("pkg:schema:merged14", key.rawValue)
        assertNull(KeyboardCompositionKey.parse("nolayout"))
        assertNull(KeyboardCompositionKey.parse(":qwerty26"))
        assertNull(KeyboardCompositionKey.parse("schema:unknown"))
    }

    @Test
    fun builtinLayoutsCannotBeDeleted() {
        for (id in BuiltinLayouts.all.map { it.id }) {
            val error =
                assertThrows(LayoutException::class.java) {
                    LayoutRegistry.create().remove(id)
                }
            assertEquals(
                "builtin layout " + id,
                LayoutInvalidCode.LAYOUT_PRESET_IS_READ_ONLY,
                (error as LayoutException.Invalid).code,
            )
        }
    }

    @Test
    fun unknownRemovalIsRejected() {
        val registry = LayoutRegistry.create()
        val error = assertThrows(LayoutException::class.java) { registry.remove("missing") }
        assertEquals("missing", (error as LayoutException.UnknownId).layoutId)
    }

    @Test
    fun usableLayoutsIncludeThePresetsAnOlderCatalogLeftOut() {
        val registry = LayoutRegistry.create(layouts = listOf(BuiltinLayouts.phone9))
        assertEquals(
            listOf("phone9", "qwerty26"),
            registry
                .usableLayouts {
                    it.id == "qwerty26" || it.id == "phone9"
                }.map { it.id },
        )
        assertTrue(registry.usableLayouts { false }.isEmpty())
        assertEquals("qwerty26", registry.resolvedLayout("qwerty26")!!.id)
        assertNull(registry.layout("qwerty26"))
    }

    @Test
    fun duplicateIdsAreRejected() {
        val duplicated = listOf(BuiltinLayouts.qwerty26, BuiltinLayouts.qwerty26)
        val error = assertThrows(LayoutException::class.java) { LayoutRegistry.create(duplicated) }
        assertEquals(LayoutInvalidCode.DUPLICATE_LAYOUT_IDS, (error as LayoutException.Invalid).code)
    }

    @Test
    fun replacePreservesTheRestOfTheCatalog() {
        val registry = LayoutRegistry.create()
        val replaced = registry.replace(BuiltinLayouts.phone9.renamed("我的九键"))
        assertEquals("我的九键", replaced.layout("phone9")!!.name)
        assertEquals(
            registry.layouts.map { it.id },
            replaced.layouts.map { it.id },
        )
    }

    @Test
    fun storeRoundTripAndFailedImportPreservesSnapshot() {
        val directory = Files.createTempDirectory("LayoutKit-")
        try {
            val store = LayoutStore(directory.toString())
            var registry = store.load()
            // Nothing is written until the user edits.
            assertFalse(
                Files.exists(
                    java.nio.file.Paths
                        .get(store.catalogPath),
                ),
            )
            registry = registry.replace(BuiltinLayouts.qwerty26.renamed("我的 26 键"))
            store.save(registry)
            assertEquals(registry.layouts, store.load().layouts)

            val replacement = BuiltinLayouts.phone9.renamed("我的九键")
            registry = registry.replace(replacement)
            val (withCopy, copy) = registry.copy(replacement, newId = "copy-id")
            registry = withCopy
            store.save(registry)
            assertEquals(registry.layouts, store.load().layouts)

            val beforeFailedEdit =
                Files.readString(
                    java.nio.file.Paths
                        .get(store.catalogPath),
                )
            assertThrows(LayoutException::class.java) { registry.replace(replacement.renamed("")) }
            assertThrows(LayoutException::class.java) { registry.copy(replacement.renamed(""), newId = "y") }
            assertEquals(
                beforeFailedEdit,
                Files.readString(
                    java.nio.file.Paths
                        .get(store.catalogPath),
                ),
            )
            assertEquals(copy, store.load().layout(copy.id))

            val source = directory.resolve("import.json")
            val custom =
                LayoutDefinition(
                    id = "custom",
                    name = "Custom",
                    input = LayoutInput(builtinLayout = LayoutInput.Builtin.QWERTY26),
                    rows = BuiltinLayouts.qwerty26.rows,
                )
            Files.writeString(source, custom.encoded())
            val imported = store.importLayout(source.toString())
            assertEquals(BuiltinLayouts.all.map { it.id } + "copy-id" + "custom", imported.layouts.map { it.id })
            Files.writeString(source, "{}")
            assertThrows(LayoutException::class.java) { store.importLayout(source.toString()) }
            assertEquals(imported.layouts, store.load().layouts)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun loadingAMissingDirectoryYieldsTheBuiltinCatalog() {
        val directory = Files.createTempDirectory("LayoutKit-missing-")
        directory.toFile().deleteRecursively()
        val registry = LayoutStore(directory.toString()).load()
        assertEquals(BuiltinLayouts.all.map { it.id }, registry.layouts.map { it.id })
    }

    @Test
    fun ioFailuresAreDomainErrorsNotRawIoExceptions() {
        val directory = Files.createTempDirectory("LayoutKit-io-")
        try {
            // A directory where the catalog file belongs can never be read as a regular file.
            Files.createDirectory(directory.resolve(LayoutStore.CATALOG_FILE_NAME))
            val unreadable =
                assertThrows(LayoutException::class.java) {
                    LayoutStore(directory.toString()).load()
                }
            assertEquals(LayoutInvalidCode.UNREADABLE_SOURCE, (unreadable as LayoutException.Invalid).code)
        } finally {
            directory.toFile().deleteRecursively()
        }
        val scratch = Files.createTempDirectory("LayoutKit-blocked-")
        try {
            // A file where the catalog directory belongs makes the write fail; that must stay a
            // LayoutException so the settings UI can map it instead of crashing on IOException.
            val blocker = Files.createFile(scratch.resolve("blocker"))
            assertThrows(LayoutException::class.java) {
                LayoutStore(blocker.toString()).save(LayoutRegistry.create())
            }
        } finally {
            scratch.toFile().deleteRecursively()
        }
    }
}

/** Ported from LayoutKitTests/LayoutDefinitionIdentityTests. */
class LayoutIdentityTest {
    @Test
    fun sameContentSameIdYieldsStableIdentity() {
        val a = LayoutIdentityFixtures.merged14()
        val b = LayoutIdentityFixtures.merged14()
        assertTrue(a.runtimeIdentity.isProvablySameAs(b.runtimeIdentity))
        assertTrue(a.runtimeIdentity.isProvablySameAs(a.runtimeIdentity))
        val canonical = (a.runtimeIdentity as LayoutRuntimeIdentity.Proven).canonical
        assertTrue(canonical.contains("\"id\":\"merged14\""))
    }

    @Test
    fun renameAndNoteDoNotChangeIdentity() {
        val base = BuiltinLayouts.merged14
        val renamed = base.copy(name = "新的名字", note = "改过的说明")
        assertNotEquals(base, renamed)
        assertTrue(renamed.runtimeIdentity.isProvablySameAs(base.runtimeIdentity))
    }

    @Test
    fun keyActionRowShapeAndInputChangesChangeIdentity() {
        val base = LayoutIdentityFixtures.merged14()

        val changedAction =
            base.replacingKey(
                LayoutKeyPath.Row(row = 0, key = 0),
                base.rows[0].keys[0].withAction(LayoutAction.code("w")),
            )
        assertFalse(changedAction.runtimeIdentity.isProvablySameAs(base.runtimeIdentity))

        val reordered =
            base.copy(
                rows =
                    base.rows.mapIndexed { index, row ->
                        if (index == 2) LayoutRow(row.keys.reversed()) else row
                    },
            )
        assertFalse(reordered.runtimeIdentity.isProvablySameAs(base.runtimeIdentity))

        val rebound = base.copy(input = LayoutInput(schemaID = "imported_14"))
        assertFalse(rebound.runtimeIdentity.isProvablySameAs(base.runtimeIdentity))
    }

    @Test
    fun longPressWidthAndAuxiliaryAreasChangeIdentity() {
        val base = LayoutIdentityFixtures.merged14()

        val withLongPress =
            base.replacingKey(
                LayoutKeyPath.Row(row = 0, key = 0),
                base.rows[0].keys[0].withLongPress(listOf(LayoutOption("z", LayoutAction.literal("z")))),
            )
        assertFalse(withLongPress.runtimeIdentity.isProvablySameAs(base.runtimeIdentity))

        val withWidth =
            base.replacingKey(
                LayoutKeyPath.Row(row = 0, key = 0),
                base.rows[0].keys[0].withWidth(2.0),
            )
        assertFalse(withWidth.runtimeIdentity.isProvablySameAs(base.runtimeIdentity))

        val withAux =
            base.copy(
                auxiliaryAreas =
                    listOf(
                        LayoutAuxiliaryArea(
                            position = LayoutAuxiliaryArea.Position.LEADING,
                            role = LayoutAuxiliaryArea.Role.EDITING,
                            keys = listOf(LayoutKey("x", action = LayoutAction.code("x"))),
                        ),
                    ),
            )
        assertFalse(withAux.runtimeIdentity.isProvablySameAs(base.runtimeIdentity))
    }

    @Test
    fun differentIdChangesIdentity() {
        val a =
            LayoutDefinition(
                id = "a",
                name = "n",
                input = LayoutInput(builtinLayout = LayoutInput.Builtin.MERGED14),
                rows = LayoutIdentityFixtures.merged14().rows,
            )
        val b = a.copy(id = "b")
        assertFalse(a.runtimeIdentity.isProvablySameAs(b.runtimeIdentity))
    }

    @Test
    fun identityIsNotHashOrderDependent() {
        val definition = BuiltinLayouts.merged14
        val identities = (0 until 8).map { definition.runtimeIdentity }
        for (other in identities.drop(1)) {
            assertTrue(identities[0].isProvablySameAs(other))
        }
        assertTrue(definition.runtimeIdentity.isProven)
    }

    @Test
    fun encodingFailureYieldsUnprovenNeverCollides() {
        val a = LayoutIdentityFixtures.merged14()
        val b =
            a.replacingKey(
                LayoutKeyPath.Row(row = 0, key = 0),
                a.rows[0].keys[0].withAction(LayoutAction.code("w")),
            )
        assertEquals(a.id, b.id)
        val failing: (LayoutDefinition) -> String = { throw LayoutIdentityEncoder.EncodingUnavailable() }
        val idA = LayoutIdentityEncoder.identity(a, failing)
        val idB = LayoutIdentityEncoder.identity(b, failing)
        assertEquals(a.id, (idA as LayoutRuntimeIdentity.Unproven).id)
        assertFalse(idA.isProven)
        // Two different contents with the same id must never be judged the same on failure.
        assertFalse(idA.isProvablySameAs(idB))
        assertFalse(idA.isProvablySameAs(idA))
    }

    @Test
    fun absentNeverEqualsAbsentAndMixedStatesAreNeverSame() {
        assertFalse(LayoutRuntimeIdentity.Absent.isProvablySameAs(LayoutRuntimeIdentity.Absent))
        assertFalse(LayoutRuntimeIdentity.of(null).isProvablySameAs(LayoutRuntimeIdentity.of(null)))
        val definition = LayoutIdentityFixtures.merged14()
        val proven = definition.runtimeIdentity
        assertFalse(proven.isProvablySameAs(LayoutRuntimeIdentity.Unproven(definition.id)))
        assertFalse(proven.isProvablySameAs(LayoutRuntimeIdentity.Absent))
        assertFalse(LayoutRuntimeIdentity.Unproven("x").isProvablySameAs(LayoutRuntimeIdentity.Absent))
    }

    @Test
    fun absentAndUnprovenAreNotProven() {
        assertFalse(LayoutRuntimeIdentity.Absent.isProven)
        assertFalse(LayoutRuntimeIdentity.Unproven("x").isProven)
        assertTrue(LayoutRuntimeIdentity.Absent is LayoutRuntimeIdentity.Absent)
        assertNull(null as LayoutDefinition?)
    }
}

internal object LayoutIdentityFixtures {
    private fun code(value: String): LayoutKey =
        LayoutKey(value, secondaryLabel = value, action = LayoutAction.code(value))

    private fun controls(): LayoutRow =
        LayoutRow(
            listOf(
                LayoutKey("布局", action = LayoutAction(LayoutActionKind.MODE_SWITCH, "nextLayout")),
                LayoutKey("空格", width = 4.0, action = LayoutAction(LayoutActionKind.SPACE)),
                LayoutKey("⌫", action = LayoutAction(LayoutActionKind.BACKSPACE)),
                LayoutKey("⏎", action = LayoutAction(LayoutActionKind.ENTER)),
            ),
        )

    fun merged14(
        name: String = "14 键",
        rowsOverride: List<List<String>>? = null,
    ): LayoutDefinition {
        val groups =
            rowsOverride
                ?: listOf(
                    listOf("q", "e", "t", "u", "o"),
                    listOf("a", "d", "g", "j"),
                    listOf("l", "z", "c", "b", "m"),
                )
        return LayoutDefinition(
            id = "merged14",
            name = name,
            input = LayoutInput(builtinLayout = LayoutInput.Builtin.MERGED14),
            rows = groups.map { row -> LayoutRow(row.map { code(it) }) } + listOf(controls()),
        )
    }
}
