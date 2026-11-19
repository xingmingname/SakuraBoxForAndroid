package io.nekohasekai.sagernet.ui.profile

import io.nekohasekai.sagernet.fmt.sudoku.SudokuOptions

/** Presentation only; the option schema and saved JSON are independent of grouping. */
internal object SudokuPreferenceLayout {
    enum class Section(val collapsed: Boolean = false) {
        BASIC, HTTPMASK, ADVANCED(true), DIAL(true), RESOLVER(true), LEGACY(true)
    }

    private val basic = listOf("key", "aead", "ascii", "multiplex", "table_limit")
    private val advanced = listOf("padding_min", "padding_max", "enable_pure_downlink", "custom_table", "custom_tables")

    fun fields(section: Section): List<SudokuOptions.Field> = when (section) {
        Section.BASIC -> basic.map { path -> SudokuOptions.fields.single { it.path == path } }
        Section.ADVANCED -> advanced.map { path -> SudokuOptions.fields.single { it.path == path } }
        else -> SudokuOptions.fields.filter { field -> field.group == when (section) {
            Section.HTTPMASK -> SudokuOptions.Group.HTTPMASK
            Section.DIAL -> SudokuOptions.Group.DIAL
            Section.RESOLVER -> SudokuOptions.Group.RESOLVER
            Section.LEGACY -> SudokuOptions.Group.LEGACY
            else -> error("Explicit section")
        } }
    }
}
