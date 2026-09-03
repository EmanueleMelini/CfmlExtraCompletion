package it.emanuelemelini.cfmlextracompletion

import com.intellij.lang.documentation.DocumentationProviderEx
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.FakePsiElement
import it.emanuelemelini.cfmlextracompletion.model.Symbol
import it.emanuelemelini.cfmlextracompletion.model.SymbolKind
import javax.swing.Icon

/**
 * Shows documentation for the functions this plugin contributes — the preceding comment (a CFML
 * `<!--- --->` block or a JS-doc block) or the tag's `hint`, plus the signature and origin. Surfaces both
 * in the completion popup (the doc pane beside the list) and via Quick Documentation (Ctrl+Q) on the name.
 *
 * This is beyond what the bundled CFML plugin offers for these functions; for anything we don't recognize
 * we return null so other providers (e.g. built-in) handle it.
 */
class CfmlDocumentationProvider : DocumentationProviderEx() {

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        if (element is CfmlDocElement) return element.html
        val file = element?.containingFile ?: return null
        if (!CfmlProjectSymbols.isCfmlFile(file)) return null
        val name = (originalElement ?: element).text ?: return null
        return findFunction(file, name)?.let(::render)
    }

    override fun getCustomDocumentationElement(
        editor: Editor,
        file: PsiFile,
        contextElement: PsiElement?,
        targetOffset: Int,
    ): PsiElement? {
        if (contextElement == null || !CfmlProjectSymbols.isCfmlFile(file)) return null
        val name = trailingIdentifier(contextElement.text) ?: return null

        findFunction(file, name)?.let { return CfmlDocElement(contextElement, it.name, render(it)) }
        queryDoc(editor, name, targetOffset)?.let { return CfmlDocElement(contextElement, name, it) }
        return null
    }

    /** Doc for a query name (its column list) or a query column (its owning query), or null. */
    private fun queryDoc(editor: Editor, name: String, offset: Int): String? {
        val project = editor.project ?: return null
        val docText = editor.document.immutableCharSequence
        val defs = CfmlQueryScopes.queryDefs(docText.toString())
        if (defs.isEmpty()) return null

        defs.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { def ->
            val cols = CfmlQueryResolver.resolveColumns(project, def)
                .joinToString(", ") { it.name }.ifEmpty { "unknown (SELECT * or dynamic)" }
            return "<b>query ${escape(def.name)}</b><br/>columns: ${escape(cols)}"
        }
        val active = CfmlQueryResolver.activeQuery(project, docText, offset) ?: return null
        if (active.columns.any { it.name.equals(name, ignoreCase = true) }) {
            return "<b>${escape(name)}</b><br/>column of query <b>${escape(active.name)}</b>"
        }
        return null
    }

    override fun getDocumentationElementForLookupItem(
        psiManager: PsiManager,
        `object`: Any?,
        element: PsiElement?,
    ): PsiElement? {
        val symbol = `object` as? Symbol ?: return null
        if (symbol.kind != SymbolKind.FUNCTION) return null
        val context = element ?: return null
        return CfmlDocElement(context, symbol.name, render(symbol))
    }

    private fun findFunction(file: PsiFile, name: String): Symbol? {
        val agg = CfmlProjectSymbols.of(file)
        // Note: the aggregate excludes the current file, so at a function's own declaration the built-in
        // resolves it and shows the <cffunction> tag help — we don't fight that (it caused a flicker).
        return (agg.unscoped.asSequence() + agg.application.asSequence() + agg.thisScope.asSequence())
            .firstOrNull { it.kind == SymbolKind.FUNCTION && it.name.equals(name, ignoreCase = true) }
    }

    private fun render(s: Symbol): String {
        val signature = buildString {
            append(s.name).append('(')
            append(s.params.joinToString(", ") { it.render() })
            append(')')
            s.returnType?.let { append(" : ").append(it) }
        }
        return buildString {
            append("<b>").append(escape(signature)).append("</b>")
            if (!s.doc.isNullOrBlank()) append("<br/><br/>").append(escape(s.doc))
            append("<br/><br/><small>").append(escape(s.sourceLabel)).append("</small>")
        }
    }

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

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

    /**
     * Synthetic element carrying pre-rendered doc HTML for our contributed functions. It must be
     * "presentable" (non-null [getPresentation]) or the documentation/hover subsystem throws when it
     * builds a target presentation for it.
     */
    private class CfmlDocElement(
        private val context: PsiElement,
        private val displayName: String,
        val html: String,
    ) : FakePsiElement() {
        override fun getParent(): PsiElement = context
        override fun getProject(): Project = context.project
        override fun getContainingFile(): PsiFile? = context.containingFile
        override fun getTextRange(): TextRange? = context.textRange
        override fun getTextOffset(): Int = context.textRange?.startOffset ?: 0
        override fun isValid(): Boolean = context.isValid
        override fun getName(): String = displayName
        override fun getPresentation(): ItemPresentation = object : ItemPresentation {
            override fun getPresentableText(): String = displayName
            override fun getLocationString(): String? = null
            override fun getIcon(unused: Boolean): Icon? = null
        }
    }
}
