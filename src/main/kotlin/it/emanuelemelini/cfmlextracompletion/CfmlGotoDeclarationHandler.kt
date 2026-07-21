package it.emanuelemelini.cfmlextracompletion

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager

/**
 * Ctrl/Cmd+Click (and Go To Declaration) for the symbols this plugin contributes: jumps to where the
 * variable or function is declared in Application.cfm / Application.cfc / a cfinclude'd file. The bundled
 * CFML plugin can't resolve these, so it offers no navigation for them; we return null for anything we
 * don't recognize so its navigation for real CFML symbols is unaffected.
 */
class CfmlGotoDeclarationHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(element: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
        if (element == null) return null
        val file = element.containingFile ?: return null
        if (!CfmlProjectSymbols.isCfmlFile(file)) return null

        symbolTarget(file, element.text)?.let { return arrayOf(it) }
        mappedIncludeTarget(file, offset)?.let { return arrayOf(it) }
        return null
    }

    /** Navigate an identifier to the variable/function we contributed. */
    private fun symbolTarget(file: PsiFile, name: String?): PsiElement? {
        if (name.isNullOrEmpty() || !name.all { it.isLetterOrDigit() || it == '_' }) return null
        val decl = CfmlProjectSymbols.of(file).declarations[name.lowercase()] ?: return null
        val targetFile = PsiManager.getInstance(file.project).findFile(decl.file) ?: return null
        return targetFile.findElementAt(decl.offset) ?: targetFile
    }

    /** Navigate a cfinclude/cfmodule template that resolves through a ColdFusion mapping to its file. */
    private fun mappedIncludeTarget(file: PsiFile, offset: Int): PsiElement? {
        val vfile = file.virtualFile ?: return null
        val path = templatePathAt(file.text, offset) ?: return null
        val mappings = CfmlProjectSymbols.of(file).mappings
        val matchesAlias = mappings.keys.any { path.equals(it, ignoreCase = true) || path.startsWith("$it/", ignoreCase = true) }
        if (!matchesAlias) return null // non-mapped paths are handled by the built-in file reference
        val target = IncludeGraphResolver.resolveTemplate(file.project, vfile, path, mappings) ?: return null
        return PsiManager.getInstance(file.project).findFile(target)
    }

    private companion object {
        private val TEMPLATE_ATTR_END = Regex("""\b(?:template|name)\s*=\s*$""", RegexOption.IGNORE_CASE)
        private val INCLUDE_TAG = Regex("""<cf(?:include|module)\b""", RegexOption.IGNORE_CASE)

        /** If [offset] is inside a cfinclude/cfmodule template="..." string, returns that path. */
        fun templatePathAt(text: CharSequence, offset: Int): String? {
            // Opening quote to the left (without crossing a newline or tag end).
            var start = -1
            var quote = ' '
            var i = offset - 1
            while (i >= 0) {
                val c = text[i]
                if (c == '"' || c == '\'') { start = i; quote = c; break }
                if (c == '\n' || c == '>' || c == '<') return null
                i--
            }
            if (start < 0) return null

            // Closing quote to the right.
            var end = -1
            var j = offset
            while (j < text.length) {
                val c = text[j]
                if (c == quote) { end = j; break }
                if (c == '\n') return null
                j++
            }
            if (end < 0) return null

            val pre = text.subSequence(0, start).toString()
            if (!TEMPLATE_ATTR_END.containsMatchIn(pre)) return null
            val lt = pre.lastIndexOf('<')
            if (lt < 0) return null
            val tagPart = pre.substring(lt)
            if (tagPart.contains('>') || !INCLUDE_TAG.containsMatchIn(tagPart)) return null

            return text.subSequence(start + 1, end).toString()
        }
    }
}
