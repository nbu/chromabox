package com.bnsoft.chromabox.palette

import com.bnsoft.chromabox.ChromaboxBundle
import com.bnsoft.chromabox.color.ChromaColor
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.ReadonlyStatusHandler
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.usageView.UsageInfo
import com.intellij.usages.Usage
import com.intellij.usages.UsageInfo2UsageAdapter
import com.intellij.usages.UsageTarget
import com.intellij.usages.UsageViewManager
import com.intellij.usages.UsageViewPresentation

/** What a palette color can do (C-03.5). */
object PaletteActions {
    /** How a replace went: occurrences written, ones whose notation can't express the color, and read-only files. */
    data class ReplaceResult(val written: Int, val unrepresentable: Int, val readOnlyFiles: Int)

    /**
     * Writes [color] over every occurrence of the color [hex] in [files], each in its own notation, as one undoable
     * command, undoable from any of the files it changed. The files are scanned again first, so what's written is
     * what's there now. Skips read-only files and notations that can't express [color].
     */
    fun replace(project: Project, files: Collection<VirtualFile>, hex: String, color: ChromaColor): ReplaceResult {
        val status = ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(files)
        val readOnly = status.readonlyFiles.toSet()
        var unrepresentable = 0
        val edits = files.filter { it !in readOnly && it.isValid }.mapNotNull { file ->
            val document = FileDocumentManager.getInstance().getDocument(file) ?: return@mapNotNull null
            val occurrences = Palette.scan(file, document, project).filter { Palette.hex(it.color) == hex }
            document to occurrences.sortedByDescending { it.range.startOffset }.mapNotNull { occurrence ->
                val text = occurrence.write?.invoke(color)
                if (text == null) unrepresentable++
                text?.let { occurrence to it }
            }
        }.filter { it.second.isNotEmpty() }
        var written = 0
        if (edits.isNotEmpty()) {
            WriteCommandAction.writeCommandAction(project)
                .withName(ChromaboxBundle.message("palette.replace.command"))
                .withGlobalUndo()
                .run<RuntimeException> {
                    for ((document, replacements) in edits) {
                        for ((occurrence, text) in replacements) {
                            document.replaceString(occurrence.range.startOffset, occurrence.range.endOffset, text)
                            written++
                        }
                    }
                }
        }
        return ReplaceResult(written, unrepresentable, files.count { it in readOnly })
    }

    /** Tells what [replace] skipped; says nothing if it skipped nothing. */
    fun report(project: Project, result: ReplaceResult) {
        val skipped = buildList {
            if (result.unrepresentable > 0) add(ChromaboxBundle.message("palette.replace.unrepresentable", result.unrepresentable))
            if (result.readOnlyFiles > 0) add(ChromaboxBundle.message("palette.replace.readOnly", result.readOnlyFiles))
        }
        if (skipped.isEmpty()) return
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATIONS)
            .createNotification(
                ChromaboxBundle.message("palette.replace.done", result.written),
                skipped.joinToString("<br>"),
                NotificationType.WARNING,
            )
            .notify(project)
    }

    /** Lists the occurrences in the Find tool window. */
    fun showUsages(project: Project, entry: PaletteEntry) {
        val usages = ReadAction.compute<Array<Usage>, RuntimeException> {
            val psiManager = PsiManager.getInstance(project)
            entry.occurrences.mapNotNull { occurrence ->
                val file = occurrence.file.takeIf { it.isValid }?.let(psiManager::findFile) ?: return@mapNotNull null
                if (occurrence.range.endOffset > file.textLength) return@mapNotNull null
                UsageInfo2UsageAdapter(UsageInfo(file, occurrence.range.startOffset, occurrence.range.endOffset))
            }.toTypedArray<Usage>()
        }
        val presentation = UsageViewPresentation().apply {
            tabText = ChromaboxBundle.message("palette.usages.tab", entry.hex)
            searchString = entry.hex
            isOpenInNewTab = false
            codeUsagesString = ChromaboxBundle.message("palette.usages.title", entry.hex)
        }
        UsageViewManager.getInstance(project).showUsages(UsageTarget.EMPTY_ARRAY, usages, presentation)
    }

    const val NOTIFICATIONS = "Chromabox"
}
