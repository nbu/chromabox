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
    data class ReplaceResult(val written: Int, val unrepresentable: Int, val readOnlyFiles: Int, val changed: Int)

    /**
     * Writes [color] over every occurrence, each in its own notation, as one undoable command, undoable from any of the
     * files it changed. Skips read-only files, occurrences whose text changed since the scan, and notations that can't
     * express [color], such as a named color for a color without a name.
     */
    fun replace(project: Project, occurrences: List<PaletteOccurrence>, color: ChromaColor): ReplaceResult {
        val byFile = occurrences.groupBy { it.file }
        val status = ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(byFile.keys)
        val readOnly = status.readonlyFiles.toSet()
        var written = 0
        var unrepresentable = 0
        var changed = 0
        val edits = byFile.filterKeys { it !in readOnly }.mapNotNull { (file, inFile) ->
            val document = FileDocumentManager.getInstance().getDocument(file) ?: return@mapNotNull null
            document to inFile.sortedByDescending { it.range.startOffset }.mapNotNull { occurrence ->
                val write = occurrence.write
                val text = write?.invoke(color)
                when {
                    occurrence.range.endOffset > document.textLength || document.getText(occurrence.range) != occurrence.text -> {
                        changed++
                        null
                    }
                    text == null -> {
                        unrepresentable++
                        null
                    }
                    else -> occurrence to text
                }
            }
        }.filter { it.second.isNotEmpty() }
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
        return ReplaceResult(written, unrepresentable, byFile.keys.count { it in readOnly }, changed)
    }

    /** Tells what [replace] skipped; says nothing if it skipped nothing. */
    fun report(project: Project, result: ReplaceResult) {
        val skipped = buildList {
            if (result.unrepresentable > 0) add(ChromaboxBundle.message("palette.replace.unrepresentable", result.unrepresentable))
            if (result.readOnlyFiles > 0) add(ChromaboxBundle.message("palette.replace.readOnly", result.readOnlyFiles))
            if (result.changed > 0) add(ChromaboxBundle.message("palette.replace.changed", result.changed))
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
