package io.nekohasekai.sagernet.ui.profile

import com.google.gson.JsonObject
import io.nekohasekai.sagernet.fmt.sudoku.SudokuOptions

/** Compare read-only render snapshots; all writes remain in SudokuOptions. */
internal object SudokuPreferenceUpdates {
    /** Main-thread queue; a stale view callback cannot drain a newer view's edits. */
    class Pending {
        private var target: Any? = null
        private val fields = mutableSetOf<String>()

        fun request(target: Any, field: String?): Boolean {
            val schedule = this.target !== target
            if (schedule) { fields.clear(); this.target = target }
            if (field != null) fields.add(field)
            return schedule
        }

        fun drain(target: Any): Set<String>? {
            if (this.target !== target) return null
            this.target = null
            return fields.toSet().also { fields.clear() }
        }

        fun clear() { target = null; fields.clear() }
    }

    fun changedFields(before: JsonObject, after: JsonObject, forced: Set<String> = emptySet()) =
        SudokuOptions.fields.filter { field ->
            field.path in forced || SudokuOptions.raw(before, field.path) != SudokuOptions.raw(after, field.path) ||
                SudokuOptions.editable(before, field) != SudokuOptions.editable(after, field)
        }

    fun canUnset(root: JsonObject, field: SudokuOptions.Field) =
        field.kind in setOf(SudokuOptions.Kind.BOOLEAN, SudokuOptions.Kind.LIST) &&
            SudokuOptions.raw(root, field.path) != null && SudokuOptions.editable(root, field)
}
