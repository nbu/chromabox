package com.bnsoft.chromabox.palette

import com.bnsoft.chromabox.ChromaboxBundle
import com.bnsoft.chromabox.ColorSwatchRenderer
import com.bnsoft.chromabox.color.ChromaColor
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.ColorChooserService
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.listCellRenderer
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * Merges a group of near-duplicates into one color (C-03.6): choose one of the group's colors or any other, see every
 * change by file, then apply them all as one undoable command. Each occurrence keeps its notation.
 */
class MergeDialog(private val project: Project, group: PaletteGroup) : DialogWrapper(project) {
    private val targets = CollectionComboBoxModel(group.entries.map { it.color }.toMutableList())
    private val targetBox = ComboBox(targets)
    private val tree = Tree()
    private val summary = JBLabel()
    private val files = group.entries.flatMap { entry -> entry.occurrences.map { it.file } }.distinct()
    private val hexes = group.entries.map { it.hex }.toSet()

    /** The changes for the chosen color; what OK applies. */
    var plan = PaletteActions.Plan(emptyList(), 0)
        private set

    internal var target: ChromaColor
        get() = targetBox.item
        set(value) {
            if (value !in targets.items) targets.add(value)
            targetBox.item = value
        }

    init {
        title = ChromaboxBundle.message("palette.merge.title")
        targetBox.renderer = listCellRenderer {
            icon(ColorSwatchRenderer.of(value))
            text(Palette.hex(value))
        }
        targetBox.addActionListener { update() }
        tree.isRootVisible = false
        tree.cellRenderer = MergePreview.Renderer()
        update()
        init()
    }

    internal fun update() {
        val color = target
        plan = ReadAction.computeBlocking<PaletteActions.Plan, RuntimeException> { PaletteActions.plan(project, files, hexes, color) }
        tree.model = MergePreview.model(plan, project)
        TreeUtil.expandAll(tree)
        val fileCount = plan.changes.map { it.file }.distinct().size
        val lines = listOfNotNull(
            ChromaboxBundle.message("palette.merge.summary", plan.changes.size, fileCount),
            plan.unrepresentable.takeIf { it > 0 }?.let { ChromaboxBundle.message("palette.merge.unrepresentable", it) },
        )
        summary.text = lines.joinToString("<br>", "<html>", "</html>")
        isOKActionEnabled = plan.changes.isNotEmpty()
    }

    private fun chooseOther() {
        val title = ChromaboxBundle.message("palette.merge.otherTitle")
        val picked = ColorChooserService.getInstance().showDialog(project, contentPane, title, target.toAwt(), true, emptyList(), true)
            ?: return
        target = ChromaColor.fromAwt(picked)
    }

    override fun createCenterPanel(): JComponent = panel {
        row(ChromaboxBundle.message("palette.merge.into")) {
            cell(targetBox)
            link(ChromaboxBundle.message("palette.merge.other")) { chooseOther() }
        }
        row {
            scrollCell(tree).align(Align.FILL)
        }.resizableRow()
        row { cell(summary) }
    }.apply { preferredSize = JBUI.size(760, 420) }

    override fun getPreferredFocusedComponent(): JComponent = targetBox
}

/** The changes of a merge as a tree: a node per file, with a node per change under it. */
object MergePreview {
    /** [file] in [location], its folder relative to the project when it's in it. */
    class FileNode(val file: VirtualFile, val location: String, val count: Int)

    class ChangeNode(val change: PaletteActions.Change, val line: Int)

    fun model(plan: PaletteActions.Plan, project: Project): DefaultTreeModel {
        val root = DefaultMutableTreeNode()
        val projectDir = project.guessProjectDir()
        for ((file, changes) in plan.changes.groupBy { it.file }) {
            val parent = file.parent
            val location = parent?.let { dir -> projectDir?.let { VfsUtilCore.getRelativePath(dir, it) } ?: dir.presentableUrl }.orEmpty()
            val fileNode = DefaultMutableTreeNode(FileNode(file, location, changes.size))
            val document = FileDocumentManager.getInstance().getDocument(file)
            for (change in changes.sortedBy { it.range.startOffset }) {
                val line = document?.getLineNumber(change.range.startOffset)?.plus(1) ?: 0
                fileNode.add(DefaultMutableTreeNode(ChangeNode(change, line)))
            }
            root.add(fileNode)
        }
        return DefaultTreeModel(root)
    }

    class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            when (val node = (value as? DefaultMutableTreeNode)?.userObject) {
                is FileNode -> {
                    icon = node.file.fileType.icon
                    append(node.file.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    if (node.location.isNotEmpty()) append("  " + node.location, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append("  (${node.count})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is ChangeNode -> {
                    append("${node.line}  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append(node.change.old, SimpleTextAttributes(SimpleTextAttributes.STYLE_STRIKEOUT, null))
                    append("  →  ")
                    append(node.change.new, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                }
            }
        }
    }
}
