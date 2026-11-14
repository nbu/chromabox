package com.bnsoft.chromabox

import com.bnsoft.chromabox.color.ChromaColor
import com.bnsoft.chromabox.engine.Lens
import com.bnsoft.chromabox.engine.LensContext
import com.bnsoft.chromabox.engine.LensEditor
import com.bnsoft.chromabox.engine.LensMatch
import com.intellij.openapi.editor.EditorCustomElementRenderer

/** `#RGB`, `#RGBA`, `#RRGGBB`, `#RRGGBBAA`. */
class HexColorLens : Lens<ChromaColor> {
    override val id = ColorFormat.HEX.lensId

    override fun find(text: CharSequence, context: LensContext): List<LensMatch<ChromaColor>> =
        HexColorParser.findAll(text, ChromaboxSettings.getInstance().state.shortForms)
            .map { LensMatch(it.range, ChromaColor.fromAwt(it.color), it.format) }

    override fun renderer(match: LensMatch<ChromaColor>): EditorCustomElementRenderer = ColorSwatchRenderer.of(match.value)

    override fun editor(match: LensMatch<ChromaColor>): LensEditor<ChromaColor> = EDITOR

    private companion object {
        val EDITOR = ColorLensEditor { color, original -> HexColorParser.format(color.toAwt(), original.format as HexFormat) }
    }
}
