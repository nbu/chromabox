package com.bnsoft.chromabox

import com.bnsoft.chromabox.engine.LensAnnotation

/**
 * Color sequences (C-04.3): three or more colors on one line with only commas, spaces or an arrow (`→`, `->`)
 * between them, like a palette list in Markdown. They get one strip after the last color, besides their swatches.
 */
object ColorSequences {
    private const val MIN_COLORS = 3
    private val SEPARATOR = Regex("""[ \t,]*(?:(?:→|->)[ \t,]*)?""")

    fun find(text: CharSequence, colors: List<ColorAt>): List<List<ColorAt>> {
        val sorted = colors.sortedBy { it.range.startOffset }
        val sequences = ArrayList<List<ColorAt>>()
        var run = ArrayList<ColorAt>()
        for (color in sorted) {
            val previous = run.lastOrNull()
            if (previous != null && !SEPARATOR.matches(text.subSequence(previous.range.endOffset, color.range.startOffset))) {
                if (run.size >= MIN_COLORS) sequences += run
                run = ArrayList()
            }
            run += color
        }
        if (run.size >= MIN_COLORS) sequences += run
        return sequences
    }

    fun annotations(text: CharSequence, colors: List<ColorAt>): List<LensAnnotation> = find(text, colors).map { sequence ->
        val awt = sequence.map { it.color.toAwt() }
        LensAnnotation(sequence.last().range.endOffset, awt, ColorStripRenderer(awt.map { 0.0 to it }, segments = true))
    }
}
