package app.songyan.ime.candidate

import app.songyan.ime.keyboard.engine.Candidate
import org.junit.Test

/** Cross-page accumulation: read-only page reads, revision identity, frontier, authoritative empty pages. */
class CandidateBrowserTest {
    private fun page(
        index: Int,
        texts: List<String>,
        schema: String = "s",
        raw: String = "ab",
        preedit: String = "ab",
        revision: Int = 1,
        canForward: Boolean = true,
        canBackward: Boolean = true,
    ) = CandidatePresentationState(
        schemaID = schema,
        preedit = preedit,
        rawInput = raw,
        candidates = texts.mapIndexed { offset, text -> Candidate(index = offset, text = text) },
        highlightedIndex = 0,
        pageIndex = index,
        pageSize = 2,
        canPageBackward = canBackward,
        canPageForward = canForward,
        isComposing = true,
        revision = revision,
    )

    private fun items(texts: List<String>) = texts.mapIndexed { offset, text -> Candidate(index = offset, text = text) }

    @Test
    fun resetAnchorsOnRealPageAndBackfillsPrefix() {
        val browser = CandidateBrowser()
        browser.reset(page(2, listOf("a", "b"), revision = 5, canForward = false))
        assertEquals(listOf("a", "b"), browser.entries.map { it.text })
        assertEquals(2, browser.enginePageIndex)
        assertTrue(browser.needsEarlier)
        assertTrue(browser.hasMore)
        assertEquals(0, browser.nextPageToLoad)
        assertTrue(browser.append(pageIndex = 0, candidates = items(listOf("c", "d")), pageSize = 2))
        assertTrue(browser.append(pageIndex = 1, candidates = items(listOf("e", "f")), pageSize = 2))
        assertEquals(
            listOf("0:0", "0:1", "1:0", "1:1", "2:0", "2:1"),
            browser.entries.map { it.id },
            "candidates sort by absolute index, never reversed or duplicated",
        )
        assertFalse(browser.needsEarlier)
        assertFalse(browser.hasMore)
        assertNull(browser.nextPageToLoad)
    }

    @Test
    fun backfillsPrefixThenContinuesForward() {
        val browser = CandidateBrowser()
        browser.reset(page(2, listOf("a", "b"), revision = 5, canForward = true))
        assertEquals(0, browser.nextPageToLoad)
        assertTrue(browser.append(pageIndex = 0, candidates = items(listOf("c", "d")), pageSize = 2))
        assertEquals(1, browser.nextPageToLoad)
        assertTrue(browser.append(pageIndex = 1, candidates = items(listOf("e", "f")), pageSize = 2))
        assertEquals(3, browser.nextPageToLoad, "after earlier pages are filled, continue past the real current page")
        assertTrue(browser.append(pageIndex = 3, candidates = items(listOf("g")), pageSize = 2))
        assertFalse(browser.hasMore, "a read-back shorter than a page past the anchor is the last page")
    }

    @Test
    fun resetIsNoOpWhenIdentityUnchanged() {
        val browser = CandidateBrowser()
        browser.reset(page(0, listOf("a", "b"), revision = 7, canBackward = false))
        assertTrue(browser.append(pageIndex = 1, candidates = items(listOf("c", "d")), pageSize = 2))
        assertEquals(4, browser.count)
        browser.reset(page(0, listOf("a", "b"), revision = 7, canBackward = false))
        assertEquals(4, browser.count, "an unchanged identity must keep loaded pages")
        assertEquals(2, browser.nextPageToLoad)
        browser.reset(page(0, listOf("a", "b"), preedit = "DIFFERENT", revision = 7, canBackward = false))
        assertEquals(4, browser.count, "preedit is not part of the identity")
    }

    @Test
    fun revisionChangeInvalidatesSameRawIdentity() {
        val browser = CandidateBrowser()
        browser.reset(page(0, listOf("a", "b"), raw = "ab", revision = 1, canBackward = false))
        assertTrue(browser.append(pageIndex = 1, candidates = items(listOf("c", "d")), pageSize = 2))
        assertEquals(4, browser.count)
        browser.reset(page(0, listOf("new1", "new2"), raw = "ab", revision = 2, canBackward = false))
        assertEquals(listOf("new1", "new2"), browser.entries.map { it.text })
    }

