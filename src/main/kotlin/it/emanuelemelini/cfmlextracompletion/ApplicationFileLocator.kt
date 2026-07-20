package it.emanuelemelini.cfmlextracompletion

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile

/**
 * Application.cfm / Application.cfc files governing a page, ordered nearest-first. When only the nearest
 * is wanted each list holds at most one entry.
 */
data class ApplicationFiles(val cfms: List<VirtualFile>, val cfcs: List<VirtualFile>) {
    val nearestCfm: VirtualFile? get() = cfms.firstOrNull()
    val nearestCfc: VirtualFile? get() = cfcs.firstOrNull()
}

/**
 * Finds the CFML Application files for a given file by walking up the directory tree (bounded by the
 * content root). ColdFusion applies the nearest Application.(cfc|cfm); with [includeAncestors] the caller
 * can also collect the ones higher up for a merged view.
 */
object ApplicationFileLocator {

    private const val MAX_DEPTH = 64

    fun locate(project: Project, from: VirtualFile, includeAncestors: Boolean = false): ApplicationFiles {
        val fileIndex = ProjectRootManager.getInstance(project).fileIndex
        val contentRoot = fileIndex.getContentRootForFile(from)

        val cfms = ArrayList<VirtualFile>()
        val cfcs = ArrayList<VirtualFile>()

        var dir: VirtualFile? = if (from.isDirectory) from else from.parent
        var depth = 0
        while (dir != null && depth < MAX_DEPTH) {
            findChildIgnoreCase(dir, "application.cfc")?.let { if (includeAncestors || cfcs.isEmpty()) cfcs.add(it) }
            findChildIgnoreCase(dir, "application.cfm")?.let { if (includeAncestors || cfms.isEmpty()) cfms.add(it) }
            if (!includeAncestors && cfcs.isNotEmpty() && cfms.isNotEmpty()) break
            if (dir == contentRoot) break
            dir = dir.parent
            depth++
        }
        return ApplicationFiles(cfms, cfcs)
    }

    private fun findChildIgnoreCase(dir: VirtualFile, lowerName: String): VirtualFile? =
        dir.children?.firstOrNull { !it.isDirectory && it.isValid && it.name.equals(lowerName, ignoreCase = true) }
}
