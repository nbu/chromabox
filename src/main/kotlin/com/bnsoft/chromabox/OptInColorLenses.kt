package com.bnsoft.chromabox

import com.bnsoft.chromabox.color.ChromaColor
import com.bnsoft.chromabox.engine.Lens
import com.bnsoft.chromabox.engine.LensContext
import com.bnsoft.chromabox.engine.LensEditor
import com.bnsoft.chromabox.engine.LensMatch
import com.intellij.openapi.editor.EditorCustomElementRenderer

/** CSS named colors: `tomato`, `rebeccapurple`. Off by default. */
class NamedColorLens : Lens<ChromaColor> {
    override val id = ColorFormat.NAMED.lensId

    override fun find(text: CharSequence, context: LensContext): List<LensMatch<ChromaColor>> =
        NamedColors.findAll(text).map { LensMatch(it.range, it.color, it.format) }

    override fun renderer(match: LensMatch<ChromaColor>): EditorCustomElementRenderer = ColorSwatchRenderer.of(match.value)

    override fun editor(match: LensMatch<ChromaColor>): LensEditor<ChromaColor> = EDITOR

    private companion object {
        val EDITOR = ColorLensEditor { color, original -> NamedColors.format(color.toAwt(), original.format as NamedColorFormat) }
    }
}

/** Float tuples: `[0.1, 0.2, 0.3]`. Off by default, and only in the file types the user lists. */
class FloatTupleLens : Lens<ChromaColor> {
    override val id = ColorFormat.FLOAT_TUPLES.lensId

    override fun find(text: CharSequence, context: LensContext): List<LensMatch<ChromaColor>> {
        if (!ChromaboxSettings.getInstance().isFloatTupleFileType(context.fileType)) return emptyList()
        return FloatTuples.findAll(text).map { LensMatch(it.range, it.color, it.format) }
    }

    override fun renderer(match: LensMatch<ChromaColor>): EditorCustomElementRenderer = ColorSwatchRenderer.of(match.value)

    override fun editor(match: LensMatch<ChromaColor>): LensEditor<ChromaColor> = EDITOR

    private companion object {
        val EDITOR = ColorLensEditor { color, original -> ConstructorColors.format(color.toAwt(), original.format as ConstructorFormat) }
    }
}
