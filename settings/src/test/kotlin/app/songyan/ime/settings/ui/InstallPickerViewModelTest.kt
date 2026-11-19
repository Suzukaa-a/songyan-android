package app.songyan.ime.settings.ui

import android.app.Application
import app.songyan.ime.keyboard.engine.SchemaKeyProfile
import app.songyan.ime.keyboard.engine.SchemaKeyProfile.Kind
import app.songyan.ime.layout.ApplicableLayouts
import app.songyan.ime.layout.LayoutRegistry
import app.songyan.ime.settings.state.ImeStatus
import app.songyan.ime.settings.state.ImeStatusSource
import app.songyan.ime.settings.state.ImportedSchemeInfo
import app.songyan.ime.settings.state.InMemoryLayoutLibrarySource
import app.songyan.ime.settings.state.InMemorySettingsAppStateSource
import app.songyan.ime.settings.state.InMemorySettingsPreferences
import app.songyan.ime.settings.state.InstalledScheme
import app.songyan.ime.settings.state.PackageLearningResult
import app.songyan.ime.settings.state.SchemeImportGateway
import app.songyan.ime.settings.state.SchemeImportResult
import app.songyan.ime.settings.state.SettingsAppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The install picker after a new package finished installing: defaults, covering, confirming. */
@OptIn(ExperimentalCoroutinesApi::class)
class InstallPickerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val letters = ('a'..'z').map { it.toString() }
    private val all = setOf("qwerty26")
    private val strokes =
        listOf("h", "s", "p", "n", "z").zip(listOf("⼀", "⼁", "⼃", "⼂", "⼄")).map {
            SchemaKeyProfile.Stroke(it.first, it.second)
        }
    private val wubi =
        InstalledScheme(
            "wubi86",
            "五笔",
            all,
            profile =
                SchemaKeyProfile(
                    Kind.SHAPE_CODE,
                    letters + listOf(";", "'"),
                ),
        )
    private val stroke =
        InstalledScheme(
            "stroke",
            "笔画",
            all,
            profile =
                SchemaKeyProfile(
                    Kind.STROKES,
                    strokes.map { it.code } + listOf("'", "`"),
                    strokes,
                ),
        )
    private val pinyin = InstalledScheme("pinyin", "拼音", all, profile = SchemaKeyProfile(Kind.SYLLABIC, letters))
    private val preferences = InMemorySettingsPreferences(schemaId = "pinyin", layoutId = "qwerty26")
    private val library = InMemoryLayoutLibrarySource(LayoutRegistry.create())
    private val info =
        ImportedSchemeInfo(
            "包",
            listOf("wubi86", "stroke", "pinyin"),
            inUse = true,
            sourceId = "scheme-0123456789abcdef",
        )

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() =
        SettingsViewModel(
            application = Application(),
            appStateSource =
                InMemorySettingsAppStateSource(
                    SettingsAppState(schemes = listOf(wubi, stroke, pinyin), inUseSchemeId = "wubi86"),
                ),
            imeStatusSource = ImeStatusSource { ImeStatus(enabled = true, selected = true) },
            preferences = preferences,
            schemeGateway = AvailableGateway(),
            layoutLibrary = library,
            ioDispatcher = dispatcher,
        )

    @Test
    fun aNewPackageOffersEachInputMethodsDefaultAndCoversItWithNoKeyboards() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.runSchemeImport { SchemeImportResult.Success(info) }
            advanceUntilIdle()

            val picker = requireNotNull(viewModel.uiState.value.installPicker)
            assertEquals(
                setOf(
                    "wubi86:qwerty26#auto.wubi86.keys26",
                    "stroke:qwerty26#auto.stroke.strokes",
                    "pinyin:qwerty26#qwerty26",
                ),
                picker.checked,
            )
            assertEquals(listOf("wubi86", "stroke", "pinyin"), picker.groups.map { it.schemeId })
            // Nothing is asked of the deployment until the user answers.
            val covered = preferences.myKeyboards.first()
            assertTrue(covered.keyboards.isEmpty())
            assertEquals(setOf("wubi86", "stroke", "pinyin"), covered.schemes)
        }

    @Test
    fun anUpdateOfAUsedPackageOffersNoPicker() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.runSchemeImport(updated = true) { SchemeImportResult.Success(info) }
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.installPicker)
            assertTrue(
                preferences.myKeyboards
                    .first()
                    .schemes
                    .isEmpty(),
            )
        }

    @Test
    fun confirmingStoresTheTickedKeyboardsInstallsTheirLayoutsAndSwitchesToTheFirst() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.runSchemeImport { SchemeImportResult.Success(info) }
            advanceUntilIdle()
            // The plain 26-key board of wubi as well; the pinyin one is unticked.
            viewModel.togglePickedKeyboard("wubi86:qwerty26#qwerty26")
            viewModel.togglePickedKeyboard("pinyin:qwerty26#qwerty26")
            advanceUntilIdle()

            viewModel.confirmInstalledKeyboards { kind, _ ->
                if (kind ==
                    ApplicableLayouts.Generated.KEYS26
                ) {
                    "26+"
                } else {
                    "笔画"
                }
            }
            advanceUntilIdle()

            val stored = preferences.myKeyboards.first()
            assertEquals(
                listOf(
                    "wubi86:qwerty26#auto.wubi86.keys26",
                    "wubi86:qwerty26#qwerty26",
                    "stroke:qwerty26#auto.stroke.strokes",
                ),
                stored.keyboards.map { it.id },
            )
            assertEquals(setOf("wubi86", "stroke", "pinyin"), stored.schemes)
            assertEquals("wubi86", preferences.activeSchemaId.first())
            assertEquals("auto.wubi86.keys26", preferences.layoutDefinitionId.first())
            val registry = requireNotNull(library.registry.value)
            assertNotNull(registry.layout("auto.wubi86.keys26"))
            assertEquals("26+", registry.layout("auto.wubi86.keys26")?.name)
            assertNotNull(registry.layout("auto.stroke.strokes"))
            assertNull(viewModel.uiState.value.installPicker)
        }

    private class AvailableGateway : SchemeImportGateway {
        override val available = true

        override suspend fun importFromUri(uri: android.net.Uri): SchemeImportResult = SchemeImportResult.Success(null)

        override suspend fun mergePackageLearning(sourceId: String): PackageLearningResult =
            PackageLearningResult.Failure(null)

        override fun discardPackageLearning(sourceId: String) = Unit
    }
}
