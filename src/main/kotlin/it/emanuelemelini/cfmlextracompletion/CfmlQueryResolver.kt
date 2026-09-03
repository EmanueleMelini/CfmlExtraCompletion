package it.emanuelemelini.cfmlextracompletion

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project

/**
 * Resolves a query's columns by combining the text-parsed explicit columns with schema-expanded `SELECT *`
 * / `alias.*` (from attached data sources). Everything DB-related is gated on [isDatabaseAvailable] so a
 * missing/disabled Database Tools plugin simply means no expansion (silent fallback), never a crash.
 */
object CfmlQueryResolver {

    /** A resolved column with a source offset for navigation (schema-derived columns point at the query). */
    data class ResolvedColumn(val name: String, val offset: Int)
    data class Resolved(val name: String, val columns: List<ResolvedColumn>)

    /** The query whose loop the caret is in, with its columns (explicit + schema-expanded), or null. */
    fun activeQuery(project: Project, documentText: CharSequence, caret: Int): Resolved? {
        val name = CfmlQueryScopes.enclosingQueryName(documentText, caret) ?: return null
        return queryByName(project, documentText, name)
    }

    /**
     * A query in this file by name, with its resolved columns. Works anywhere in the file — ColdFusion lets
     * you write `queryName.column` outside a loop to read the first row.
     */
    fun queryByName(project: Project, documentText: CharSequence, name: String): Resolved? {
        val def = CfmlQueryScopes.queryDefs(documentText.toString())
            .firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return null
        val columns = resolveColumns(project, def)
        return if (columns.isEmpty()) null else Resolved(def.name, columns)
    }

    /** Columns of the query referenced as `qualifier.<identifier>` at [offset], if any. */
    fun qualifiedQueryAt(project: Project, documentText: CharSequence, offset: Int): Resolved? {
        val qualifier = CfmlQueryScopes.qualifierAt(documentText, offset) ?: return null
        return queryByName(project, documentText, qualifier)
    }

    private val LOG = logger<CfmlQueryResolver>()

    /** Explicit columns first, then schema columns for any `*` / `alias.*`, de-duplicated (case-insensitive). */
    fun resolveColumns(project: Project, def: CfmlQueryScopes.QueryDef): List<ResolvedColumn> {
        val out = LinkedHashMap<String, ResolvedColumn>()
        def.columns.forEach { out.putIfAbsent(it.name.lowercase(), ResolvedColumn(it.name, it.offset)) }

        val needsSchema = def.hasStar || def.qualifiedStars.isNotEmpty()
        // isDebugEnabled guard: this runs on every keystroke, so never build the message when logging is off.
        if (needsSchema && LOG.isDebugEnabled) {
            LOG.debug(
                "Query '${def.name}': star=${def.hasStar} qualifiedStars=${def.qualifiedStars} " +
                    "tables=${def.tables.map { it.name }} dbAvailable=${CfmlDbSchema.available}",
            )
        }
        if (needsSchema && CfmlDbSchema.available) {
            val schemaCols = ArrayList<String>()
            if (def.hasStar) {
                def.tables.forEach { schemaCols += CfmlDbSchema.tableColumns(project, it.name) }
            }
            def.qualifiedStars.forEach { q ->
                def.tables.firstOrNull { it.alias.equals(q, ignoreCase = true) || it.name.equals(q, ignoreCase = true) }
                    ?.let { schemaCols += CfmlDbSchema.tableColumns(project, it.name) }
            }
            // Schema-derived columns have no in-file position; navigation points at the query definition.
            schemaCols.forEach { out.putIfAbsent(it.lowercase(), ResolvedColumn(it, def.nameOffset)) }
            if (LOG.isDebugEnabled) LOG.debug("Query '${def.name}': schema added ${schemaCols.size} column(s)")
        }
        return out.values.toList()
    }
}