    @Test
    fun appendRejectsDuplicateAndOutOfIdentityPage() {
        val browser = CandidateBrowser()
        browser.reset(page(0, listOf("a", "b"), canBackward = false))
        assertTrue(browser.append(pageIndex = 1, candidates = items(listOf("c", "d")), pageSize = 2))
        assertFalse(browser.append(pageIndex = 1, candidates = items(listOf("c", "d")), pageSize = 2))
        assertEquals(4, browser.count)
        assertFalse(browser.append(pageIndex = -1, candidates = items(listOf("x")), pageSize = 2))
    }

    @Test
    fun generationStableIdIsDeterministicAndUnambiguous() {
        val a =
            CandidateGeneration(
                revision = 3,
                schemaID = "s",
                layout = "l",
                rawInput = "ab",
                pageSize = 5,
                isComposing = true,
            )
        val b =
            CandidateGeneration(
                revision = 3,
                schemaID = "s",
                layout = "l",
                rawInput = "ab",
                pageSize = 5,
                isComposing = true,
            )
        val c = a.copy(revision = 4)
        assertEquals(a, b)
        assertEquals(a.stableId, b.stableId)
        assertNotEquals(a.stableId, c.stableId, "a new composition must give a different sentinel identity")

        // A separator inside a field must not collide: bare concatenation would make these equal.
        val ambiguousA = a.copy(schemaID = "s|l", layout = "x")
        val ambiguousB = a.copy(schemaID = "s", layout = "l|x")
        assertNotEquals(ambiguousA, ambiguousB)
        assertNotEquals(ambiguousA.stableId, ambiguousB.stableId, "string field boundaries must be unique")
    }

    @Test
    fun sentinelIdentityDistinguishesFrontierAndViewKind() {
        val generation =
            CandidateGeneration(
                revision = 3,
                schemaID = "s",
                layout = "l",
                rawInput = "ab",
                pageSize = 5,
                isComposing = true,
            )
        val base =
            CandidateSentinelIdentity(
                generation = generation,
                frontier = 0,
                kind = CandidateSentinelIdentity.Kind.COLLAPSED_TAIL,
            )
        assertEquals(
            base,
            CandidateSentinelIdentity(
                generation = generation,
                frontier = 0,
                kind = CandidateSentinelIdentity.Kind.COLLAPSED_TAIL,
            ),
        )
        assertNotEquals(base, base.copy(frontier = 1))
        assertNotEquals(base, base.copy(kind = CandidateSentinelIdentity.Kind.EXPANDED_TAIL))
        assertNotEquals(base, base.copy(generation = generation.copy(revision = 4)))
    }

    @Test
    fun emptyResultClearsOldPage() {
        val browser = CandidateBrowser()
        browser.reset(page(0, listOf("a", "b"), canBackward = false))
        assertTrue(browser.append(pageIndex = 1, candidates = items(listOf("c", "d")), pageSize = 2))
        assertEquals(4, browser.count)
        browser.reset(
            CandidatePresentationState(
                schemaID = "s",
                rawInput = "ab",
                pageIndex = 0,
                pageSize = 2,
                isComposing = true,
                revision = 2,
            ),
        )
        assertTrue(browser.hasNoEntries, "an empty result must clear old pages")
        assertFalse(browser.hasMore)
    }

    @Test
    fun sameIdentityEmptyResultClearsAndRecovers() {
        val browser = CandidateBrowser()
        browser.reset(page(0, listOf("a", "b"), revision = 7, canBackward = false))
        assertTrue(browser.append(pageIndex = 1, candidates = items(listOf("c", "d")), pageSize = 2))
        assertEquals(4, browser.count)
        assertEquals(2, browser.nextPageToLoad)
        browser.reset(page(0, emptyList(), revision = 7, canBackward = false))
        assertTrue(browser.hasNoEntries, "an empty result under the same identity must not keep old candidates")
        assertFalse(browser.hasMore)
        assertNull(browser.nextPageToLoad)
        assertFalse(browser.hasIncompletePage)
        browser.reset(page(0, listOf("new"), revision = 7, canBackward = false))
        assertEquals(
            listOf("new"),
            browser.entries.map { it.text },
            "non-empty under the same identity after a clear must rebuild",
        )
    }

