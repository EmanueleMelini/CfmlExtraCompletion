package it.emanuelemelini.cfmlextracompletion

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.util.ParenthesesInsertHandler
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext
import it.emanuelemelini.cfmlextracompletion.model.Symbol
import it.emanuelemelini.cfmlextracompletion.model.SymbolKind
import it.emanuelemelini.cfmlextracompletion.model.SymbolScope

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

            // Full document text — NOT originalFile.text: a .cfm is a multi-language view and a cfquery body
            // is SQL-injected, so the CFML view's text can omit the SELECT list we parse for columns.
            val docText = editor.document.immutableCharSequence
            val caret = editor.caretModel.offset

            // The whole reference being typed, e.g. "application.te", "variables.x", or just "app".
            val ref = referencePrefix(docText, caret)

            // Completing a query name inside cfoutput/cfloop query="…".
            if (CfmlQueryScopes.inQueryAttribute(docText, caret)) {
                val rs = result.withPrefixMatcher(ref)
                for (name in CfmlQueryScopes.queryNames(docText.toString())) {
                    rs.addElement(
                        LookupElementBuilder.create(name).withIcon(AllIcons.Nodes.DataTables).withTypeText("query", true),
                    )
                }
                return
            }

            val aggregate = CfmlProjectSymbols.of(originalFile)

            // Scope qualifier = the token before the first dot, if any.
            val dot = ref.indexOf('.')
            val scope = if (dot >= 0) ref.substring(0, dot).lowercase() else null

            // Column symbols for the query the caret is looping over (cfoutput/cfloop query="q"), if any.
            // Columns include SELECT * expansion from an attached data source when available.
            val query = CfmlQueryResolver.activeQuery(originalFile.project, docText, caret)
            val queryColumns = query?.columns.orEmpty().map {
                Symbol(it.name, SymbolKind.VARIABLE, SymbolScope.UNSCOPED, "query: ${query!!.name}")
            }

            // Match against the full reference (including "application.") so the typed portion is
            // prefix-highlighted and items surface while typing the scope name — like the built-in.
            val rs = result.withPrefixMatcher(ref)
            val emitted = HashSet<String>()

            fun emit(symbols: List<Symbol>, qualifier: String?) {
                for (s in symbols) {
                    val lookup = if (qualifier != null) "$qualifier.${s.name}" else s.name
                    if (emitted.add(lookup)) rs.addElement(buildLookup(s, lookup))
                }
            }

            // Query columns qualified by the query name: q.<column>. Resolved by NAME, so this also works
            // outside a cfoutput/cfloop block — ColdFusion allows q.col anywhere to read the first row.
            if (scope != null) {
                val named = CfmlQueryResolver.queryByName(originalFile.project, docText, scope)
                if (named != null) {
                    emit(
                        named.columns.map { Symbol(it.name, SymbolKind.VARIABLE, SymbolScope.UNSCOPED, "query: ${named.name}") },
                        ref.substring(0, dot), // keep what the user actually typed as the qualifier
                    )
                    return
                }
            }

            when (scope) {
                // No dot yet: offer unscoped names bare, and the scope-qualified names so they appear while
                // typing the scope word (each matched against whatever has been typed).
                null -> {
                    emit(aggregate.unscoped, null)
                    emit(aggregate.application, "application")
                    emit(aggregate.session, "session")
                    emit(aggregate.request, "request")
                    emit(queryColumns, null) // inside a cfoutput/cfloop query block, columns are unqualified
                }
                "variables" -> emit(aggregate.unscoped, "variables")
                "application" -> emit(aggregate.application, "application")
                "session" -> emit(aggregate.session, "session")
                "request" -> emit(aggregate.request, "request")
                // Skip the standard Application.cfc this.* settings — the built-in already completes those,
                // so contributing them again would double the entry. Custom this.* properties still show.
                "this" -> emit(aggregate.thisScope.filter { it.name.lowercase() !in STANDARD_THIS_SETTINGS }, "this")
                else -> return // some other CFML scope (server, url, form, cgi, ...) — leave to built-in
            }
        }

        // The lookup string IS the qualified form ("application.foo"), so the platform highlights the typed
        // portion (including the scope) and insertion replaces the whole reference — matching the built-in.
        // The symbol is the lookup object, so the documentation provider can render its doc/signature.
        private fun buildLookup(symbol: Symbol, lookupString: String): LookupElementBuilder =
            when (symbol.kind) {
                SymbolKind.FUNCTION ->
                    LookupElementBuilder.create(symbol, lookupString)
                        .withIcon(AllIcons.Nodes.Method)
                        .withTailText("(" + symbol.params.joinToString(", ") { it.render() } + ")", true)
                        .withTypeText(symbol.returnType ?: symbol.sourceLabel, true)
                        .withInsertHandler(if (symbol.params.isEmpty()) NO_PARAM_INSERT else PARAM_INSERT)
                SymbolKind.VARIABLE ->
                    LookupElementBuilder.create(symbol, lookupString)
                        .withIcon(AllIcons.Nodes.Variable)
                        .withTypeText(symbol.sourceLabel, true)
            }
    }

    companion object {
        /** No-arg function: insert "()", caret after. */
        private val NO_PARAM_INSERT = ParenthesesInsertHandler.NO_PARAMETERS

        /** Function with args: insert "()", caret inside, then pop up parameter info. */
        private val PARAM_INSERT = InsertHandler<LookupElement> { context, item ->
            ParenthesesInsertHandler.WITH_PARAMETERS.handleInsert(context, item)
            AutoPopupController.getInstance(context.project).autoPopupParameterInfo(context.editor, null)
        }

        /**
         * Standard Application.cfc `this`-scope settings that the bundled CFML plugin already completes.
         * We skip these so they aren't offered twice; custom this.* properties are unaffected. All lowercase.
         */
        private val STANDARD_THIS_SETTINGS = setOf(
            "name", "applicationtimeout", "sessionmanagement", "sessiontimeout", "setclientcookies",
            "setdomaincookies", "clientmanagement", "clientstorage", "loginstorage", "scriptprotect",
            "securejson", "securejsonprefix", "serialization", "invokeimplicitaccessor", "datasource",
            "datasources", "defaultdatasource", "ormenabled", "ormsettings", "mappings", "customtagpaths",
            "welcomefilelist", "timezone", "localmode", "sessioncookie", "authcookie", "applicationtoken",
            "javasettings", "s3", "enablerobustexception", "triggerdatamember",
            "searchimplicitscopes", "passarraybyreference", "wssettings", "cgireadonly", "componentpaths",
        )

        private fun isIdent(c: Char) = c.isLetterOrDigit() || c == '_'

        /** Reads the full reference being typed up to the caret, e.g. "application.te" or "app".
         * @return String */
        private fun referencePrefix(chars: CharSequence, caret: Int): String {
            var i = caret
            while (i > 0) {
                val c = chars[i - 1]
                if (isIdent(c) || c == '.') i-- else break
            }
            return chars.subSequence(i, caret).toString()
        }
    }
}
