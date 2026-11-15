package com.bnsoft.chromabox.palette

import com.bnsoft.chromabox.ChromaboxBundle
import com.bnsoft.chromabox.color.ChromaColor
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.ReadonlyStatusHandler
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.usageView.UsageInfo
import com.intellij.usages.Usage
import com.intellij.usages.UsageInfo2UsageAdapter
import com.intellij.usages.UsageTarget
import com.intellij.usages.UsageViewManager
import com.intellij.usages.UsageViewPresentation
import org.jetbrains.annotations.Nls

/** What a palette color can do (C-03.5). */
object PaletteActions {
    /** [old] at [range] in [file] becomes [new]. */
    data class Change(val file: VirtualFile, val range: TextRange, val old: String, val new: String)

    /** The [changes] a replace or merge would make, and how many occurrences can't be written in their notation. */
    data class Plan(val changes: List<Change>, val unrepresentable: Int)

    /**
     * How a replace or merge went: occurrences written, ones whose notation can't express the color, read-only files,
     * and occurrences whose text changed after the plan was made.
     */
    data class ReplaceResult(val written: Int, val unrepresentable: Int, val readOnlyFiles: Int, val changed: Int = 0)

    /**
     * What writing [color] over every occurrence of the colors [hexes] in [files] changes, each in its own notation.
     * Scans the files again, so it's what's there now. Occurrences that would be written as they are, such as `red`
     * merged into `#ff0000`, aren't changes. Needs a read action.
     */
    fun plan(project: Project, files: Collection<VirtualFile>, hexes: Set<String>, color: ChromaColor): Plan {
        var unrepresentable = 0
        val changes = files.filter { it.isValid }.flatMap { file ->
            val document = FileDocumentManager.getInstance().getDocument(file) ?: return@flatMap emptyList()
            Palette.scan(file, document, project).filter { Palette.hex(it.color) in hexes }.mapNotNull { occurrence ->
                val new = occurrence.write?.invoke(color)
                if (new == null) unrepresentable++
                val old = occurrence.text ?: return@mapNotNull null
                if (new == null || new == old) null else Change(file, occurrence.range, old, new)
            }
        }
        return Plan(changes, unrepresentable)
    }

    /**
     * Makes the changes of [plan] as one command named [commandName], undoable from any of the files it changed.
     * Skips read-only files, and changes whose text isn't what the plan saw any more.
     */
    fun apply(project: Project, plan: Plan, commandName: @Nls String): ReplaceResult {
        val byFile = plan.changes.groupBy { it.file }
        val status = ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(byFile.keys)
        val readOnly = status.readonlyFiles.toSet()
        var written = 0
        var changed = 0
        val edits = byFile.filterKeys { it !in readOnly && it.isValid }.mapNotNull { (file, changes) ->
            val document = FileDocumentManager.getInstance().getDocument(file) ?: return@mapNotNull null
            document to changes.sortedByDescending { it.range.startOffset }.filter { change ->
                val current = change.range.endOffset <= document.textLength && document.getText(change.range) == change.old
                if (!current) changed++
                current
            }
        }
        if (edits.any { it.second.isNotEmpty() }) {
            WriteCommandAction.writeCommandAction(project)
                .withName(commandName)
                .withGlobalUndo()
                .run<RuntimeException> {
                    for ((document, changes) in edits) {
                        for (change in changes) {
                            document.replaceString(change.range.startOffset, change.range.endOffset, change.new)
                            written++
                        }
                    }
                }
        }
        return ReplaceResult(written, plan.unrepresentable, byFile.keys.count { it in readOnly }, changed)
    }

    /**
     * Writes [color] over every occurrence of the color [hex] in [files], each in its own notation, as one undoable
     * command, undoable from any of the files it changed. Skips read-only files and notations that can't express it.
     */
    fun replace(project: Project, files: Collection<VirtualFile>, hex: String, color: ChromaColor): ReplaceResult =
        apply(project, plan(project, files, setOf(hex), color), ChromaboxBundle.message("palette.replace.command"))

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
