package com.bnsoft.chromabox.engine

import com.intellij.openapi.Disposable
import com.intellij.openapi.fileTypes.FileType

/** How the engine shows matches. */
enum class LensPresentation {
    /** The renderer replaces the literal, which is folded away unless a caret touches it. */
    INLAY_ONLY,

    /** The renderer is shown before the literal. */
    INLAY_WITH_TEXT,
    OFF,
}

/**
 * What the plugin that bundles the engine provides: its lenses, settings and lifetime. Keeps the engine free of any
 * plugin's settings or extension point names.
 */
interface LensHost {
    /** Parent of every [EditorLensController]; disposed when the plugin is unloaded. */
    val parentDisposable: Disposable

    val presentation: LensPresentation

    /** Enabled lenses, in registration order. Earlier lenses win ties between overlapping matches. */
    val lenses: List<Lens<*>>

    /** Every lens, enabled or not; recognizes folds the engine left behind in an earlier session. */
    val allLenses: List<Lens<*>> get() = lenses

    fun isExcluded(fileType: FileType): Boolean

    /**
     * Whether the IDE already shows [match] in its own way, so the engine leaves it alone. Called during a scan, in a
     * read action, with the document's PSI committed when the editor has a project.
     */
    fun isShownByIde(match: ScannedMatch<*>, context: LensContext): Boolean = false

    /** Calls [listener] whenever [presentation], [lenses] or exclusions may have changed, until [disposable] is disposed. */
    fun subscribe(disposable: Disposable, listener: () -> Unit)

    /** Documents longer than this aren't scanned. */
    val maxDocumentLength: Int get() = 2_000_000
}
