package com.bnsoft.chromabox

import com.bnsoft.chromabox.color.ChromaColor
import com.bnsoft.chromabox.engine.Lens
import com.bnsoft.chromabox.engine.LensContext
import com.bnsoft.chromabox.engine.LensEditor
import com.bnsoft.chromabox.engine.LensMatch
import com.intellij.openapi.editor.EditorCustomElementRenderer

/** `rgb()`, `rgba()`, `hsl()`, `hsla()`, `hwb()`, legacy and modern syntax. */
class CssFunctionColorLens : Lens<ChromaColor> {
    override val id = ColorFormat.CSS_FUNCTIONS.lensId

    override fun find(text: CharSequence, context: LensContext): List<LensMatch<ChromaColor>> =
        CssColorFunctions.findAll(text).map { LensMatch(it.range, it.color, it.format) }

    override fun renderer(match: LensMatch<ChromaColor>): EditorCustomElementRenderer = ColorSwatchRenderer(match.value.toAwt())

    override fun editor(match: LensMatch<ChromaColor>): LensEditor<ChromaColor> = EDITOR

    private companion object {
        val EDITOR = ColorLensEditor { color, original -> CssColorFunctions.format(color, original.format as CssFunctionFormat) }
    }
}
