package com.bnsoft.chromabox

import com.bnsoft.chromabox.engine.EditorLensController
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager

class ChromaboxEditorFactoryListener : EditorFactoryListener {
    override fun editorCreated(event: EditorFactoryEvent) {
        if (isSupported(event.editor)) EditorLensController.getOrCreate(event.editor, ChromaboxHost)
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        EditorLensController.release(event.editor)
    }

    companion object {
        fun isSupported(editor: Editor): Boolean =
            (editor.editorKind == EditorKind.MAIN_EDITOR || editor.editorKind == EditorKind.DIFF) &&
                FileDocumentManager.getInstance().getFile(editor.document) != null
    }
}
