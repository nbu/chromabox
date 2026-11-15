package com.bnsoft.chromabox

import com.bnsoft.chromabox.engine.LensContext
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.ElementColorProvider
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil

/**
 * Finds colors the IDE already shows a swatch for (C-01.g), such as AWT `new Color(…)` in Java, so they don't get a
 * second one. The IDE's swatches come from an [ElementColorProvider] that returns a color for some PSI element; that
 * element is checked if it's in the match, or contains it without reaching past the match's lines. Colors in
 * injected code, like CSS in an HTML `style` attribute, are checked in the injected PSI.
 *
 * CSS draws its swatches without an [ElementColorProvider], which is why CSS-like files are skipped. So a color in
 * injected code of a skipped file type, like a Markdown ```css fence, counts as shown too.
 */
object IdeColorSwatches {
    private val LOG = logger<IdeColorSwatches>()

    /** Whether the IDE shows a swatch for the color at a range of [context]'s document; null if it shows none there. */
    fun shownIn(context: LensContext, isExcluded: (FileType) -> Boolean): ((TextRange) -> Boolean)? {
        val project = context.project ?: return null
        val providers = ElementColorProvider.EP_NAME.extensionList
        val documentManager = PsiDocumentManager.getInstance(project)
        val document = context.document
        if (!documentManager.isCommitted(document)) return null
        val file = documentManager.getPsiFile(document) ?: return null
        val injected = InjectedLanguageManager.getInstance(project)
        return { range ->
            val injectedAt = injected.findInjectedElementAt(file, range.startOffset)
            injectedAt != null && isExcluded(injectedAt.containingFile.fileType) ||
                shownByProvider(range, file, injectedAt, injected, providers, document)
        }
    }

    private fun shownByProvider(
        range: TextRange, file: PsiFile, injectedAt: PsiElement?, injected: InjectedLanguageManager,
        providers: List<ElementColorProvider>, document: Document,
    ): Boolean {
        if (providers.isEmpty()) return false
        val lines = TextRange(
            document.getLineStartOffset(document.getLineNumber(range.startOffset)),
            document.getLineEndOffset(document.getLineNumber(range.endOffset)),
        )
        val elements = candidates(file.findElementAt(range.startOffset), range, lines) { it.textRange } +
            candidates(injectedAt, range, lines) { injected.injectedToHost(it, it.textRange) }
        return elements.any { element -> providers.any { it.hasColor(element) } }
    }

    /** The leaves from [first] on that start in [range], and their ancestors within [lines], in host offsets. */
    private fun candidates(first: PsiElement?, range: TextRange, lines: TextRange, hostRange: (PsiElement) -> TextRange) =
        generateSequence(first, PsiTreeUtil::nextLeaf)
            .takeWhile { hostRange(it).startOffset < range.endOffset }
            .flatMap { leaf -> generateSequence(leaf) { it.parent }.takeWhile { it !is PsiFile && hostRange(it) in lines } }
            .distinct()

    private fun ElementColorProvider.hasColor(element: PsiElement): Boolean = try {
        getColorFrom(element) != null
    } catch (e: Exception) {
        if (e is ControlFlowException) throw e
        LOG.warn("${javaClass.name} failed on ${element.javaClass.name}", e)
        false
    }
}
