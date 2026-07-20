package it.emanuelemelini.cfmlextracompletion

import com.intellij.codeInspection.InspectionSuppressor
import com.intellij.codeInspection.SuppressQuickFix
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * Stops the bundled CFML plugin from underlining things we know about but its own resolver doesn't:
 *
 *  - [CFML_REFERENCE_INSPECTION] — "unresolved variable" (WEAK_WARNING) on identifiers that come from
 *    Application.cfm, Application.cfc application/this scope, or a cfinclude'd file.
 *  - [CFML_FILE_REFERENCE_INSPECTION] — "unresolved file" (WARNING) on a cfinclude/cfmodule template path
 *    that only resolves through a ColdFusion mapping (this.mappings), which CFML doesn't apply.
 *
 * We suppress only these two inspections, and only where our own resolver succeeds, so genuinely broken
 * references stay flagged. This avoids depending on CFML's private resolution internals.
 */
class CfmlReferenceInspectionSuppressor : InspectionSuppressor {

    override fun isSuppressedFor(element: PsiElement, toolId: String): Boolean {
        val file = element.containingFile ?: return false
        if (!CfmlProjectSymbols.isCfmlFile(file)) return false

        return when {
            toolId.equals(CFML_REFERENCE_INSPECTION, ignoreCase = true) -> isKnownVariable(element, file)
            toolId.equals(CFML_FILE_REFERENCE_INSPECTION, ignoreCase = true) -> isResolvableTemplate(element, file)
            else -> false
        }
    }

    override fun getSuppressActions(element: PsiElement?, toolId: String): Array<SuppressQuickFix> =
        SuppressQuickFix.EMPTY_ARRAY

    private fun isKnownVariable(element: PsiElement, file: PsiFile): Boolean {
        val known = CfmlProjectSymbols.of(file).allNames
        if (known.isEmpty()) return false
        val name = trailingIdentifier(element.text) ?: return false
        return name in known
    }

    private fun isResolvableTemplate(element: PsiElement, file: PsiFile): Boolean {
        val vfile = file.virtualFile ?: return false
        val path = templatePath(element.text) ?: return false
        val mappings = CfmlProjectSymbols.of(file).mappings
        return IncludeGraphResolver.resolveTemplate(file.project, vfile, path, mappings) != null
    }

    private companion object {
        const val CFML_REFERENCE_INSPECTION = "CfmlReferenceInspection"
        const val CFML_FILE_REFERENCE_INSPECTION = "CfmlFileReference"

        private val TEMPLATE_ATTR = Regex("""template\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

        private fun isIdent(c: Char) = c.isLetterOrDigit() || c == '_'

        /** Extracts the last identifier of a (possibly qualified) reference, e.g. "application.dsn" -> "dsn". */
        fun trailingIdentifier(text: String?): String? {
            if (text.isNullOrEmpty()) return null
            var end = text.length
            while (end > 0 && !isIdent(text[end - 1])) end--
            if (end == 0) return null
            var start = end
            while (start > 0 && isIdent(text[start - 1])) start--
            return text.substring(start, end)
        }

        /** Extracts a template path from either a bare path element or a whole cfinclude/cfmodule tag. */
        fun templatePath(text: String?): String? {
            if (text.isNullOrBlank()) return null
            TEMPLATE_ATTR.find(text)?.let { return it.groupValues[1] }
            val trimmed = text.trim().trim('"', '\'')
            if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() } || trimmed.contains('<')) return null
            return trimmed
        }
    }
}
