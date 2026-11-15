package com.bnsoft.chromabox

import com.bnsoft.chromabox.color.ColorVision
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.impl.status.widget.StatusBarWidgetsManager
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.Consumer
import java.awt.Color
import java.awt.Component
import java.awt.Point
import java.awt.event.MouseEvent

/**
 * Color vision deficiency simulation (C-05): swatches, strips and gutter icons are drawn as they'd look with it, and
 * contrast badges show the ratio of the simulated colors. Document text never changes (C-05.2).
 */
object ColorVisionSimulation {
    val current: ColorVision get() = ChromaboxSettings.getInstance().simulation

    /** [color] as it looks with the current simulation; renderers call this when they paint. */
    fun simulated(color: Color): Color = current.simulate(color)

    fun select(vision: ColorVision) {
        val settings = ChromaboxSettings.getInstance()
        if (settings.simulation == vision) return
        settings.setSimulation(vision)
        // Badges are rescanned; swatches are kept and simulate when they paint, so they only need a repaint.
        settings.notifyChanged()
        EditorFactory.getInstance().allEditors.forEach { it.component.repaint() }
        for (project in ProjectManager.getInstance().openProjects) updateWidget(project)
    }

    private fun updateWidget(project: Project) {
        if (project.isDisposed) return
        project.service<StatusBarWidgetsManager>().updateWidget(ColorVisionWidgetFactory::class.java)
        WindowManager.getInstance().getStatusBar(project)?.updateWidget(ColorVisionWidgetFactory.ID)
    }
}

/** Selects one simulation (C-05.1); like [DisplayModeAction], not a toggle, as the choices exclude each other. */
abstract class SimulateAction(private val vision: ColorVision) : DumbAwareAction(), Toggleable {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        Toggleable.setSelected(e.presentation, ColorVisionSimulation.current == vision)
    }

    override fun actionPerformed(e: AnActionEvent) = ColorVisionSimulation.select(vision)
}

class SimulateNoneAction : SimulateAction(ColorVision.NONE)
class SimulateProtanopiaAction : SimulateAction(ColorVision.PROTANOPIA)
class SimulateDeuteranopiaAction : SimulateAction(ColorVision.DEUTERANOPIA)
class SimulateTritanopiaAction : SimulateAction(ColorVision.TRITANOPIA)
class SimulateAchromatopsiaAction : SimulateAction(ColorVision.ACHROMATOPSIA)

/** Shows the active simulation in the status bar while one is on (C-05.1); click it to change or end it. */
class ColorVisionWidgetFactory : StatusBarWidgetFactory {
    override fun getId() = ID

    override fun getDisplayName() = ChromaboxBundle.message("simulation.widget.name")

    override fun isAvailable(project: Project) = ColorVisionSimulation.current != ColorVision.NONE

    override fun createWidget(project: Project): StatusBarWidget = ColorVisionWidget()

    override fun canBeEnabledOn(statusBar: StatusBar) = true

    companion object {
        const val ID = "Chromabox.ColorVision"
    }
}

private class ColorVisionWidget : StatusBarWidget, StatusBarWidget.TextPresentation {
    override fun ID() = ColorVisionWidgetFactory.ID

    override fun getPresentation() = this

    override fun getText() = ChromaboxBundle.message("simulation.widget.text", ChromaboxBundle.message("simulation.${ColorVisionSimulation.current.name}"))

    override fun getTooltipText() = ChromaboxBundle.message("simulation.widget.tooltip")

    override fun getAlignment() = Component.CENTER_ALIGNMENT

    override fun getClickConsumer() = Consumer<MouseEvent> { event ->
        val group = ActionManager.getInstance().getAction("Chromabox.Simulate") as? ActionGroup ?: return@Consumer
        val popup = JBPopupFactory.getInstance().createActionGroupPopup(
            ChromaboxBundle.message("simulation.widget.name"),
            group,
            DataManager.getInstance().getDataContext(event.component),
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            false,
        )
        popup.show(RelativePoint(event.component, Point(0, -popup.content.preferredSize.height)))
    }
}
