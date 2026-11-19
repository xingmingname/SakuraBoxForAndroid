package io.nekohasekai.sagernet.ui.profile.editor

internal class JsonSearchMatches(
    private val offsets: IntArray,
    val queryLength: Int,
    val textLength: Int,
) {
    val size get() = offsets.size
    fun start(index: Int) = offsets[index]
    fun end(index: Int) = offsets[index] + queryLength

    fun indexAtOrAfter(offset: Int): Int {
        if (size == 0) return -1
        return lowerBound(offset).let { if (it == size) 0 else it }
    }

    fun navigate(current: Int, backwards: Boolean): Int {
        if (size == 0) return -1
        if (current !in offsets.indices) return if (backwards) size - 1 else 0
        return if (backwards) { if (current == 0) size - 1 else current - 1 }
        else { if (current == size - 1) 0 else current + 1 }
    }

    fun forEachVisibleRange(start: Int, end: Int, draw: (Int, Int) -> Unit) {
        if (size == 0 || start >= end) return
        var i = lowerBound((start - queryLength + 1).coerceAtLeast(0))
        val limit = lowerBound(end)
        while (i < limit) {
            val left = offsets[i].coerceAtLeast(start)
            var right = offsets[i] + queryLength
            var last = i
            while (right < end) {
                val next = (upperBound(right) - 1).coerceAtMost(limit - 1)
                if (next <= last) break
                last = next
                right = offsets[last] + queryLength
            }
            draw(left, right.coerceAtMost(end))
            if (right >= end) break
            i = last + 1
        }
    }

    private fun lowerBound(value: Int): Int {
        var low = 0
        var high = size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (offsets[mid] < value) low = mid + 1 else high = mid
        }
        return low
    }

    private fun upperBound(value: Int): Int {
        var low = 0
        var high = size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (offsets[mid] <= value) low = mid + 1 else high = mid
        }
        return low
    }

    companion object {
        val EMPTY = JsonSearchMatches(IntArray(0), 0, 0)
    }
}

internal object JsonEditorSearch {
    fun find(text: CharSequence, query: String, checkCancelled: () -> Unit = {}): JsonSearchMatches {
        if (query.isEmpty() || query.length > text.length) {
            return JsonSearchMatches(IntArray(0), query.length, text.length)
        }
        val pattern = CharArray(query.length) { fold(query[it]) }
        val prefix = IntArray(pattern.size)
        var matched = 0
        for (i in 1 until pattern.size) {
            if (i and 2047 == 0) checkCancelled()
            while (matched > 0 && pattern[i] != pattern[matched]) matched = prefix[matched - 1]
            if (pattern[i] == pattern[matched]) matched++
            prefix[i] = matched
        }
        var positions = IntArray(minOf(64, text.length))
        var count = 0
        matched = 0
        for (i in text.indices) {
            if (i and 2047 == 0) checkCancelled()
            val char = fold(text[i])
            while (matched > 0 && char != pattern[matched]) matched = prefix[matched - 1]
            if (char == pattern[matched]) matched++
            if (matched == pattern.size) {
                if (count == positions.size) {
                    positions = positions.copyOf(minOf(text.length, maxOf(1, positions.size * 2)))
                }
                positions[count++] = i - pattern.size + 1
                matched = prefix[matched - 1]
            }
        }
        checkCancelled()
        return JsonSearchMatches(positions.copyOf(count), query.length, text.length)
    }

    private fun fold(char: Char) = char.uppercaseChar().lowercaseChar()
}

internal class JsonEditorSearchState {
    var visible = false
        private set
    var revision = 0L
        private set
    var matches = JsonSearchMatches.EMPTY
        private set
    var index = -1
        private set
    var anchor = 0
        private set

    fun open(offset: Int = 0) {
        visible = true
        invalidate(offset)
    }

    fun invalidate(offset: Int = if (index >= 0) matches.start(index) else anchor): Long {
        revision++
        anchor = offset.coerceAtLeast(0)
        matches = JsonSearchMatches.EMPTY
        index = -1
        return revision
    }

    fun edited(start: Int, before: Int, count: Int): Long {
        val old = if (index >= 0) matches.start(index) else anchor
        val offset = when {
            old < start -> old
            old >= start + before -> old + count - before
            else -> start
        }
        return invalidate(offset)
    }

    fun accept(request: Long, results: JsonSearchMatches): Boolean {
        if (!visible || revision != request) return false
        matches = results
        index = results.indexAtOrAfter(anchor)
        if (index >= 0) anchor = results.start(index)
        return true
    }

    fun navigate(backwards: Boolean) {
        index = matches.navigate(index, backwards)
        if (index >= 0) anchor = matches.start(index)
    }

    fun close() {
        visible = false
        invalidate(0)
    }
}
