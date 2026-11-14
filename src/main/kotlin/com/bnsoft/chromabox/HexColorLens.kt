package com.bnsoft.chromabox

import com.bnsoft.chromabox.engine.Lens
import com.bnsoft.chromabox.engine.LensContext
import com.bnsoft.chromabox.engine.LensEditor
import com.bnsoft.chromabox.engine.LensMatch
import com.intellij.openapi.editor.EditorCustomElementRenderer
import java.awt.Color

/** `#RGB`, `#RGBA`, `#RRGGBB`, `#RRGGBBAA`. */
class HexColorLens : Lens<Color> {
    override val id = "color.hex"

    override fun find(text: CharSequence, context: LensContext): List<LensMatch<Color>> =
        HexColorParser.findAll(text, ChromaboxSettings.getInstance().state.shortForms)
            .map { LensMatch(it.range, it.color, it.format) }

    override fun renderer(match: LensMatch<Color>): EditorCustomElementRenderer = ColorSwatchRenderer(match.value)

    override fun editor(match: LensMatch<Color>): LensEditor<Color> = EDITOR

    private companion object {
        val EDITOR = ColorLensEditor { color, original -> HexColorParser.format(color, original.format as HexFormat) }
    }
}
