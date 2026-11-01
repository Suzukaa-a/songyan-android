package app.songyan.ime.content

import app.songyan.ime.packages.DictionaryImportError
import app.songyan.ime.packages.SchemePackageImportError
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The mapping from core rejection reasons to user-facing problem kinds is the contract between the
 * deploy process (which records the kind's name) and the settings gateway (which shows its copy).
 */
class SchemeImportProblemsTest {
    @Test
    fun relatedReasonsKeepTheirOwnCopy() {
        assertEquals(
            SchemeImportProblemKind.NAME_ENCODING,
            SchemeImportProblems.kindOf(SchemePackageImportError.Reason.UNSUPPORTED_NAME_ENCODING),
        )
        assertEquals(
            SchemeImportProblemKind.MISSING_DEPENDENCY,
            SchemeImportProblems.kindOf(SchemePackageImportError.Reason.MISSING_DEPENDENCY),
        )
        assertEquals(
            SchemeImportProblemKind.BUILTIN_CUSTOMIZATION,
            SchemeImportProblems.kindOf(SchemePackageImportError.Reason.BUILTIN_CUSTOMIZATION),
        )
        assertEquals(
            SchemeImportProblemKind.UNSAFE_CONTENTS,
            SchemeImportProblems.kindOf(SchemePackageImportError.Reason.DUPLICATE_PATH),
        )
    }

    @Test
    fun everyKindHasItsOwnMessage() {
        val scheme = SchemeImportProblemKind.entries.filter { it != SchemeImportProblemKind.CANCELLED }
        assertEquals(scheme.size, scheme.map { SchemeImportProblems.messageRes(it) }.toSet().size)
        val dictionary = DictionaryImportProblemKind.entries.filter { it != DictionaryImportProblemKind.CANCELLED }
        assertEquals(dictionary.size, dictionary.map { DictionaryImportProblems.messageRes(it) }.toSet().size)
    }

    @Test
    fun kindNamesRoundTripThroughTheRequestStatus() {
        for (kind in SchemeImportProblemKind.entries) assertEquals(kind, SchemeImportProblems.kindNamed(kind.name))
        for (kind in DictionaryImportProblemKind.entries) {
            assertEquals(
                kind,
                DictionaryImportProblems.kindNamed(kind.name),
            )
        }
        assertEquals(null, SchemeImportProblems.kindNamed("NOT_A_KIND"))
    }

    @Test
    fun dictionaryDependencyReasonsAreNotGenericFormatErrors() {
        assertEquals(
            DictionaryImportProblemKind.MISSING_DEPENDENCIES,
            DictionaryImportProblems.kindOf(DictionaryImportError.Reason.MISSING_DEPENDENCIES),
        )
        assertEquals(
            DictionaryImportProblemKind.DEPENDENCY_CYCLE,
            DictionaryImportProblems.kindOf(DictionaryImportError.Reason.DEPENDENCY_CYCLE),
        )
        assertEquals(
            "a.dict.yaml、b.dict.yaml、c.dict.yaml…",
            ImportPaths.list(listOf("x/a.dict.yaml", "b.dict.yaml", "c.dict.yaml", "d.dict.yaml"), "、"),
        )
    }
}
