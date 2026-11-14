package com.bnsoft.chromabox

/**
 * Matches of this regex that start at one of [starts] (ascending), skipping starts inside an earlier match, like
 * [Regex.findAll]. Much faster for a regex that starts with a lookbehind, which [Regex.findAll] tries at every offset:
 * callers pass only the offsets where the literal can start, found with a cheap character search (N-02).
 */
internal fun Regex.findAllAt(text: CharSequence, starts: Sequence<Int>): Sequence<MatchResult> {
    var end = 0
    return starts.mapNotNull { start ->
        if (start < end) return@mapNotNull null
        matchAt(text, start)?.also { end = it.range.last + 1 }
    }
}

// Plain loops: the standard library's indexOf on a CharSequence that isn't a String is several times slower.

/** Offsets of [char] in this text, ascending. */
internal fun CharSequence.offsetsOf(char: Char): Sequence<Int> = offsetsOf(char, char)

/** Offsets of either character, ascending. */
internal fun CharSequence.offsetsOf(a: Char, b: Char): Sequence<Int> =
    generateSequence(indexOf(a, b, 0)) { indexOf(a, b, it + 1) }.takeWhile { it >= 0 }

private fun CharSequence.indexOf(a: Char, b: Char, from: Int): Int {
    for (i in from until length) {
        val c = this[i]
        if (c == a || c == b) return i
    }
    return -1
}

/** Offsets of [string] in this text, ascending. */
internal fun CharSequence.offsetsOf(string: String): Sequence<Int> =
    offsetsOf(string[0]).filter { startsWith(string, it) }

/** Where the run of ASCII letters that ends right before [offset] starts; [offset] if there's none. */
internal fun CharSequence.lettersStart(offset: Int): Int {
    var start = offset
    while (start > 0 && this[start - 1].let { it in 'a'..'z' || it in 'A'..'Z' }) start--
    return start
}
