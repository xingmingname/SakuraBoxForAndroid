package io.nekohasekai.sagernet.ui.profile

import com.google.gson.JsonObject
import io.nekohasekai.sagernet.fmt.ProtocolOptions
import io.nekohasekai.sagernet.fmt.sudoku.SudokuOptions
import org.junit.Assert.*
import org.junit.Test

class SudokuPreferenceUpdatesTest {
    private fun root(raw: String) = ProtocolOptions.parse(raw)
    private fun field(path: String) = SudokuOptions.fields.single { it.path == path }
    private fun paths(before: JsonObject, after: JsonObject, forced: Set<String> = emptySet()) =
        SudokuPreferenceUpdates.changedFields(before, after, forced).map { it.path }.toSet()

    @Test fun storeNotificationAndUserEditScheduleOneBatchWithAllForcedFields() {
        val pending = SudokuPreferenceUpdates.Pending()
        val view = Any()
        assertTrue(pending.request(view, null))
        assertFalse(pending.request(view, "aead"))
        assertFalse(pending.request(view, "aead"))
        assertFalse(pending.request(view, "table_limit"))
        assertEquals(setOf("aead", "table_limit"), pending.drain(view))
        assertNull(pending.drain(view))
        assertTrue(pending.request(view, "ascii"))
    }

    @Test fun oldViewCallbackCannotConsumeOrOverwriteNewViewsPendingEdits() {
        val pending = SudokuPreferenceUpdates.Pending()
        val oldView = Any(); val newView = Any()
        assertTrue(pending.request(oldView, "aead"))
        assertTrue(pending.request(newView, "ascii"))
        assertNull(pending.drain(oldView))
        assertEquals(setOf("ascii"), pending.drain(newView))
    }

    @Test fun lifecycleClearCancelsAnOldBatchAndDoesNotCarryFieldsIntoReopen() {
        val pending = SudokuPreferenceUpdates.Pending()
        val view = Any()
        pending.request(view, "key")
        pending.clear()
        assertNull(pending.drain(view))
        assertTrue(pending.request(view, null))
        assertEquals(emptySet<String>(), pending.drain(view))
    }

    @Test fun unchangedOrReformattedContentHasNoFieldUpdates() {
        val before = root("""{"aead":"none","padding_min":0,"httpmask":{"tls":false}}""")
        assertTrue(paths(before, before).isEmpty())
        assertTrue(paths(before, root("""{ "httpmask": { "tls": false }, "padding_min": 0, "aead": "none" }""")).isEmpty())
    }

    @Test fun ordinaryEditsTouchOnlyTheirOwnRowAndDoNotMutatePreviousSnapshot() {
        val before = root("""{"key":"synthetic-key","aead":"none","ascii":"prefer_entropy","table_limit":64,"padding_min":0,"future":{"keep":true}}""")
        val saved = before.deepCopy()
        for ((path, value) in listOf("aead" to "aes-128-gcm", "ascii" to "prefer_ascii",
            "table_limit" to "512", "padding_min" to "1", "key" to "new-synthetic-key")) {
            val after = SudokuOptions.set(before, field(path), value)
            assertEquals(setOf(path), paths(before, after))
            assertEquals(saved, before)
            assertEquals(before["future"], after["future"])
        }
    }

    @Test fun repeatedMixedEditsKeepOrdinaryRowsEditableAndSelectOnlyChangedPaths() {
        var current = root("{}")
        var count = 0
        repeat(20) {
            for ((path, value) in listOf("aead" to "none", "ascii" to "prefer_ascii",
                "table_limit" to "128", "padding_min" to it.toString(), "aead" to "aes-128-gcm",
                "ascii" to "prefer_entropy", "table_limit" to "0")) {
                val after = SudokuOptions.set(current, field(path), value)
                val changed = paths(current, after, setOf(path))
                assertEquals(setOf(path), changed)
                for (f in SudokuOptions.fields) assertEquals(SudokuOptions.editable(current, f), SudokuOptions.editable(after, f))
                current = after; count++
            }
        }
        assertEquals(140, count)
        println("Sudoku incremental updates: $count edits; one target row per edit; no ordinary row type replacement")
    }

    @Test fun forcedRowStillSynchronizesIfAnEditNormalizesToTheSameJsonValue() {
        val before = root("""{"padding_min":1}""")
        val after = SudokuOptions.set(before, field("padding_min"), "01")
        assertTrue(paths(before, after).isEmpty())
        assertEquals(setOf("padding_min"), paths(before, after, setOf("padding_min")))
    }

    @Test fun resolverChangesUpdateOnlyValuesOrEditabilityThatDependOnThatParent() {
        val before = root("""{"domain_resolver":"dns"}""")
        val withTimeout = SudokuOptions.set(before, field("domain_resolver.timeout"), "1s")
        assertEquals(setOf("domain_resolver", "domain_resolver.timeout"), paths(before, withTimeout))
        val unsupported = root("""{"domain_resolver":null}""")
        val resolverFields = SudokuOptions.fields.filter { it.path == "domain_resolver" || it.path.startsWith("domain_resolver.") }.map { it.path }.toSet()
        assertEquals(resolverFields, paths(before, unsupported))
        assertEquals(resolverFields, paths(unsupported, before))
    }

    @Test fun unsetActionVisibilityRetainsOmissionFalseAndEmptyListSemantics() {
        val omitted = root("{}")
        for ((path, text) in listOf("enable_pure_downlink" to "false", "custom_tables" to "", "network_type" to "")) {
            val f = field(path)
            assertFalse(SudokuPreferenceUpdates.canUnset(omitted, f))
            val present = SudokuOptions.set(omitted, f, text)
            assertTrue(SudokuPreferenceUpdates.canUnset(present, f))
            assertEquals(setOf(path), paths(omitted, present))
            val unset = SudokuOptions.set(present, f, null)
            assertFalse(SudokuPreferenceUpdates.canUnset(unset, f))
            assertEquals(omitted, unset)
        }
    }

    @Test fun unrelatedRetainedFieldsDoNotRebindAnyKnownOption() {
        val before = root("""{"aead":"none","future":{"enabled":false}}""")
        val after = root("""{"aead":"none","future":{"enabled":true},"extra":0}""")
        assertTrue(paths(before, after).isEmpty())
        assertEquals(listOf("future", "extra"), SudokuOptions.retainedFields(after))
        assertEquals("none", SudokuOptions.text(after, field("aead")))
    }
}
