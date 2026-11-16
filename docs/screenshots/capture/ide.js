// Runs inside the screenshot IDE (./gradlew runIdeForScreenshots), through the Robot Server plugin's JavaScript
// (Rhino) endpoint; robot.py sends it before each script, after setting SHOTS, the folder screenshots go to.
// Rhino can't see the plugin's classes by name, so plugin objects are reached through Swing or pluginClass().

importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.actionSystem.ActionManager)
importClass(com.intellij.openapi.editor.CaretState)
importClass(com.intellij.openapi.editor.LogicalPosition)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.vfs.LocalFileSystem)
importClass(com.intellij.openapi.wm.ToolWindowManager)
importClass(com.intellij.openapi.wm.WindowManager)
importClass(com.intellij.util.ui.JBFont)
importClass(com.intellij.util.ui.JBUI)
importClass(com.intellij.util.ui.UIUtil)
importClass(java.awt.Color)
importClass(java.awt.RenderingHints)
importClass(java.awt.geom.RoundRectangle2D)
importClass(java.awt.image.BufferedImage)
importClass(java.io.File)
importClass(javax.imageio.ImageIO)

var project = ProjectManager.getInstance().getOpenProjects()[0]
var SHADOW = new Color(0x10000000, true)

// region Capture

function frame() { return WindowManager.getInstance().getFrame(project) }

function smooth(g) {
  g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
  g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
  return g
}

function paintInto(g, c, x, y) {
  var g2 = g.create(x, y, c.getWidth(), c.getHeight())
  c.printAll(g2)
  g2.dispose()
}

function shadow(g, x, y, w, h, arc, size, drop) {
  for (var k = size; k > 0; k--) { g.setColor(SHADOW); g.fillRoundRect(x - k, y - k + drop, w + 2 * k, h + 2 * k, arc + k, arc + k) }
}

// A dialog, with what macOS draws natively: the shadow, rounded corners, and the title and window buttons over its
// top band.
function paintDialog(g, win, x, y, contentX, contentY) {
  var w = win.getWidth(), h = win.getHeight(), titleHeight = 28
  var g2 = smooth(g.create())
  shadow(g2, x, y, w, h, 20, 16, 8)
  var shape = new RoundRectangle2D.Double(x, y, w, h, 20, 20)
  var inside = g2.create()
  inside.clip(shape)
  inside.setColor(UIUtil.getPanelBackground())
  inside.fill(shape)
  paintInto(inside, win.getRootPane(), contentX, contentY)
  inside.dispose()
  g2.setColor(UIUtil.getLabelForeground())
  g2.setFont(JBFont.label().asBold())
  var fm = g2.getFontMetrics(), title = win.getTitle()
  g2.drawString(title, x + (w - fm.stringWidth(title)) / 2, y + (titleHeight + fm.getAscent() - fm.getDescent()) / 2)
  var buttons = [0xff5f57, 0xfebc2e, 0x28c840]
  for (var i = 0; i < 3; i++) { g2.setColor(new Color(buttons[i])); g2.fillOval(x + 12 + i * 20, y + titleHeight / 2 - 6, 12, 12) }
  g2.setColor(JBUI.CurrentTheme.Popup.borderColor(true))
  g2.draw(new RoundRectangle2D.Double(x + 0.5, y + 0.5, w - 1, h - 1, 20, 20))
  g2.dispose()
}

// A popup, with the shadow, rounded corners and border the window system draws.
function paintPopup(g, win, x, y) {
  var root = win.getRootPane(), w = root.getWidth(), h = root.getHeight()
  var g2 = smooth(g.create())
  shadow(g2, x, y, w, h, 14, 10, 4)
  var inside = g2.create()
  inside.clip(new RoundRectangle2D.Double(x, y, w, h, 14, 14))
  paintInto(inside, root, x, y)
  inside.dispose()
  g2.setColor(JBUI.CurrentTheme.Popup.borderColor(true))
  g2.draw(new RoundRectangle2D.Double(x + 0.5, y + 0.5, w - 1, h - 1, 14, 14))
  g2.dispose()
}

// Paints the IDE frame and the dialogs and popups over it into SHOTS/name.png, at 2x. Painting, rather than a
// screen capture, needs no screen recording permission and works while the window is covered.
function shot(name) {
  var f = frame(), root = f.getRootPane(), origin = root.getLocationOnScreen()
  var img = new BufferedImage(root.getWidth() * 2, root.getHeight() * 2, BufferedImage.TYPE_INT_RGB)
  var g = img.createGraphics()
  g.scale(2, 2)
  paintInto(g, root, 0, 0)
  var windows = java.awt.Window.getWindows()
  for (var i = 0; i < windows.length; i++) {
    var win = windows[i]
    if (win === f || !win.isShowing() || !(win instanceof javax.swing.RootPaneContainer)) continue
    var at = win.getLocationOnScreen(), content = win.getRootPane().getLocationOnScreen()
    if (win instanceof java.awt.Dialog) {
      paintDialog(g, win, at.x - origin.x, at.y - origin.y, content.x - origin.x, content.y - origin.y)
    } else {
      paintPopup(g, win, content.x - origin.x, content.y - origin.y)
    }
  }
  g.dispose()
  ImageIO.write(img, "png", new File(SHOTS + name + ".png"))
  return name + ": " + root.getWidth() + "x" + root.getHeight()
}

// endregion

// region The IDE

