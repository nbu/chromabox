package com.bnsoft.chromabox

import com.bnsoft.chromabox.color.ChromaColor
import com.bnsoft.chromabox.engine.Lens
import com.bnsoft.chromabox.engine.LensContext
import com.bnsoft.chromabox.engine.LensEditor
import com.bnsoft.chromabox.engine.LensMatch
import com.intellij.openapi.editor.EditorCustomElementRenderer

/** Standalone `0xAARRGGBB`, or `0xRRGGBBAA` if so set. */
class IntegerColorLens : Lens<ChromaColor> {
    override val id = ColorFormat.INTEGER.lensId

    override fun find(text: CharSequence, context: LensContext): List<LensMatch<ChromaColor>> =
        IntegerColors.findAll(text, ChromaboxSettings.getInstance().state.intOrder).map { LensMatch(it.range, it.color, it.format) }

    override fun renderer(match: LensMatch<ChromaColor>): EditorCustomElementRenderer = ColorSwatchRenderer.of(match.value)

    override fun editor(match: LensMatch<ChromaColor>): LensEditor<ChromaColor> = EDITOR

    private companion object {
        val EDITOR = ColorLensEditor { color, original -> IntegerColors.format(color.toAwt(), original.format as IntColorFormat) }
    }
}

/** Compose, Flutter, AWT, UIKit, AppKit and SwiftUI color constructors. */
class ConstructorColorLens : Lens<ChromaColor> {
    override val id = ColorFormat.CONSTRUCTORS.lensId

    override fun find(text: CharSequence, context: LensContext): List<LensMatch<ChromaColor>> =
        ConstructorColors.findAll(text).map { LensMatch(it.range, it.color, it.format) }

    override fun renderer(match: LensMatch<ChromaColor>): EditorCustomElementRenderer = ColorSwatchRenderer.of(match.value)

    override fun editor(match: LensMatch<ChromaColor>): LensEditor<ChromaColor> = EDITOR

    private companion object {
        val EDITOR = ColorLensEditor { color, original -> ConstructorColors.format(color.toAwt(), original.format as ConstructorFormat) }
    }
}
