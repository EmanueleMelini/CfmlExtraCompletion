package it.emanuelemelini.cfmlextracompletion

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile

/**
 * Resolves the transitive set of files reachable from a starting CFML file via cfinclude / script include.
 * Traversal is breadth-first with a visited set, so include cycles terminate. The starting file is not
 * included in the result.
 */
object IncludeGraphResolver {

    private val CFINCLUDE = Regex(
        """<cfinclude\b[^>]*?\btemplate\s*=\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val SCRIPT_INCLUDE = Regex(
        """\binclude\s+["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )

    fun collectIncludedFiles(
        project: Project,
        start: VirtualFile,
        mappings: Map<String, VirtualFile> = emptyMap(),
        textOf: (VirtualFile) -> String?,
    ): List<VirtualFile> {
        val contentRoot = ProjectRootManager.getInstance(project).fileIndex.getContentRootForFile(start)
        val visited = HashSet<VirtualFile>().apply { add(start) }
        val result = LinkedHashSet<VirtualFile>()
        val queue = ArrayDeque<VirtualFile>().apply { add(start) }

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val text = textOf(current) ?: continue
            for (path in findIncludePaths(text)) {
                val resolved = resolve(current, path, contentRoot, mappings) ?: continue
                if (visited.add(resolved)) {
                    result.add(resolved)
                    queue.add(resolved)
                }
            }
        }
        return result.toList()
    }

    /** Resolves a single cfinclude/cfmodule template path from [from], applying CF mappings. */
    fun resolveTemplate(
        project: Project,
        from: VirtualFile,
        rawPath: String,
        mappings: Map<String, VirtualFile> = emptyMap(),
    ): VirtualFile? {
        val contentRoot = ProjectRootManager.getInstance(project).fileIndex.getContentRootForFile(from)
        return resolve(from, rawPath, contentRoot, mappings)
    }

    private fun findIncludePaths(text: String): Sequence<String> =
        (CFINCLUDE.findAll(text) + SCRIPT_INCLUDE.findAll(text)).map { it.groupValues[1] }

    private fun resolve(
        base: VirtualFile,
        rawPath: String,
        contentRoot: VirtualFile?,
        mappings: Map<String, VirtualFile>,
    ): VirtualFile? {
        val path = rawPath.trim().replace('\\', '/')
        // Skip dynamic templates that interpolate CFML expressions — cannot resolve statically.
        if (path.isEmpty() || path.contains('#')) return null

        // Prefer a ColdFusion mapping match (longest alias wins), e.g. "/utils/x.cfm" -> mapped dir.
        resolveViaMapping(path, mappings)?.let { return it }

        val target = if (path.startsWith("/")) {
            contentRoot?.findFileByRelativePath(path.removePrefix("/"))
        } else {
            base.parent?.findFileByRelativePath(path)
        }
        return target?.takeIf { it.isValid && !it.isDirectory }
    }

    private fun resolveViaMapping(path: String, mappings: Map<String, VirtualFile>): VirtualFile? {
        if (mappings.isEmpty() || !path.startsWith("/")) return null
        val alias = mappings.keys
            .filter { path.equals(it, ignoreCase = true) || path.startsWith("$it/", ignoreCase = true) }
            .maxByOrNull { it.length } ?: return null
        val remainder = path.substring(alias.length).trimStart('/')
        val dir = mappings[alias] ?: return null
        val target = if (remainder.isEmpty()) dir else dir.findFileByRelativePath(remainder)
        return target?.takeIf { it.isValid && !it.isDirectory }
    }
}
