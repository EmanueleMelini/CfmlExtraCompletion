package it.emanuelemelini.cfmlextracompletion

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.FakePsiElement
import javax.swing.Icon

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

        val docText = file.viewProvider.document?.immutableCharSequence ?: file.text
        symbolTarget(file, element.text)?.let { return arrayOf(it) }
        queryTarget(file, docText, element.text, offset)?.let { return arrayOf(it) }
        mappedIncludeTarget(file, docText, offset)?.let { return arrayOf(it) }
        return null
    }

    /** Navigate a query name to its <cfquery>/queryNew, or a query column to its spot in that definition. */
    private fun queryTarget(file: PsiFile, docText: CharSequence, text: String?, offset: Int): PsiElement? {
        val name = trailingIdentifier(text) ?: return null
        val defs = CfmlQueryScopes.queryDefs(docText.toString())
        if (defs.isEmpty()) return null

        // The identifier is a query variable -> its definition.
        defs.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return targetAt(file, it.nameOffset, it.name) }

        // A column: either "queryName.column" (valid anywhere) or a bare column inside that query's loop.
        val query = CfmlQueryResolver.qualifiedQueryAt(file.project, docText, offset)
            ?: CfmlQueryResolver.activeQuery(file.project, docText, offset)
            ?: return null
        query.columns.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?.let { return targetAt(file, it.offset, it.name) }
        return null
    }

    /**
     * A navigation target for a raw (file, offset).
     *
     * Deliberately NOT `file.findElementAt(offset)`: a cfquery's SELECT list is an injected SQL region, and
     * returning a PSI element from inside an injection does not navigate (that is why SELECT * worked — its
     * offset points at the query's name attribute — while explicit column lists did not).
     */
    private fun targetAt(file: PsiFile, offset: Int, label: String): PsiElement = OffsetTarget(file, offset, label)

    private class OffsetTarget(
        private val file: PsiFile,
        private val offset: Int,
        private val label: String,
    ) : FakePsiElement() {
        override fun getParent(): PsiElement = file
        override fun getContainingFile(): PsiFile = file
        override fun getProject(): Project = file.project
        override fun getName(): String = label
        override fun getTextRange(): TextRange = TextRange(offset, offset)
        override fun getTextOffset(): Int = offset
        override fun isValid(): Boolean = file.isValid
        override fun canNavigate(): Boolean = file.virtualFile != null
        override fun canNavigateToSource(): Boolean = canNavigate()
        override fun navigate(requestFocus: Boolean) {
            val vf = file.virtualFile ?: return
            OpenFileDescriptor(file.project, vf, offset).navigate(requestFocus)
        }

        override fun getPresentation(): ItemPresentation = object : ItemPresentation {
            override fun getPresentableText(): String = label
            override fun getLocationString(): String? = file.name
            override fun getIcon(unused: Boolean): Icon? = null
        }
    }

    /** Navigate an identifier to the variable/function we contributed (handles qualified refs too). */
    private fun symbolTarget(file: PsiFile, text: String?): PsiElement? {
        val name = trailingIdentifier(text) ?: return null
        val decl = CfmlProjectSymbols.of(file).declarations[name.lowercase()] ?: return null
        val targetFile = PsiManager.getInstance(file.project).findFile(decl.file) ?: return null
        return targetAt(targetFile, decl.offset, name)
    }

    /** Navigate a cfinclude/cfmodule template that resolves through a ColdFusion mapping to its file. */
    private fun mappedIncludeTarget(file: PsiFile, docText: CharSequence, offset: Int): PsiElement? {
        val vfile = file.virtualFile ?: return null
        val path = templatePathAt(docText, offset) ?: return null
        val mappings = CfmlProjectSymbols.of(file).mappings
        val matchesAlias = mappings.keys.any { path.equals(it, ignoreCase = true) || path.startsWith("$it/", ignoreCase = true) }
        if (!matchesAlias) return null // non-mapped paths are handled by the built-in file reference
        val target = IncludeGraphResolver.resolveTemplate(file.project, vfile, path, mappings) ?: return null
        return PsiManager.getInstance(file.project).findFile(target)
    }

    private companion object {
        private val TEMPLATE_ATTR_END = Regex("""\b(?:template|name)\s*=\s*$""", RegexOption.IGNORE_CASE)
        private val INCLUDE_TAG = Regex("""<cf(?:include|module)\b""", RegexOption.IGNORE_CASE)

        /** Trailing identifier of a possibly-qualified reference, e.g. "application.dsn" -> "dsn". */
        private fun trailingIdentifier(text: String?): String? {
            if (text.isNullOrEmpty()) return null
            fun ident(c: Char) = c.isLetterOrDigit() || c == '_'
            var end = text.length
            while (end > 0 && !ident(text[end - 1])) end--
            if (end == 0) return null
            var start = end
            while (start > 0 && ident(text[start - 1])) start--
            return text.substring(start, end)
        }

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
