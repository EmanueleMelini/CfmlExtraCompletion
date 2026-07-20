package it.emanuelemelini.cfmlextracompletion

import com.intellij.openapi.vfs.VirtualFile

/**
 * Parses ColdFusion application mappings (this.mappings) from an Application.cfc so cfinclude paths that
 * start with a mapping alias (e.g. "/utils/x.cfm") can be resolved to the mapped directory.
 *
 * Best-effort and static: it understands literal string values and the common expandPath("./rel") form,
 * resolved against the Application.cfc's directory (or the content root for absolute-looking values).
 * Anything dynamic (variables, other function calls, interpolation) is skipped.
 */
object CfMappingResolver {

    // this.mappings["/alias"] = <value> ;
    private val INDEXED = Regex(
        """this\s*\.\s*mappings\s*\[\s*(['"])(.*?)\1\s*]\s*=\s*([^;\n]+)""",
        RegexOption.IGNORE_CASE,
    )
    // this.mappings = { "/a" = <value>, "/b" : <value> }  — entries scanned inside the braces.
    private val STRUCT_BLOCK = Regex(
        """this\s*\.\s*mappings\s*=\s*\{(.*?)}""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val STRUCT_ENTRY = Regex(
        """(['"])(.*?)\1\s*[:=]\s*([^,}\n]+)""",
    )
    private val EXPAND_PATH = Regex(
        """expandPath\s*\(\s*(['"])(.*?)\1\s*\)""",
        RegexOption.IGNORE_CASE,
    )

    /** Returns alias -> directory. Aliases are normalized to a leading "/" with no trailing "/". */
    fun parse(cfcText: String, cfcDir: VirtualFile?, contentRoot: VirtualFile?): Map<String, VirtualFile> {
        if (cfcDir == null) return emptyMap()
        val result = LinkedHashMap<String, VirtualFile>()

        fun add(aliasRaw: String, valueRaw: String) {
            val alias = normalizeAlias(aliasRaw) ?: return
            if (result.containsKey(alias)) return
            resolveValue(valueRaw.trim(), cfcDir, contentRoot)?.let { result[alias] = it }
        }

        INDEXED.findAll(cfcText).forEach { add(it.groupValues[2], it.groupValues[3]) }
        STRUCT_BLOCK.findAll(cfcText).forEach { block ->
            STRUCT_ENTRY.findAll(block.groupValues[1]).forEach { add(it.groupValues[2], it.groupValues[3]) }
        }
        return result
    }

    private fun normalizeAlias(raw: String): String? {
        var a = raw.trim().replace('\\', '/')
        if (a.isEmpty()) return null
        if (!a.startsWith("/")) a = "/$a"
        if (a.length > 1) a = a.trimEnd('/')
        return a
    }

    private fun resolveValue(value: String, cfcDir: VirtualFile, contentRoot: VirtualFile?): VirtualFile? {
        // Skip anything we can't evaluate statically (interpolation, variables).
        if (value.contains('#')) return null

        // expandPath("./rel") — resolved against the Application.cfc's directory.
        EXPAND_PATH.find(value)?.let { m ->
            val rel = m.groupValues[2].trim().replace('\\', '/')
            return cfcDir.findFileByRelativePath(rel)?.takeIf { it.isValid && it.isDirectory }
        }

        // Otherwise only a plain quoted string literal is usable.
        val trimmed = value.trim()
        val quoted = trimmed.length >= 2 &&
            ((trimmed.startsWith("\"") && trimmed.endsWith("\"")) || (trimmed.startsWith("'") && trimmed.endsWith("'")))
        if (!quoted) return null

        val literal = trimmed.substring(1, trimmed.length - 1).replace('\\', '/')
        val target = if (literal.startsWith("/")) {
            contentRoot?.findFileByRelativePath(literal.removePrefix("/"))
        } else {
            cfcDir.findFileByRelativePath(literal)
        }
        return target?.takeIf { it.isValid && it.isDirectory }
    }
}
