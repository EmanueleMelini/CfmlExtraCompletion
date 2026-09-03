package it.emanuelemelini.cfmlextracompletion

/**
 * Resolves query-column completion inside `<cfoutput query="q">` / `<cfloop query="q">` blocks.
 *
 * Best-effort and same-file: a query's columns are read from a `<cfquery name="q"> SELECT … FROM …`
 * (explicit column list) or a `q = queryNew("col1,col2,…")` literal in the same file. `SELECT *`,
 * expressions without an alias, and dynamically built SQL yield no columns.
 */
object CfmlQueryScopes {

    data class QueryScope(val name: String, val columns: List<String>)

    private val CFQUERY = Regex("""<cfquery\b([^>]*)>(.*?)</cfquery>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val QUERYNEW = Regex("""\b([A-Za-z_]\w*)\s*=\s*queryNew\s*\(\s*(['"])(.*?)\2""", RegexOption.IGNORE_CASE)
    private val SELECT_KW = Regex("""\bselect\b""", RegexOption.IGNORE_CASE)
    private val AS_ALIAS = Regex("""\bas\s+([A-Za-z_]\w*)\s*$""", RegexOption.IGNORE_CASE)
    private val DOTTED_IDENT = Regex("""^[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*$""")
    private val PLAIN_IDENT = Regex("""^[A-Za-z_]\w*$""")
    private val LEADING_QUALIFIER = Regex("""^(?:distinct\s+|top\s+\d+\s+)+""", RegexOption.IGNORE_CASE)
    private val TAG = Regex("""<(/?)(cfoutput|cfloop)\b([^>]*)>""", RegexOption.IGNORE_CASE)
    private val CFML_COMMENT = Regex("""<!---.*?--->""", RegexOption.DOT_MATCHES_ALL)
    private val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
    private val QUERY_ATTR_END = Regex("""\bquery\s*=\s*$""", RegexOption.IGNORE_CASE)
    private val OUTPUT_LOOP_TAG = Regex("""<cf(?:output|loop)\b""", RegexOption.IGNORE_CASE)
    private val ATTR = { attrs: String, name: String ->
        Regex("""\b$name\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(attrs)?.groupValues?.get(1)
    }

    /**
     * The query whose row scope the caret is in, with its columns — or null.
     *
     * [documentText] must be the FULL document text (e.g. editor document / host view), not a single
     * language view: a `<cfquery>` body is SQL-injected, so the CFML view's text may omit the SELECT list.
     */
    fun activeQuery(documentText: CharSequence, caret: Int): QueryScope? {
        // enclosingQueryName / columnsByQuery each strip comments themselves.
        val name = enclosingQueryName(documentText, caret) ?: return null
        val columns = columnsByQuery(documentText.toString())[name.lowercase()] ?: return null
        return QueryScope(name, columns)
    }

    /**
     * Blanks CFML/block comments to same-length whitespace (newlines kept), so comment text that mentions
     * cfquery/cfoutput/queryNew can't poison the query parse, while offsets still map to the real document.
     */
    private fun stripComments(text: String): String {
        val out = CFML_COMMENT.replace(text, ::blank)
        return BLOCK_COMMENT.replace(out, ::blank)
    }

    private fun blank(m: MatchResult): String =
        buildString(m.value.length) { m.value.forEach { append(if (it == '\n') '\n' else ' ') } }

    /**
     * If the identifier at [offset] is written as `qualifier.identifier`, returns the qualifier token.
     * Used to resolve `queryName.column` references, which ColdFusion allows anywhere (reading the first
     * row) — not just inside a `<cfoutput query="...">` loop.
     */
    fun qualifierAt(text: CharSequence, offset: Int): String? {
        fun isIdent(c: Char) = c.isLetterOrDigit() || c == '_'
        var i = offset.coerceIn(0, text.length)
        // Move to the start of the identifier under/next to the offset.
        while (i > 0 && isIdent(text[i - 1])) i--
        var p = i
        while (p > 0 && text[p - 1].isWhitespace()) p--
        if (p == 0 || text[p - 1] != '.') return null
        var d = p - 1
        while (d > 0 && text[d - 1].isWhitespace()) d--
        var s = d
        while (s > 0 && isIdent(text[s - 1])) s--
        return text.subSequence(s, d).toString().takeIf { it.isNotEmpty() }
    }

    /** Names (original case) of all resolvable queries in the file — for completing a query="" attribute. */
    fun queryNames(text: String): Set<String> {
        val src = stripComments(text)
        val names = LinkedHashSet<String>()
        CFQUERY.findAll(src).forEach { m -> ATTR(m.groupValues[1], "name")?.let { names.add(it) } }
        QUERYNEW.findAll(src).forEach { names.add(it.groupValues[1]) }
        return names
    }

    /** True if [caret] is inside a cfoutput/cfloop `query="…"` attribute value (for name completion). */
    fun inQueryAttribute(rawText: CharSequence, caret: Int): Boolean {
        val text = stripComments(rawText.toString())
        var i = caret - 1
        var quote = ' '
        var start = -1
        while (i >= 0) {
            val c = text[i]
            if (c == '"' || c == '\'') { start = i; quote = c; break }
            if (c == '\n' || c == '>' || c == '<') return false
            i--
        }
        if (start < 0) return false
        val pre = text.subSequence(0, start).toString()
        if (!QUERY_ATTR_END.containsMatchIn(pre)) return false
        val lt = pre.lastIndexOf('<')
        if (lt < 0) return false
        val tagPart = pre.substring(lt)
        return !tagPart.contains('>') && OUTPUT_LOOP_TAG.containsMatchIn(tagPart)
    }

    /** Name of the nearest enclosing cfoutput/cfloop that has a query="" attribute, or null. */
    fun enclosingQueryName(rawText: CharSequence, caret: Int): String? {
        val text = stripComments(rawText.toString())
        val openQueryNames = ArrayDeque<String?>()
        for (m in TAG.findAll(text)) {
            if (m.range.first >= caret) break
            val closing = m.groupValues[1] == "/"
            val attrs = m.groupValues[3]
            if (closing) {
                openQueryNames.removeLastOrNull()
            } else if (!attrs.trimEnd().endsWith("/")) { // ignore self-closed tags
                openQueryNames.addLast(ATTR(attrs, "query"))
            }
        }
        return openQueryNames.lastOrNull { it != null }
    }

    /** name (lowercased) -> ordered column names, for every resolvable query in the file. */
    fun columnsByQuery(text: String): Map<String, List<String>> {
        val src = stripComments(text)
        val map = LinkedHashMap<String, List<String>>()
        CFQUERY.findAll(src).forEach { m ->
            val name = ATTR(m.groupValues[1], "name") ?: return@forEach
            selectColumns(m.groupValues[2]).takeIf { it.isNotEmpty() }?.let { map.putIfAbsent(name.lowercase(), it) }
        }
        QUERYNEW.findAll(src).forEach { m ->
            val cols = m.groupValues[3].split(',').map { it.trim() }.filter { PLAIN_IDENT.matches(it) }
            if (cols.isNotEmpty()) map.putIfAbsent(m.groupValues[1].lowercase(), cols)
        }
        return map
    }

    /** A query definition with source offsets, for go-to-declaration and documentation. */
    data class QueryColumn(val name: String, val offset: Int)
    /** A table referenced in a FROM/JOIN clause (name and optional alias), for SELECT * expansion. */
    data class TableRef(val name: String, val alias: String?)
    data class QueryDef(
        val name: String,
        val nameOffset: Int,
        /** Explicitly-listed columns (text parse). */
        val columns: List<QueryColumn>,
        /** SELECT contained a bare "*" — all [tables]' columns should be pulled from the schema. */
        val hasStar: Boolean = false,
        /** "alias.*" / "table.*" entries — that table's columns should be pulled from the schema. */
        val qualifiedStars: List<String> = emptyList(),
        /** Tables in the FROM/JOIN clause (for resolving the stars above against a data source). */
        val tables: List<TableRef> = emptyList(),
    )

    /** All query definitions in the file (cfquery + queryNew), with name/column source offsets. */
    fun queryDefs(text: String): List<QueryDef> {
        val src = stripComments(text)
        val defs = LinkedHashMap<String, QueryDef>()
        CFQUERY.findAll(src).forEach { m ->
            val attrs = m.groupValues[1]
            val name = ATTR(attrs, "name") ?: return@forEach
            val nameOffset = attrValueOffset(attrs, "name")?.let { m.groups[1]!!.range.first + it } ?: m.range.first
            val body = m.groupValues[2]
            val sel = parseSelect(body)
            val cols = columnOffsets(body, m.groups[2]!!.range.first, sel.explicit)
            defs.putIfAbsent(name.lowercase(), QueryDef(name, nameOffset, cols, sel.hasStar, sel.qualifiedStars, sel.tables))
        }
        QUERYNEW.findAll(src).forEach { m ->
            val literal = m.groupValues[3]
            val names = literal.split(',').map { it.trim() }.filter { PLAIN_IDENT.matches(it) }
            val cols = columnOffsets(literal, m.groups[3]!!.range.first, names)
            defs.putIfAbsent(m.groupValues[1].lowercase(), QueryDef(m.groupValues[1], m.groups[1]!!.range.first, cols))
        }
        return defs.values.toList()
    }

    /** Locates each resolved column name within [region] (first word-boundary hit) to get its offset. */
    private fun columnOffsets(region: String, regionStart: Int, names: List<String>): List<QueryColumn> =
        names.map { n ->
            val hit = Regex("""\b${Regex.escape(n)}\b""").find(region)
            QueryColumn(n, regionStart + (hit?.range?.first ?: 0))
        }

    private fun attrValueOffset(attrs: String, name: String): Int? =
        Regex("""\b$name\s*=\s*["']""", RegexOption.IGNORE_CASE).find(attrs)?.range?.let { it.last + 1 }

    private fun selectColumns(queryBody: String): List<String> = parseSelect(queryBody).explicit

    private class SelectInfo(
        val explicit: List<String>,
        val hasStar: Boolean,
        val qualifiedStars: List<String>,
        val tables: List<TableRef>,
    )

    private val EMPTY_SELECT = SelectInfo(emptyList(), false, emptyList(), emptyList())
    private val QUALIFIED_STAR = Regex("""^([A-Za-z_]\w*)\s*\.\s*\*$""")
    private val FROM_END = Regex("""\b(where|group\s+by|order\s+by|having|union|limit|offset|for\s+update)\b""", RegexOption.IGNORE_CASE)
    private val FROM_TABLE = Regex(
        """(?:\bfrom\b|\bjoin\b|,)\s+([A-Za-z_][\w.]*)(?:\s+(?:as\s+)?([A-Za-z_]\w*))?""",
        RegexOption.IGNORE_CASE,
    )
    private val FROM_KEYWORDS = setOf(
        "on", "using", "join", "inner", "left", "right", "full", "outer", "cross",
        "where", "group", "order", "having", "union", "limit", "offset", "and", "or",
    )

    /** Parses a SELECT into explicit columns, star markers, and the FROM/JOIN tables (best-effort). */
    private fun parseSelect(queryBody: String): SelectInfo {
        val selectEnd = SELECT_KW.find(queryBody)?.range?.last?.plus(1) ?: return EMPTY_SELECT
        val fromStart = topLevelFrom(queryBody, selectEnd)
        if (fromStart < 0) return EMPTY_SELECT
        val list = LEADING_QUALIFIER.replaceFirst(queryBody.substring(selectEnd, fromStart).trim(), "").trim()

        var hasStar = false
        val explicit = ArrayList<String>()
        val qualifiedStars = ArrayList<String>()
        for (raw in splitTopLevel(list)) {
            val item = raw.trim()
            when {
                item == "*" -> hasStar = true
                QUALIFIED_STAR.matches(item) -> qualifiedStars.add(QUALIFIED_STAR.find(item)!!.groupValues[1])
                else -> columnName(item)?.let { explicit.add(it) }
            }
        }
        return SelectInfo(explicit, hasStar, qualifiedStars, parseFromTables(queryBody, fromStart))
    }

    private fun parseFromTables(queryBody: String, fromStart: Int): List<TableRef> {
        val endMatch = FROM_END.find(queryBody, fromStart)
        val clause = queryBody.substring(fromStart, endMatch?.range?.first ?: queryBody.length)
        return FROM_TABLE.findAll(clause).map { m ->
            val table = m.groupValues[1].substringAfterLast('.') // strip schema/db qualifier
            val alias = m.groupValues[2].takeIf { it.isNotEmpty() && it.lowercase() !in FROM_KEYWORDS }
            TableRef(table, alias)
        }.toList()
    }

    /** Index of the top-level (paren-depth 0) `from` keyword after [start], or -1. Skips subquery FROMs. */
    private fun topLevelFrom(text: String, start: Int): Int {
        var depth = 0
        var i = start
        while (i < text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> if (depth > 0) depth--
                else ->
                    if (depth == 0 && (text[i] == 'f' || text[i] == 'F') &&
                        text.regionMatches(i, "from", 0, 4, ignoreCase = true) &&
                        (i == 0 || !isWord(text[i - 1])) &&
                        (i + 4 >= text.length || !isWord(text[i + 4]))
                    ) {
                        return i
                    }
            }
            i++
        }
        return -1
    }

    private fun isWord(c: Char) = c.isLetterOrDigit() || c == '_'

    private fun columnName(item: String): String? {
        if (item.isEmpty() || item == "*") return null
        AS_ALIAS.find(item)?.let { return it.groupValues[1] }        // col AS alias
        if (DOTTED_IDENT.matches(item)) return item.substringAfterLast('.') // [table.]col
        return null // expression / function without alias — can't name it
    }

    private fun splitTopLevel(s: String): List<String> {
        val parts = ArrayList<String>()
        var depth = 0
        val sb = StringBuilder()
        for (c in s) when (c) {
            '(' -> { depth++; sb.append(c) }
            ')' -> { if (depth > 0) depth--; sb.append(c) }
            ',' -> if (depth == 0) { parts.add(sb.toString()); sb.clear() } else sb.append(c)
            else -> sb.append(c)
        }
        if (sb.isNotBlank()) parts.add(sb.toString())
        return parts
    }
}
