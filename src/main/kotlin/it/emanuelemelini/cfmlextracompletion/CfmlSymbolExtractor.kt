package it.emanuelemelini.cfmlextracompletion

import it.emanuelemelini.cfmlextracompletion.model.Param
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
    // A whole <cffunction ...> ... </cffunction> block: attributes in group 1, body (with cfargument) in 2.
    private val CFFUNCTION_BLOCK = Regex(
        """<cffunction\b([^>]*)>(.*?)</cffunction>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val CFARGUMENT = Regex("""<cfargument\b([^>]*?)/?>""", RegexOption.IGNORE_CASE)
    // public/private/... and an optional return type before `function name(args)`.
    private val SCRIPT_FUNCTION_FULL = Regex(
        """(?:\b(?:public|private|package|remote)\b\s+)?(?:\b([A-Za-z_][\w.]*)\b\s+)?\bfunction\s+([A-Za-z_]\w*)\s*\(([^)]*)\)""",
        RegexOption.IGNORE_CASE,
    )
    private val ACCESS_MODIFIERS = setOf("public", "private", "package", "remote")
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
    // Doc comments IMMEDIATELY preceding a function declaration (matched against the RAW, un-stripped text).
    // The comment body uses a tempered pattern so it can't span across an earlier "--->" / "*/" and swallow
    // unrelated content between an earlier comment and the function.
    private val TAG_DOC = Regex(
        """<!---((?:(?!--->)[\s\S])*)--->\s*<cffunction\b[^>]*?\bname\s*=\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val SCRIPT_DOC = Regex(
        """/\*\*((?:(?!\*/)[\s\S])*)\*/\s*(?:\b(?:public|private|package|remote)\b\s+)?(?:\b[\w.]+\b\s+)?\bfunction\s+([A-Za-z_]\w*)""",
        RegexOption.IGNORE_CASE,
    )
    private val CFML_BOOLEAN_TRUE = setOf("true", "yes", "1")

    fun extract(rawText: String, sourceLabel: String): List<Symbol> {
        val text = stripComments(rawText)
        val result = LinkedHashMap<Pair<String, SymbolScope>, Symbol>()

        fun addVar(scopeToken: String?, name: String, offset: Int) {
            val scope = mapScope(scopeToken) ?: return
            val key = name to scope
            result.putIfAbsent(key, Symbol(name, SymbolKind.VARIABLE, scope, sourceLabel, declarationOffset = offset))
        }

        fun addFunction(
            name: String,
            offset: Int,
            params: List<Param> = emptyList(),
            returnType: String? = null,
            doc: String? = null,
        ) {
            val key = name to SymbolScope.UNSCOPED
            result.putIfAbsent(
                key,
                Symbol(
                    name, SymbolKind.FUNCTION, SymbolScope.UNSCOPED, sourceLabel,
                    params, returnType?.ifBlank { null }, doc?.ifBlank { null }, offset,
                ),
            )
        }

        CFSET.findAll(text).forEach { addVar(it.groupValues[1].ifEmpty { null }, it.groupValues[2], it.groups[2]!!.range.first) }
        SCRIPT_ASSIGN.findAll(text).forEach { addVar(it.groupValues[1].ifEmpty { null }, it.groupValues[2], it.groups[2]!!.range.first) }
        CFPARAM.findAll(text).forEach {
            val raw = it.groupValues[1]
            val offset = it.groups[1]!!.range.first
            val dot = raw.lastIndexOf('.')
            if (dot >= 0) addVar(raw.substring(0, raw.indexOf('.')), raw.substring(dot + 1), offset + dot + 1)
            else addVar(null, raw, offset)
        }

        // Functions with signatures first (so they win over the name-only fallbacks below). Doc from the
        // tag's hint attribute here; a preceding comment (found in the raw text below) takes precedence.
        CFFUNCTION_BLOCK.findAll(text).forEach { m ->
            val attrs = m.groupValues[1]
            val name = attr(attrs, "name") ?: return@forEach
            val offset = attrValueOffset(attrs, "name")?.let { m.groups[1]!!.range.first + it } ?: m.range.first
            addFunction(name, offset, tagParams(m.groupValues[2]), attr(attrs, "returntype"), attr(attrs, "hint"))
        }
        SCRIPT_FUNCTION_FULL.findAll(text).forEach { m ->
            addFunction(m.groupValues[2], m.groups[2]!!.range.first, scriptParams(m.groupValues[3]), m.groupValues[1])
        }
        // Name-only fallbacks for anything the signature-aware patterns missed (e.g. no closing tag).
        CFFUNCTION.findAll(text).forEach { addFunction(it.groupValues[1], it.groups[1]!!.range.first) }
        SCRIPT_FUNCTION.findAll(text).forEach { addFunction(it.groupValues[1], it.groups[1]!!.range.first) }

        // Preceding doc comments are matched against the RAW text (comments are stripped in `text`).
        val commentDocs = functionCommentDocs(rawText)
        return result.values.map { s ->
            if (s.kind == SymbolKind.FUNCTION) {
                commentDocs[s.name.lowercase()]?.let { s.copy(doc = it) } ?: s
            } else {
                s
            }
        }
    }

    /** name (lowercased) -> cleaned doc comment, for functions preceded by a CFML/JS-doc comment. */
    private fun functionCommentDocs(rawText: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        TAG_DOC.findAll(rawText).forEach { map.putIfAbsent(it.groupValues[2].lowercase(), cleanComment(it.groupValues[1])) }
        SCRIPT_DOC.findAll(rawText).forEach { map.putIfAbsent(it.groupValues[2].lowercase(), cleanComment(it.groupValues[1])) }
        return map.filterValues { it.isNotBlank() }
    }

    /** Normalizes a comment body: drops per-line leading '*' and collapses to single-spaced lines. */
    private fun cleanComment(body: String): String =
        body.lines()
            .map { it.trim().removePrefix("*").trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    /** Parses `<cfargument ...>` tags out of a cffunction body into ordered params. */
    private fun tagParams(body: String): List<Param> =
        CFARGUMENT.findAll(body).mapNotNull { m ->
            val a = m.groupValues[1]
            val name = attr(a, "name") ?: return@mapNotNull null
            val required = attr(a, "required")?.lowercase() in CFML_BOOLEAN_TRUE
            Param(name, attr(a, "type"), optional = !required)
        }.toList()

    /** Parses a script function argument list like "required numeric amount, string symbol='$'". */
    private fun scriptParams(argList: String): List<Param> {
        if (argList.isBlank()) return emptyList()
        return argList.split(',').mapNotNull { raw ->
            val decl = raw.substringBefore('=').trim()
            if (decl.isEmpty()) return@mapNotNull null
            val words = decl.split(Regex("""\s+""")).toMutableList()
            val required = words.firstOrNull()?.equals("required", ignoreCase = true) == true
            if (required) words.removeAt(0)
            // Not-required arguments (with or without a default) are optional in CFML.
            when {
                words.isEmpty() -> null
                words.size == 1 -> Param(words[0], null, optional = !required)
                else -> Param(words[1], words[0], optional = !required)
            }
        }.toList()
    }

    /** Reads an HTML-style attribute value (name="value" / name='value') from a tag's attribute text. */
    private fun attr(attrs: String, name: String): String? =
        Regex("""\b$name\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(attrs)?.groupValues?.get(1)

    /** Offset within [attrs] where the value of [name] starts (just after the opening quote). */
    private fun attrValueOffset(attrs: String, name: String): Int? =
        Regex("""\b$name\s*=\s*["']""", RegexOption.IGNORE_CASE).find(attrs)?.range?.let { it.last + 1 }

    private fun mapScope(token: String?): SymbolScope? = when (token?.lowercase()) {
        null, "", "variables" -> SymbolScope.UNSCOPED
        "application" -> SymbolScope.APPLICATION
        "this" -> SymbolScope.THIS
        "session" -> SymbolScope.SESSION
        "request" -> SymbolScope.REQUEST
        else -> null // server/url/form/local/arguments/cgi/...: out of scope for this plugin
    }

    /**
     * Blanks comments to spaces (keeping newlines) instead of deleting them, so every match offset in the
     * returned text maps 1:1 to the same offset in the original document — needed for go-to-declaration.
     */
    private fun stripComments(text: String): String {
        var out = CFML_COMMENT.replace(text, ::blank)
        out = BLOCK_COMMENT.replace(out, ::blank)
        out = LINE_COMMENT.replace(out, ::blank)
        return out
    }

    private fun blank(m: MatchResult): String =
        buildString(m.value.length) { m.value.forEach { append(if (it == '\n') '\n' else ' ') } }
}