// Frame size, and nothing in the shots that isn't Chromabox's: spelling and table-format squiggles, the VCS widget.
function setUp(width, height) {
  var f = frame()
  f.setExtendedState(0)
  f.setSize(width, height)
  f.setLocation(60, 60)
  var profile = com.intellij.profile.codeInspection.InspectionProjectProfileManager.getInstance(project).getCurrentProfile()
  profile.setToolEnabled("SpellCheckingInspection", false, project)
  profile.setToolEnabled("MarkdownIncorrectTableFormatting", false, project)
  com.intellij.openapi.vcs.ProjectLevelVcsManager.getInstance(project).setDirectoryMappings(new java.util.ArrayList())
  com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart()
  closeAll()
  tw("Project").hide()
}

function isSmart() { return !com.intellij.openapi.project.DumbService.isDumb(project) }

function file(path) { return LocalFileSystem.getInstance().refreshAndFindFileByPath(project.getBasePath() + "/" + path) }

function open(path) { return FileEditorManager.getInstance(project).openFile(file(path), true)[0] }

function closeAll() {
  var editors = FileEditorManager.getInstance(project), files = editors.getOpenFiles()
  for (var i = 0; i < files.length; i++) editors.closeFile(files[i])
}

function editor() { return FileEditorManager.getInstance(project).getSelectedTextEditor() }

function tw(id) { return ToolWindowManager.getInstance(project).getToolWindow(id) }

// One caret at line, column (1-based), with nothing selected.
function caret(line, column) {
  var carets = editor().getCaretModel()
  carets.removeSecondaryCarets()
  carets.moveToLogicalPosition(new LogicalPosition(line - 1, column - 1))
  editor().getSelectionModel().removeSelection()
}

// A caret at each [line, column].
function carets(positions) {
  var states = new java.util.ArrayList()
  for (var i = 0; i < positions.length; i++) {
    var p = new LogicalPosition(positions[i][0] - 1, positions[i][1] - 1)
    states.add(new CaretState(p, p, p))
  }
  editor().getCaretModel().setCaretsAndSelections(states)
}

// Runs an action in the editor's context.
function action(id) {
  var manager = ActionManager.getInstance()
  manager.tryToExecute(manager.getAction(id), null, editor().getContentComponent(), "screenshots", true)
}

function closePopups() {
  var popups = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().getChildPopups(frame().getRootPane())
  for (var i = 0; i < popups.size(); i++) popups.get(i).cancel()
}

function closeDialogs() {
  var windows = java.awt.Window.getWindows()
  for (var i = 0; i < windows.length; i++) if (windows[i].isShowing() && windows[i].getDialogWrapper) windows[i].getDialogWrapper().close(1)
}

// Centers the showing dialog over the frame, at width x height.
function fitDialog(width, height) {
  var windows = java.awt.Window.getWindows(), f = frame()
  for (var i = 0; i < windows.length; i++) if (windows[i] instanceof java.awt.Dialog && windows[i].isShowing()) {
    windows[i].setSize(width, height)
    windows[i].setLocation(f.getX() + (f.getWidth() - width) / 2, f.getY() + (f.getHeight() - height) / 2 + 10)
  }
}

function splitPreview() {
  FileEditorManager.getInstance(project).getSelectedEditor()
    .setLayout(com.intellij.openapi.fileEditor.TextEditorWithPreview.Layout.SHOW_EDITOR_AND_PREVIEW)
}

// Later, so the modal dialog doesn't hold up the script.
function showSettingsLater(page) {
  ApplicationManager.getApplication().invokeLater(function () {
    com.intellij.openapi.options.ShowSettingsUtil.getInstance().showSettingsDialog(project, page)
  })
}

// endregion

// region The Chromabox tool window

function palette() { return tw("Chromabox").getContentManager().getContent(0).getComponent() }

function pluginClass(name) { return java.lang.Class.forName(name, true, palette().getClass().getClassLoader()) }

// The components under root that are instances of the class named className, depth first.
function find(root, className) {
  var cls = java.lang.Class.forName(className), out = []
  function walk(c) {
    if (cls.isInstance(c)) out.push(c)
    if (c instanceof java.awt.Container) { var children = c.getComponents(); for (var i = 0; i < children.length; i++) walk(children[i]) }
  }
  walk(root)
  return out
}

function paletteList() { return find(palette(), "javax.swing.JList")[0] }

// PROJECT, CURRENT_FILE, ...
function setScope(name) {
  var box = find(palette(), "javax.swing.JComboBox")[0]
  for (var i = 0; i < box.getItemCount(); i++) if (box.getItemAt(i).toString() == name) box.setSelectedIndex(i)
}

function select(hex) {
  var list = paletteList(), model = list.getModel()
  for (var i = 0; i < model.getSize(); i++) if (model.getElementAt(i).getEntry().getHex() == hex) {
    list.setSelectedIndex(i)
    list.ensureIndexIsVisible(i)
  }
}

function clearSelection() { paletteList().clearSelection() }

// Merge Group Into... for the group of hex; later, so the modal dialog doesn't hold up the script.
function showMergeLater(hex) {
  select(hex)
  var group = paletteList().getSelectedValue().getGroup()
  var constructor = pluginClass("com.bnsoft.chromabox.palette.MergeDialog").getConstructors()[0]
  ApplicationManager.getApplication().invokeLater(function () { constructor.newInstance(project, group).show() })
}

// endregion