    @Test
    fun earlierShortPageStaysRetryableWithoutHole() {
        val browser = CandidateBrowser()
        browser.reset(page(2, listOf("a", "b"), revision = 5, canForward = false))
        assertEquals(0, browser.nextPageToLoad)
        assertFalse(
            browser.append(pageIndex = 0, candidates = emptyList(), pageSize = 2),
            "an empty page before the anchor is not EOF and must not be sealed as read",
        )
        assertEquals(0, browser.nextPageToLoad, "the frontier must stay and must not skip earlier pages")
        assertTrue(browser.hasIncompletePage)
        assertTrue(browser.hasIncompleteEarlierPage)
        assertTrue(browser.canAppend(pageIndex = 0), "an incomplete page stays retryable")
        assertTrue(browser.loadedPages.contains(2))
        assertFalse(browser.loadedPages.contains(0))
        assertEquals(
            CandidateBrowser.BrowserAppendResult.APPENDED,
            browser.append(
                pageIndex = 0,
                result = CandidateBrowser.CandidatePageReadResult.Page(items(listOf("c", "d"))),
                pageSize = 2,
            ),
        )
        assertEquals(1, browser.nextPageToLoad)
        assertFalse(browser.hasIncompletePage)
        assertEquals(2, browser.entries.count { it.pageIndex == 0 })
    }

    @Test
    fun aShortEarlierPageKeepsItsCandidatesWhileStayingRetryable() {
        val browser = CandidateBrowser()
        browser.reset(page(2, listOf("a", "b"), revision = 5, canForward = false))
        assertEquals(
            CandidateBrowser.BrowserAppendResult.INCOMPLETE,
            browser.append(
                pageIndex = 0,
                result = CandidateBrowser.CandidatePageReadResult.Page(items(listOf("c"))),
                pageSize = 2,
            ),
        )
        // The real candidate is shown while the read stays retryable: nothing is silently dropped,
        // which is what the iOS accumulator does in the same branch.
        assertEquals(listOf("c"), browser.entries.filter { it.pageIndex == 0 }.map { it.text })
        assertEquals(0, browser.nextPageToLoad)
        assertTrue(browser.hasIncompleteEarlierPage)
        // A later complete read replaces the partial page instead of stacking a duplicate.
        assertTrue(browser.append(pageIndex = 0, candidates = items(listOf("d", "e")), pageSize = 2))
        assertEquals(listOf("d", "e"), browser.entries.filter { it.pageIndex == 0 }.map { it.text })
    }

    @Test
    fun unavailableReadDoesNotConcludeEof() {
        val browser = CandidateBrowser()
        browser.reset(page(0, listOf("a", "b"), revision = 1))
        assertEquals(1, browser.nextPageToLoad)
        assertEquals(
            CandidateBrowser.BrowserAppendResult.INCOMPLETE,
            browser.append(pageIndex = 1, result = CandidateBrowser.CandidatePageReadResult.Unavailable, pageSize = 2),
        )
        assertTrue(browser.hasMore, "a failed read must not claim the last page")
        assertEquals(1, browser.nextPageToLoad)
        assertTrue(browser.hasIncompletePage)
        assertFalse(browser.loadedPages.contains(1))
        assertEquals(
            CandidateBrowser.BrowserAppendResult.APPENDED,
            browser.append(
                pageIndex = 1,
                result = CandidateBrowser.CandidatePageReadResult.Page(items(listOf("c"))),
                pageSize = 2,
            ),
        )
        assertFalse(browser.hasMore)
        assertFalse(browser.hasIncompletePage)
    }

    @Test
    fun readResultMappingSeparatesFailureFromEof() {
        assertEquals(
            CandidateBrowser.CandidatePageReadResult.Unavailable,
            CandidateBrowser.CandidatePageReadResult.reading { throw IllegalStateException("engine down") },
            "a throw must never be treated as empty/EOF",
        )
        assertEquals(
            CandidateBrowser.CandidatePageReadResult.Page(
                emptyList(),
            ),
            CandidateBrowser.CandidatePageReadResult.reading {
                emptyList()
            },
        )
        assertEquals(
            CandidateBrowser.CandidatePageReadResult.Page(items(listOf("a"))),
            CandidateBrowser.CandidatePageReadResult.reading { items(listOf("a")) },
        )
    }

