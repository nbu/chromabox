package com.bnsoft.chromabox

import com.bnsoft.chromabox.color.ChromaColor
import com.bnsoft.chromabox.engine.EditorLensController
import com.bnsoft.chromabox.engine.ShownMatch
import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.TextRange

/** Chromabox | Check Contrast (C-02.1, heuristic 3): the contrast of two colors with carets on them, or selected. */
class CheckContrastAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor != null && EditorLensController.of(editor) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val controller = EditorLensController.of(editor) ?: return
        val pair = chosenPair(editor, controller.shownMatches)
        if (pair == null) {
            HintManager.getInstance().showErrorHint(editor, ChromaboxBundle.message("contrast.check.choose"))
            return
        }
        ContrastPopup.show(controller, pair, JBPopupFactory.getInstance().guessBestPopupLocation(editor))
    }

    companion object {
        /**
         * The two colors the user chose: with two carets, the color each caret is in or next to; with one, the colors
         * its selection covers. Null unless that's exactly two.
         */
        internal fun chosenPair(editor: Editor, matches: List<ShownMatch>): ContrastPair? {
            val colors = matches.mapNotNull { m -> (m.value as? ChromaColor)?.let { ColorAt(m.range, it) } }
            val carets = editor.caretModel.allCarets
            val chosen = if (carets.size == 2) {
                carets.mapNotNull { caret -> colors.find { caret.offset in it.range.startOffset..it.range.endOffset } }.distinct()
            } else {
                val caret = carets.singleOrNull()?.takeIf { it.hasSelection() } ?: return null
                val selection = TextRange(caret.selectionStart, caret.selectionEnd)
                colors.filter { it.range.intersectsStrict(selection) }
            }
            if (chosen.size != 2) return null
            val (first, second) = chosen.sortedBy { it.range.startOffset }
            return ContrastPairs.of(first, second)
        }
    }
}
