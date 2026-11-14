package com.bnsoft.chromabox

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.openapi.project.DumbAwareAction

/**
 * Selects one display mode. Not a [com.intellij.openapi.actionSystem.ToggleAction]: Search Everywhere shows those
 * as independent on/off switches, which is wrong for mutually exclusive choices. Menus still show a checkmark.
 */
abstract class DisplayModeAction(private val mode: DisplayMode) : DumbAwareAction(), Toggleable {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        Toggleable.setSelected(e.presentation, ChromaboxSettings.getInstance().mode == mode)
    }

    override fun actionPerformed(e: AnActionEvent) {
        ChromaboxSettings.getInstance().mode = mode
    }
}

class BoxOnlyModeAction : DisplayModeAction(DisplayMode.BOX_ONLY)
class BoxWithHexModeAction : DisplayModeAction(DisplayMode.BOX_WITH_HEX)
class GutterModeAction : DisplayModeAction(DisplayMode.GUTTER)
class OffModeAction : DisplayModeAction(DisplayMode.OFF)

class CycleDisplayModeAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val settings = ChromaboxSettings.getInstance()
        val modes = DisplayMode.entries
        settings.mode = modes[(settings.mode.ordinal + 1) % modes.size]
    }
}