    @Test
    fun productionLoadMoreKeepsFailureRetryableThenEof() {
        // Mirrors the controller wiring: nextPageToLoad -> reading(throws) -> append(result:).
        fun load(
            browser: CandidateBrowser,
            read: () -> List<Candidate>,
        ) {
            val page = browser.nextPageToLoad ?: return
            if (!browser.hasMore) return
            browser.append(page, CandidateBrowser.CandidatePageReadResult.reading(read), pageSize = 2)
        }
        val browser = CandidateBrowser()
        browser.reset(page(0, listOf("a", "b"), revision = 1))
        assertEquals(1, browser.nextPageToLoad)
        load(browser) { throw IllegalStateException("engine down") }
        assertEquals(1, browser.nextPageToLoad, "the frontier must stay after a failure")
        assertTrue(browser.hasMore)
        assertTrue(browser.hasIncompletePage)
        assertFalse(browser.loadedPages.contains(1))
        load(browser) { emptyList() }
        assertFalse(browser.hasMore, "a successful empty read after recovery should end normally at EOF")
        assertFalse(browser.hasIncompletePage)
        assertTrue(browser.loadedPages.contains(1))
    }

    @Test
    fun resetClearsOnEndCompositionOrEnglish() {
        val browser = CandidateBrowser()
        browser.reset(page(0, listOf("a", "b"), canBackward = false))
        browser.reset(CandidatePresentationState(isComposing = false))
        assertTrue(browser.hasNoEntries)
        browser.reset(page(0, listOf("a", "b"), canBackward = false))
        browser.reset(CandidatePresentationState(isEnglishMode = true))
        assertTrue(browser.hasNoEntries)
    }

    @Test
    fun keepsEngineEnglishAndEmptyInputPredictionMenus() {
        val browser = CandidateBrowser()
        val english =
            CandidatePresentationState(
                schemaID = "user-schema",
                rawInput = "hell",
                candidates = listOf(Candidate(index = 2, text = "hello")),
                pageSize = 5,
                isComposing = true,
                isEnglishMode = true,
                revision = 20,
            )
        browser.reset(english)
        assertEquals(listOf("hello"), browser.entries.map { it.candidate.text })
        assertEquals(true, browser.generation?.isComposing)

        val predicted =
            CandidatePresentationState(
                rawInput = "",
                candidates = listOf(Candidate(index = 0, text = "world")),
                pageSize = 5,
                canPageForward = true,
                isComposing = false,
                isPrediction = true,
                revision = 21,
            )
        assertTrue(predicted.showsCandidateBar)
        assertTrue(predicted.showsExpandedArea)
        assertFalse(predicted.showsCompositionRow(CandidateBarDefinition.systemDefault))
        browser.reset(predicted)
        assertEquals(listOf("world"), browser.entries.map { it.candidate.text })
        assertEquals(false, browser.generation?.isComposing)
        assertEquals(true, browser.generation?.isPrediction)
        assertEquals(1, browser.nextPageToLoad)
        assertEquals(
            CandidateBrowser.BrowserAppendResult.APPENDED,
            browser.append(
                pageIndex = 1,
                result = CandidateBrowser.CandidatePageReadResult.Page(listOf(Candidate(index = 0, text = "again"))),
                pageSize = 5,
            ),
        )
        assertEquals(listOf("world", "again"), browser.entries.map { it.candidate.text })

        val predictionIdentity = browser.generation
        browser.reset(predicted.copy(isPrediction = false, isComposing = true))
        assertNotEquals(predictionIdentity, browser.generation)
        assertNotEquals(predictionIdentity?.stableId, browser.generation?.stableId)
        assertEquals(listOf("world"), browser.entries.map { it.text }, "menu-kind changes invalidate old pages")

        browser.reset(CandidatePresentationState(isEnglishMode = true))
        assertTrue(browser.hasNoEntries, "without engine candidates no previous or guessed word may remain")
        browser.reset(
            CandidatePresentationState(
                candidates = listOf(Candidate(index = 0, text = "ghost")),
                isComposing = false,
            ),
        )
        assertTrue(
            browser.hasNoEntries,
            "standard Rime has no browsable menu that exists apart from a composition segment",
        )
    }

