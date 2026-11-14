package com.bnsoft.chromabox.engine

import com.intellij.lang.Language
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.ui.awt.RelativePoint
import org.jetbrains.annotations.Nls

/** One kind of literal the engine can detect, render and edit. */
interface Lens<V : Any> {
    /** Stable, used in settings: "color.hex", "color.css-func". */
    val id: String

    fun find(text: CharSequence, context: LensContext): List<LensMatch<V>>

    fun renderer(match: LensMatch<V>): EditorCustomElementRenderer

    /** Null for a read-only lens. */
    fun editor(match: LensMatch<V>): LensEditor<V>?
}

/** A literal at [range]. [format] is lens-specific and opaque to the engine: how the literal was written. */
data class LensMatch<V : Any>(val range: TextRange, val value: V, val format: Any)

interface LensEditor<V : Any> {
    /** Name of the undoable command that writes edited values back. */
    val commandName: @Nls String

    /** Shows UI near [location]; calls [onChange] for every intermediate value. */
    fun show(project: Project?, current: V, location: RelativePoint, onChange: (V) -> Unit, onClose: () -> Unit)

    /** Formats [value] the way the original literal was written. Null = cannot represent it. */
    fun format(value: V, original: LensMatch<V>): String?
}

data class LensContext(val fileType: FileType, val language: Language?, val document: Document)
