package app.songyan.ime.diagnostics

import app.songyan.ime.deploy.RequestState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticReportTest {
    private val facts =
        DiagnosticFacts(
            appVersion = "1.2.3",
            buildType = "release",
            androidRelease = "16",
            sdkInt = 36,
            device = "Google Pixel 9",
            abis = listOf("arm64-v8a"),
            engineVersion = "1.17.0",
            dataVersion = "2",
            activeKind = "scheme",
            activeUsable = true,
            activeSchemaCount = 3,
            builtinSources = 1,
            dictionarySources = 0,
            schemeSources = 2,
            damagedSources = 1,
            deployState = "READY",
            freeStorageMb = 4096,
            recentRequests =
                listOf(
                    RequestFact(RequestState.FAILED, "DOWNLOAD_HOST", 3),
                    RequestFact(RequestState.SUCCEEDED, null, 120),
                ),
            engineRecoveries = listOf(RecoveryFact("scheme", movedLearningData = true, switchedToBuiltin = true, 5)),
            deleteKeyEvents =
                listOf(
                    DeleteKeyFact(
                        "TOUCH_DOWN_DELETE",
                        composing = false,
                        cachedCaret = false,
                        "com.example.chat",
                        1200,
                    ),
                    DeleteKeyFact("EDITOR_DELETED_BEFORE_CURSOR", false, cachedCaret = true, "com.example.chat", 1190),
                ),
        )

    @Test
    fun theReportStatesVersionsStateAndRecentResultCodes() {
        val text = DiagnosticReport.render(facts)

        assertTrue(text.contains("app: 1.2.3 (release)"))
        assertTrue(text.contains("android: 16 (API 36)"))
        assertTrue(text.contains("engine: 1.17.0, data: 2"))
        assertTrue(text.contains("active: scheme, usable: true, input methods: 3"))
        assertTrue(text.contains("sources: builtin 1, dictionary 0, scheme 2, damaged 1"))
        assertTrue(text.contains("  FAILED DOWNLOAD_HOST 3 min ago"))
        assertTrue(text.contains("  SUCCEEDED - 120 min ago"))
        assertTrue(text.contains("  scheme learning moved: true, switched to builtin: true, 5 min ago"))
        assertTrue(text.contains("  -1200 ms TOUCH_DOWN_DELETE com.example.chat"))
        assertTrue(text.contains("  -1190 ms EDITOR_DELETED_BEFORE_CURSOR cached-caret com.example.chat"))
    }

    @Test
    fun theFactsHaveNoRoomForPersonalText() {
        // The privacy rule is structural: adding a name, link, word or path field must be a
        // deliberate change that fails here and in review.
        val allowed =
            setOf(
                "appVersion",
                "buildType",
                "androidRelease",
                "sdkInt",
                "device",
                "abis",
                "engineVersion",
                "dataVersion",
                "activeKind",
                "activeUsable",
                "activeSchemaCount",
                "builtinSources",
                "dictionarySources",
                "schemeSources",
                "damagedSources",
                "deployState",
                "freeStorageMb",
                "recentRequests",
                "engineRecoveries",
                "deleteKeyEvents",
            )
        val actual =
            DiagnosticFacts::class.java.declaredFields
                .map { it.name }
                .filterNot { it.startsWith("$") }
                .toSet()
        assertTrue("unexpected fields: " + (actual - allowed), actual == allowed)
        assertFalse(DiagnosticReport.render(facts).contains("http"))
    }
}