    @Test
    fun followingTheEnginePageKeepsTheBrowsedPagesAndRecordsTheNewOne() {
        val browser = CandidateBrowser()
        browser.reset(page(0, listOf("a", "b")))
        assertTrue(browser.append(pageIndex = 1, candidates = items(listOf("c", "d")), pageSize = 2))
        // A page key moved the engine to page 1, which was already read: nothing is re-read or lost.
        val onRead = page(1, listOf("c", "d"))
        browser.reset(onRead)
        assertTrue(browser.follow(onRead))
        assertEquals(1, browser.enginePageIndex)
        assertEquals(listOf("a", "b", "c", "d"), browser.entries.map { it.text })
        assertEquals(2, browser.nextPageToLoad)
        // Another page key reaches a page nobody read yet: the engine's own page joins the list.
        val unread = page(2, listOf("e"), canForward = false)
        browser.reset(unread)
        assertTrue(browser.follow(unread))
        assertEquals(listOf("a", "b", "c", "d", "e"), browser.entries.map { it.text })
        assertFalse(browser.hasMore, "the engine said this is the last page")
    }

    @Test
    fun followingAPageThatDisagreesWithWhatWasReadIsRefused() {
        val browser = CandidateBrowser()
        browser.reset(page(0, listOf("a", "b")))
        // Same identity, yet the engine shows other words on page 0 (a learned word moved up).
        val changed = page(0, listOf("b", "a"))
        browser.reset(changed)
        assertFalse(browser.follow(changed))
        assertEquals(listOf("a", "b"), browser.entries.map { it.text }, "nothing changes on a refusal")
    }

    @Test
    fun aJumpLeavesAGapThatTheFrontierFillsFirst() {
        val browser = CandidateBrowser()
        browser.reset(page(0, listOf("a", "b")))
        val far = page(3, listOf("g", "h"))
        browser.reset(far)
        assertTrue(browser.follow(far))
        assertEquals(1, browser.nextPageToLoad)
        assertEquals(listOf("0:0", "0:1", "3:0", "3:1"), browser.entries.map { it.id })
    }

    @Test
    fun nothingIsFollowedWithoutAList() {
        val browser = CandidateBrowser()
        assertFalse(browser.follow(page(0, listOf("a"))))
    }

    @Test
    fun absoluteIndexContract() {
        assertEquals(2, BrowserCandidate.absoluteIndex(pageIndex = 0, pageLocalIndex = 2, pageSize = 5))
        assertEquals(5, BrowserCandidate.absoluteIndex(pageIndex = 1, pageLocalIndex = 0, pageSize = 5))
        assertEquals(14, BrowserCandidate.absoluteIndex(pageIndex = 2, pageLocalIndex = 4, pageSize = 5))
        assertNull(BrowserCandidate.absoluteIndex(pageIndex = 1, pageLocalIndex = 0, pageSize = 0))
        assertNull(BrowserCandidate.absoluteIndex(pageIndex = -1, pageLocalIndex = 0, pageSize = 5))
        assertNull(BrowserCandidate.absoluteIndex(pageIndex = 1, pageLocalIndex = -1, pageSize = 5))
        val entry = BrowserCandidate(pageIndex = 2, pageLocalIndex = 4, candidate = Candidate(index = 4, text = "x"))
        assertEquals(14, entry.absoluteIndex(pageSize = 5))
    }

    @Test
    fun engineSelectionBadgePrefersLabelWithoutFabricatingFallback() {
        fun entry(
            selectKey: String? = null,
            selectLabel: String? = null,
            comment: String = "",
        ) = BrowserCandidate(
            pageIndex = 0,
            pageLocalIndex = 0,
            candidate =
                Candidate(
                    index = 0,
                    text = "x",
                    comment = comment,
                    selectKey = selectKey,
                    selectLabel = selectLabel,
                ),
        )
        assertEquals("1", entry(selectKey = "a", selectLabel = "1").selectionBadge)
        assertEquals("a", entry(selectKey = "a", selectLabel = "  ").selectionBadge)
        assertNull(entry(comment = "comment is not a label").selectionBadge)
    }
}
