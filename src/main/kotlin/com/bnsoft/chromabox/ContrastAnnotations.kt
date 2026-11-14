package com.bnsoft.chromabox

import com.bnsoft.chromabox.color.ChromaColor
import com.bnsoft.chromabox.color.Contrast
import com.bnsoft.chromabox.color.ContrastResult
import com.bnsoft.chromabox.engine.LensAnnotation
import com.bnsoft.chromabox.engine.ScannedMatch

/** Contrast badges for the colors of one scan (C-02): one after each pair of colors that belong together. */
object ContrastAnnotations {
    fun of(matches: List<ScannedMatch<*>>, text: CharSequence, setting: ContrastBadges): List<LensAnnotation> {
        if (setting == ContrastBadges.OFF) return emptyList()
        val colors = matches.mapNotNull { m -> (m.match.value as? ChromaColor)?.let { ColorAt(m.range, it) } }
        return ContrastPairs.find(text, colors).mapNotNull { pair ->
            val result = Contrast.of(pair.foreground.color, pair.background.color)
            if (setting == ContrastBadges.ONLY_FAILING && result.ratio >= ContrastResult.AA) return@mapNotNull null
            LensAnnotation(pair.second.range.endOffset, result, ContrastBadgeRenderer(result))
        }
    }
}
