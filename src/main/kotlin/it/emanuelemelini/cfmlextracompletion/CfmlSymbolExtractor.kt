package it.emanuelemelini.cfmlextracompletion

import it.emanuelemelini.cfmlextracompletion.model.Symbol
import it.emanuelemelini.cfmlextracompletion.model.SymbolKind
import it.emanuelemelini.cfmlextracompletion.model.SymbolScope

/**
 * Extracts variable and function declarations from CFML source text using tolerant regexes.
 *
 * Text-based on purpose: it avoids compiling against the CFML plugin's internal PSI classes (which are
 * not a stable API), works on tag and script syntax alike, and degrades gracefully on malformed code —
 * a spurious extra completion is harmless, a missed one is the status quo we're improving on.
 *
 * Every match is tagged with the scope detected on its left-hand side. Only scopes this plugin cares about
 * are kept (UNSCOPED / variables, application, this); function-local and per-request scopes are dropped.
 * Callers decide which of the returned scopes are relevant for a given source file.
 */
object CfmlSymbolExtractor {

    private val CFML_COMMENT = Regex("""<!---.*?--->""", setOf(RegexOption.DOT_MATCHES_ALL))
    private val BLOCK_COMMENT = Regex("""/\*.*?\*/""", setOf(RegexOption.DOT_MATCHES_ALL))
    private val LINE_COMMENT = Regex("""//[^\n]*""")

    private const val SCOPE_ALT = "variables|application|session|request|server|this|local|url|form|arguments"

    private val CFSET = Regex(
        """<cfset\s+(?:var\s+)?(?:($SCOPE_ALT)\s*\.\s*)?([A-Za-z_]\w*)""",
        RegexOption.IGNORE_CASE,
    )
    private val CFPARAM = Regex(
        """<cfparam\b[^>]*?\bname\s*=\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val CFFUNCTION = Regex(
        """<cffunction\b[^>]*?\bname\s*=\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    // Anchored to a statement boundary: line start, ; { }, or a tag close '>' (so a one-line
    // "<cfscript> application.x = 1;" is caught). Tag attributes like name="x" sit after the tag
    // name/whitespace, not after these boundaries, so they are not mistaken for assignments.
    private val SCRIPT_ASSIGN = Regex(
        """(?:^|[;{}>])\s*(?:var\s+)?(?:($SCOPE_ALT)\s*\.\s*)?([A-Za-z_]\w*)\s*=(?!=)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )
    private val SCRIPT_FUNCTION = Regex(
        """\bfunction\s+([A-Za-z_]\w*)\s*\(""",
        RegexOption.IGNORE_CASE,
    )

    fun extract(rawText: String, sourceLabel: String): List<Symbol> {
        val text = stripComments(rawText)
        val result = LinkedHashMap<Pair<String, SymbolScope>, Symbol>()

        fun addVar(scopeToken: String?, name: String) {
            val scope = mapScope(scopeToken) ?: return
            val key = name to scope
            result.putIfAbsent(key, Symbol(name, SymbolKind.VARIABLE, scope, sourceLabel))
        }

        fun addFunction(name: String) {
            val key = name to SymbolScope.UNSCOPED
            result.putIfAbsent(key, Symbol(name, SymbolKind.FUNCTION, SymbolScope.UNSCOPED, sourceLabel))
        }

        CFSET.findAll(text).forEach { addVar(it.groupValues[1].ifEmpty { null }, it.groupValues[2]) }
        SCRIPT_ASSIGN.findAll(text).forEach { addVar(it.groupValues[1].ifEmpty { null }, it.groupValues[2]) }
        CFPARAM.findAll(text).forEach {
            val raw = it.groupValues[1]
            val dot = raw.lastIndexOf('.')
            if (dot >= 0) addVar(raw.substring(0, raw.indexOf('.')), raw.substring(dot + 1))
            else addVar(null, raw)
        }
        CFFUNCTION.findAll(text).forEach { addFunction(it.groupValues[1]) }
        SCRIPT_FUNCTION.findAll(text).forEach { addFunction(it.groupValues[1]) }

        return result.values.toList()
    }

    private fun mapScope(token: String?): SymbolScope? = when (token?.lowercase()) {
        null, "", "variables" -> SymbolScope.UNSCOPED
        "application" -> SymbolScope.APPLICATION
        "this" -> SymbolScope.THIS
        else -> null // session/request/server/url/form/local/arguments: out of scope for this plugin
    }

    private fun stripComments(text: String): String {
        var out = CFML_COMMENT.replace(text, "")
        out = BLOCK_COMMENT.replace(out, "")
        out = LINE_COMMENT.replace(out, "")
        return out
    }
}
