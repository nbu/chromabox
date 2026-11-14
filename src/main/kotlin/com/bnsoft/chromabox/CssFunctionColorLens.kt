package com.bnsoft.chromabox

import com.bnsoft.chromabox.color.ChromaColor
import com.bnsoft.chromabox.engine.Lens
import com.bnsoft.chromabox.engine.LensContext
import com.bnsoft.chromabox.engine.LensEditor
import com.bnsoft.chromabox.engine.LensMatch
import com.intellij.openapi.editor.EditorCustomElementRenderer

/** CSS color functions in [functions]; [format] is the setting that turns them on or off. */
abstract class CssColorLens(format: ColorFormat, private val functions: Set<CssColorFunction>) : Lens<ChromaColor> {
    override val id = format.lensId

    override fun find(text: CharSequence, context: LensContext): List<LensMatch<ChromaColor>> =
        CssColorFunctions.findAll(text, functions).map { LensMatch(it.range, it.color, it.format) }

    override fun renderer(match: LensMatch<ChromaColor>): EditorCustomElementRenderer = ColorSwatchRenderer.of(match.value)

    override fun editor(match: LensMatch<ChromaColor>): LensEditor<ChromaColor> = EDITOR

    private companion object {
        val EDITOR = ColorLensEditor { color, original -> CssColorFunctions.format(color, original.format as CssFunctionFormat) }
    }
}

/** `rgb()`, `rgba()`, `hsl()`, `hsla()`, `hwb()`, legacy and modern syntax. */
class CssFunctionColorLens : CssColorLens(ColorFormat.CSS_FUNCTIONS, CssColorFunction.LEVEL_3)

/** `lab()`, `lch()`, `oklab()`, `oklch()`, `color(display-p3 …)`, `color(srgb …)`. */
class CssColor4Lens : CssColorLens(ColorFormat.CSS_COLOR_4, CssColorFunction.LEVEL_4)
