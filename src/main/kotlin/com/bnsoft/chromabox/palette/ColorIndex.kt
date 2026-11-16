package com.bnsoft.chromabox.palette

import com.bnsoft.chromabox.ChromaboxHost
import com.bnsoft.chromabox.ChromaboxSettings
import com.bnsoft.chromabox.ColorFormat
import com.bnsoft.chromabox.Gradient
import com.bnsoft.chromabox.color.ChromaColor
import com.bnsoft.chromabox.engine.LensContext
import com.bnsoft.chromabox.engine.LensScanner
import com.intellij.openapi.editor.impl.DocumentImpl
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.indexing.IndexedFile
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import java.io.DataInput
import java.io.DataOutput

/** Where a color is written in a file: [start] to [end], in [format] (C-03.2). */
data class IndexedColor(val start: Int, val end: Int, val format: ColorFormat?)

/**
 * The colors of the project's files (C-03.2): normalized 8-bit sRGBA hex, the key, to where each file writes it. Uses
 * the same lenses, formats and skipped file types as the editor; when those settings change, it's rebuilt.
 */
class ColorIndex : FileBasedIndexExtension<String, List<IndexedColor>>() {
    override fun getName() = NAME

    // Bump when the stored data or what's found changes.
    override fun getVersion() = 1

    override fun dependsOnFileContent() = true

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer(): DataExternalizer<List<IndexedColor>> = Externalizer

    // Project content only: library and SDK sources aren't the project's palette. Nothing while it's turned off.
    override fun getInputFilter(): FileBasedIndex.InputFilter = object : FileBasedIndex.ProjectSpecificInputFilter {
        override fun acceptInput(file: IndexedFile): Boolean {
            if (!ChromaboxSettings.getInstance().state.indexProjectColors || file.fileType.isBinary) return false
            val project = file.project ?: return false
            return ProjectFileIndex.getInstance(project).isInContent(file.file)
        }
    }

    override fun getIndexer(): DataIndexer<String, List<IndexedColor>, FileContent> = DataIndexer { content ->
        val settings = ChromaboxSettings.getInstance()
        val text = content.contentAsText
        val fileType = content.fileType
        if (text.length > ChromaboxSettings.MAX_DOCUMENT_LENGTH || settings.isExcluded(fileType)) return@DataIndexer emptyMap()
        // Lenses look only at the text and file type; the document is there because the context needs one.
        val context = LensContext(fileType, (fileType as? LanguageFileType)?.language, DocumentImpl(text, true), content.project)
        val result = HashMap<String, MutableList<IndexedColor>>()
        for (scanned in LensScanner.scan(text, context, ChromaboxHost.lenses)) {
            val format = ColorFormat.byLensId(scanned.lens.id)
            fun add(color: ChromaColor, range: TextRange) {
                result.getOrPut(Palette.hex(color)) { ArrayList() } += IndexedColor(range.startOffset, range.endOffset, format)
            }
            when (val value = scanned.match.value) {
                is ChromaColor -> add(value, scanned.range)
                is Gradient -> value.stops.forEach { add(it.color, it.range.shiftRight(scanned.range.startOffset)) }
            }
        }
        result
    }

    private object Externalizer : DataExternalizer<List<IndexedColor>> {
        override fun save(out: DataOutput, value: List<IndexedColor>) {
            out.writeInt(value.size)
            for (color in value) {
                out.writeInt(color.start)
                out.writeInt(color.end - color.start)
                out.writeByte(color.format?.ordinal ?: -1)
            }
        }

        override fun read(input: DataInput): List<IndexedColor> = List(input.readInt()) {
            val start = input.readInt()
            val end = start + input.readInt()
            IndexedColor(start, end, ColorFormat.entries.getOrNull(input.readByte().toInt()))
        }
    }

    companion object {
        val NAME: ID<String, List<IndexedColor>> = ID.create("chromabox.colors")

        /** Whether the index is turned on; while it's off, it's empty. */
        val enabled: Boolean get() = ChromaboxSettings.getInstance().state.indexProjectColors

        /**
         * The settings the index depends on. When they change, the index is rebuilt; they're compared with the ones it
         * was built with on every change and when a project opens, which also covers changes made in another IDE run.
         */
        fun settingsFingerprint(): String {
            val settings = ChromaboxSettings.getInstance()
            val state = settings.state
            val lenses = ColorFormat.entries.filter { it.lensBacked && settings.isLensEnabled(it.lensId) }.joinToString(",") { it.name }
            return listOf(state.indexProjectColors, lenses, state.shortForms, state.intOrder, state.floatTupleFileTypes, state.excludedFileTypes).joinToString("|")
        }

        fun rebuildIfSettingsChanged() {
            val state = ChromaboxSettings.getInstance().state
            val fingerprint = settingsFingerprint()
            if (state.colorIndexFingerprint == fingerprint) return
            val firstRun = state.colorIndexFingerprint.isEmpty()
            state.colorIndexFingerprint = fingerprint
            // The first time, the index was just built with these settings.
            if (!firstRun) FileBasedIndex.getInstance().requestRebuild(NAME)
        }

        /**
         * Calls [consumer] with the colors of each file in [scope], one color at a time. Needs a read action in smart
         * mode. Stops when [consumer] returns false.
         */
        fun process(project: Project, scope: GlobalSearchScope, consumer: (String, VirtualFile, List<IndexedColor>) -> Boolean) {
            val index = FileBasedIndex.getInstance()
            for (hex in index.getAllKeys(NAME, project)) {
                val keepGoing = index.processValues(NAME, hex, null, { file, colors -> consumer(hex, file, colors) }, scope)
                if (!keepGoing) return
            }
        }
    }
}

/** Checks the index against the settings when a project opens. */
class ColorIndexStartup : ProjectActivity {
    override suspend fun execute(project: Project) = ColorIndex.rebuildIfSettingsChanged()
}

/** Rebuilds the index when a setting it depends on changes. */
class ColorIndexSettingsListener : com.bnsoft.chromabox.ChromaboxSettingsListener {
    override fun settingsChanged() = ColorIndex.rebuildIfSettingsChanged()
}
