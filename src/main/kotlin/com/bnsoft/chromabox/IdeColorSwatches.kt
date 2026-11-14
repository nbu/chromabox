package com.bnsoft.chromabox

import com.bnsoft.chromabox.engine.LensContext
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.ElementColorProvider
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
 */
object IdeColorSwatches {
    private val LOG = logger<IdeColorSwatches>()

    fun isShown(range: TextRange, context: LensContext): Boolean {
        val project = context.project ?: return false
        val providers = ElementColorProvider.EP_NAME.extensionList
        if (providers.isEmpty()) return false
        val documentManager = PsiDocumentManager.getInstance(project)
        if (!documentManager.isCommitted(context.document)) return false
        val file = documentManager.getPsiFile(context.document) ?: return false
        val document = context.document
        val lines = TextRange(
            document.getLineStartOffset(document.getLineNumber(range.startOffset)),
            document.getLineEndOffset(document.getLineNumber(range.endOffset)),
        )
        val injected = InjectedLanguageManager.getInstance(project)
        val elements = candidates(file.findElementAt(range.startOffset), range, lines) { it.textRange } +
            candidates(injected.findInjectedElementAt(file, range.startOffset), range, lines) {
                injected.injectedToHost(it, it.textRange)
            }
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
