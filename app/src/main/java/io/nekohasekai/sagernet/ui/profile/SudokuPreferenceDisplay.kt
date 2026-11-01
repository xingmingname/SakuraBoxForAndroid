package io.nekohasekai.sagernet.ui.profile

/** Display only: never substitutes values or writes protocol options. */
internal object SudokuPreferenceDisplay {
    fun text(value: CharSequence?): CharSequence {
        if (value == null) return ""
        // A table list or imported value must not expand one row to thousands of lines.
        // The edit dialog still receives the full, unmodified value.
        var end = minOf(value.length, 160)
        var lines = 1
        for (i in 0 until end) {
            if (value[i] == '\n' && ++lines > 3) { end = i; break }
        }
        if (end == value.length) return value
        if (end > 0 && value[end - 1].isHighSurrogate()) end--
        return value.subSequence(0, end).toString() + "\u2026"
    }

    fun choice(value: String?, entry: CharSequence?, emptyChoice: String): CharSequence =
        if (value.isNullOrEmpty() || value == emptyChoice) "" else text(entry ?: value)

    fun secret(value: String?): String = if (value.isNullOrEmpty()) "" else "\u2022".repeat(minOf(value.length, 16))
}
