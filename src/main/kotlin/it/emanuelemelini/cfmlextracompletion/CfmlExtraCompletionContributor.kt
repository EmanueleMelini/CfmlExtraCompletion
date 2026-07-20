package it.emanuelemelini.cfmlextracompletion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.util.ParenthesesInsertHandler
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext
import it.emanuelemelini.cfmlextracompletion.model.Symbol
import it.emanuelemelini.cfmlextracompletion.model.SymbolKind

/**
 * Adds project-wide CFML completions the bundled plugin doesn't: Application.cfm page symbols,
 * Application.cfc application-scope variables, and symbols from cfinclude'd files. Results are added on
 * top of the built-in completion — nothing is removed.
 */
class CfmlExtraCompletionContributor : CompletionContributor() {

    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), Provider())
    }

    private class Provider : CompletionProvider<CompletionParameters>() {
        override fun addCompletions(
            parameters: CompletionParameters,
            context: ProcessingContext,
            result: CompletionResultSet,
        ) {
            val originalFile = parameters.originalFile
            if (!CfmlProjectSymbols.isCfmlFile(originalFile)) return
            originalFile.virtualFile ?: return
            val editor = parameters.editor

            val typed = readTypedContext(editor.document.immutableCharSequence, editor.caretModel.offset)
            val aggregate = CfmlProjectSymbols.of(originalFile)
            val symbols = when (typed.scopeWord) {
                null, "", "variables" -> aggregate.unscoped
                "application" -> aggregate.application
                "this" -> aggregate.thisScope
                else -> return // some other CFML scope (session, request, url, ...) — leave to built-in
            }
            if (symbols.isEmpty()) return

            val rs = result.withPrefixMatcher(typed.partial)
            val emitted = HashSet<String>()
            for (s in symbols) {
                if (emitted.add(s.name)) rs.addElement(buildLookup(s))
            }
        }

        private fun buildLookup(symbol: Symbol) =
            when (symbol.kind) {
                SymbolKind.FUNCTION ->
                    LookupElementBuilder.create(symbol.name)
                        .withIcon(AllIcons.Nodes.Method)
                        .withTypeText(symbol.sourceLabel, true)
                        .withInsertHandler(ParenthesesInsertHandler.WITH_PARAMETERS)
                SymbolKind.VARIABLE ->
                    LookupElementBuilder.create(symbol.name)
                        .withIcon(AllIcons.Nodes.Variable)
                        .withTypeText(symbol.sourceLabel, true)
            }
    }

    private data class TypedContext(val scopeWord: String?, val partial: String)

    companion object {
        private fun isIdent(c: Char) = c.isLetterOrDigit() || c == '_'

        /** Reads the identifier being typed and the scope qualifier before it (e.g. "application" in "application.ds"). */
        private fun readTypedContext(chars: CharSequence, caret: Int): TypedContext {
            var i = caret
            while (i > 0 && isIdent(chars[i - 1])) i--
            val partial = chars.subSequence(i, caret).toString()

            var p = i
            while (p > 0 && chars[p - 1].isWhitespace()) p--
            if (p > 0 && chars[p - 1] == '.') {
                var d = p - 1
                while (d > 0 && chars[d - 1].isWhitespace()) d--
                var s = d
                while (s > 0 && isIdent(chars[s - 1])) s--
                val word = chars.subSequence(s, d).toString()
                if (word.isNotEmpty()) return TypedContext(word.lowercase(), partial)
            }
            return TypedContext(null, partial)
        }
    }
}
