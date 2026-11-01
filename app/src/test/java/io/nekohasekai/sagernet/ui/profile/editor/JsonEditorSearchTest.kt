package io.nekohasekai.sagernet.ui.profile.editor

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class JsonEditorSearchTest {
    private fun positions(matches: JsonSearchMatches) = (0 until matches.size).map { matches.start(it) }
    private fun ranges(matches: JsonSearchMatches, start: Int, end: Int): List<Pair<Int, Int>> {
        val result = mutableListOf<Pair<Int, Int>>()
        matches.forEachVisibleRange(start, end) { a, b -> result += a to b }
        return result
    }

    @Test fun emptyQueryAndEmptyDocumentHaveNoResults() {
        assertEquals(0, JsonEditorSearch.find("", "a").size)
        assertEquals(0, JsonEditorSearch.find("abc", "").size)
        assertEquals(0, JsonEditorSearch.find("", "").size)
    }

    @Test fun textSearchIgnoresCaseAndFindsAllMatches() {
        assertEquals(listOf(0, 4, 8), positions(JsonEditorSearch.find("TLS tls TlS", "tls")))
    }

    @Test fun chineseNumbersAndSpecialCharactersAreLiteral() {
        val text = "中文123 [a.*] 中文123 [a.*]"
        assertEquals(listOf(0, 12), positions(JsonEditorSearch.find(text, "中文123")))
        assertEquals(listOf(6, 18), positions(JsonEditorSearch.find(text, "[a.*]")))
        assertEquals(0, JsonEditorSearch.find(text, "[A.+]").size)
    }

    @Test fun whitespaceAndMultilineQueriesKeepExactCharacters() {
        assertEquals(listOf(1, 3), positions(JsonEditorSearch.find("a b c", " ")))
        val result = JsonEditorSearch.find("a\r\nb\na\r\nb", "a\r\nb")
        assertEquals(listOf(0, 5), positions(result))
        assertEquals(4, result.queryLength)
    }

    @Test fun supplementaryCharactersUseAndroidUtf16Offsets() {
        val result = JsonEditorSearch.find("🌸a🌸A", "🌸a")
        assertEquals(listOf(0, 3), positions(result))
        assertEquals(3, result.queryLength)
        assertEquals(6, result.end(1))
    }

    @Test fun caseMatchingDoesNotDependOnDefaultLocale() {
        assertEquals(4, JsonEditorSearch.find("i I İ ı", "i").size)
    }

    @Test fun overlappingMatchesNavigateInOneConsistentOrder() {
        val result = JsonEditorSearch.find("aaaa", "aa")
        assertEquals(listOf(0, 1, 2), positions(result))
        assertEquals(1, result.navigate(0, false))
        assertEquals(2, result.navigate(1, false))
        assertEquals(0, result.navigate(2, false))
        assertEquals(2, result.navigate(0, true))
        assertEquals(1, result.navigate(2, true))
    }

    @Test fun noMatchAndSingleMatchNavigationAreSafe() {
        assertEquals(-1, JsonEditorSearch.find("abc", "z").navigate(-1, false))
        val one = JsonEditorSearch.find("abc", "b")
        assertEquals(0, one.navigate(0, false))
        assertEquals(0, one.navigate(0, true))
        assertEquals(0, one.navigate(-1, false))
    }

    @Test fun anchorChoosesNextMatchThenWraps() {
        val matches = JsonEditorSearch.find("x---x---x", "x")
        assertEquals(0, matches.indexAtOrAfter(0))
        assertEquals(1, matches.indexAtOrAfter(1))
        assertEquals(2, matches.indexAtOrAfter(8))
        assertEquals(0, matches.indexAtOrAfter(9))
    }

    @Test fun visibleHighlightsAreClippedAndMerged() {
        assertEquals(listOf(2 to 5), ranges(JsonEditorSearch.find("aaaaaa", "aaa"), 2, 5))
        assertEquals(listOf(4 to 6), ranges(JsonEditorSearch.find("ab--ab--ab", "ab"), 3, 8))
        assertEquals(emptyList<Pair<Int, Int>>(), ranges(JsonEditorSearch.find("abc", "z"), 0, 3))
        assertEquals(emptyList<Pair<Int, Int>>(), ranges(JsonEditorSearch.find("abc", "b"), 0, 0))
    }

    @Test fun workerCannotApplyResultsAfterSearchCloses() {
        val state = JsonEditorSearchState()
        state.open()
        val request = state.revision
        state.close()
        assertFalse(state.accept(request, JsonEditorSearch.find("abc", "a")))
        assertFalse(state.visible)
        assertEquals(0, state.matches.size)
        assertEquals(-1, state.index)
    }

    @Test fun newerQueryAndEditInvalidateOldWorkerResults() {
        val state = JsonEditorSearchState()
        state.open()
        val old = state.revision
        val newer = state.invalidate()
        assertFalse(state.accept(old, JsonEditorSearch.find("abc", "a")))
        assertTrue(state.accept(newer, JsonEditorSearch.find("abc", "b")))
        state.edited(0, 0, 2)
        assertEquals(0, state.matches.size)
        assertEquals(-1, state.index)
        assertFalse(state.accept(newer, JsonEditorSearch.find("abc", "b")))
    }

    @Test fun editingBeforeCurrentMatchRetainsItsShiftedAnchor() {
        val state = JsonEditorSearchState()
        state.open()
        assertTrue(state.accept(state.revision, JsonEditorSearch.find("x--x", "x")))
        state.navigate(false)
        val request = state.edited(0, 0, 2)
        assertEquals(5, state.anchor)
        assertTrue(state.accept(request, JsonEditorSearch.find("--x--x", "x")))
        assertEquals(1, state.index)
        assertEquals(5, state.matches.start(state.index))
    }

    @Test fun deletingTheCurrentMatchLeavesNoInvalidIndex() {
        val state = JsonEditorSearchState()
        state.open()
        state.accept(state.revision, JsonEditorSearch.find("abc", "b"))
        val request = state.edited(1, 1, 0)
        assertTrue(state.accept(request, JsonEditorSearch.find("ac", "b")))
        state.navigate(false)
        state.navigate(true)
        assertEquals(-1, state.index)
        assertEquals(0, state.matches.size)
    }

    @Test fun openingAgainCannotReviveAClosedRequest() {
        val state = JsonEditorSearchState()
        state.open()
        val old = state.revision
        state.close()
        state.open(4)
        assertFalse(state.accept(old, JsonEditorSearch.find("a---a", "a")))
        assertTrue(state.accept(state.revision, JsonEditorSearch.find("a---a", "a")))
        assertEquals(1, state.index)
    }

    @Test fun searchNeverChangesTheDocument() {
        val doc = StringBuilder("{\"server\":\"中文\"}\n")
        val original = doc.toString()
        JsonEditorSearch.find(doc, "server").forEachVisibleRange(0, doc.length) { _, _ -> }
        assertEquals(original, doc.toString())
    }

    @Test fun prefixAndDocumentScanningCanBeCancelled() {
        var calls = 0
        try {
            JsonEditorSearch.find("a".repeat(100000), "a".repeat(10000)) {
                if (++calls >= 2) throw CancellationException()
            }
            fail("Cancelled prefix scan must stop")
        } catch (_: CancellationException) { }
        try {
            JsonEditorSearch.find("a".repeat(100000), "b") { throw CancellationException() }
            fail("Cancelled document scan must stop")
        } catch (_: CancellationException) { }
    }

    @Test fun largeDocumentsAndDenseResultsStayCorrect() {
        val text = "ABC中文.123\n".repeat(100000)
        val matches = JsonEditorSearch.find(text, "中文")
        assertEquals(100000, matches.size)
        assertEquals(3, matches.start(0))
        assertEquals(999993, matches.start(matches.size - 1))
        val dense = JsonEditorSearch.find("a".repeat(1048576), "a")
        assertEquals(1048576, dense.size)
        assertEquals(listOf(900000 to 902000), ranges(dense, 900000, 902000))
    }

    @Test fun longOverlappingPatternsOnlyDrawTheVisibleUnion() {
        val matches = JsonEditorSearch.find("a".repeat(2097152), "a".repeat(1048576))
        assertEquals(1048577, matches.size)
        assertEquals(listOf(1500000 to 1501000), ranges(matches, 1500000, 1501000))
    }
}
