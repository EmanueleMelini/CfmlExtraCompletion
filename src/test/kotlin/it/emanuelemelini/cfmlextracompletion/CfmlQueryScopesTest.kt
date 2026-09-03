package it.emanuelemelini.cfmlextracompletion

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CfmlQueryScopesTest {

    private fun columns(text: String, name: String) = CfmlQueryScopes.columnsByQuery(text)[name]

    @Test
    fun `cfquery select columns with alias and table prefix`() {
        val src = """
            <cfquery name="q">
                SELECT id, u.name, price AS cost, count(*) AS total, now()
                FROM users u
            </cfquery>
        """.trimIndent()
        // count(*) has an alias -> total; now() has no alias -> dropped.
        assertEquals(listOf("id", "name", "cost", "total"), columns(src, "q"))
    }

    @Test
    fun `select star yields no explicit columns`() {
        assertNull(columns("""<cfquery name="q">SELECT * FROM t</cfquery>""", "q"))
    }

    @Test
    fun `select star captures tables for schema expansion`() {
        val def = CfmlQueryScopes.queryDefs(
            """<cfquery name="q">SELECT * FROM utenti u JOIN ordini o ON u.id = o.uid WHERE o.stato = 1</cfquery>""",
        ).first()
        assertTrue(def.hasStar)
        assertEquals(listOf("utenti", "ordini"), def.tables.map { it.name })
        assertEquals(listOf("u", "o"), def.tables.map { it.alias })
    }

    @Test
    fun `qualified star and explicit column are separated`() {
        val def = CfmlQueryScopes.queryDefs(
            """<cfquery name="q">SELECT u.*, o.total FROM utenti u JOIN ordini o ON u.id = o.uid</cfquery>""",
        ).first()
        assertEquals(listOf("u"), def.qualifiedStars)
        assertEquals(listOf("total"), def.columns.map { it.name })
        assertEquals(listOf("utenti", "ordini"), def.tables.map { it.name })
        assertTrue(!def.hasStar)
    }

    @Test
    fun `comma-separated from tables are captured`() {
        val def = CfmlQueryScopes.queryDefs("""<cfquery name="q">SELECT * FROM a, b WHERE a.id = b.id</cfquery>""").first()
        assertEquals(listOf("a", "b"), def.tables.map { it.name })
    }

    @Test
    fun `select distinct is handled`() {
        assertEquals(listOf("a", "b"), columns("""<cfquery name="q">SELECT DISTINCT a, b FROM t</cfquery>""", "q"))
    }

    @Test
    fun `a comment mentioning cfquery does not poison the real query`() {
        val src = """
            <!--- Built by a <cfquery> with a SELECT list, e.g. <cfquery name="..."> --->
            <cfquery name="users">SELECT id, userName FROM t</cfquery>
        """.trimIndent()
        assertEquals(listOf("id", "userName"), columns(src, "users"))
        assertEquals(setOf("users"), CfmlQueryScopes.queryNames(src)) // no bogus "..." from the comment
    }

    @Test
    fun `queryNew literal columns`() {
        assertEquals(listOf("colA", "colB"), columns("""<cfset q = queryNew("colA,colB")>""", "q"))
    }

    @Test
    fun `subquery commas are not split as columns`() {
        val src = """<cfquery name="q">SELECT id, (SELECT max(x) FROM y) AS mx FROM t</cfquery>"""
        assertEquals(listOf("id", "mx"), columns(src, "q"))
    }

    // --- enclosing block detection ---------------------------------------------------------------

    private fun activeName(marked: String): String? {
        val caret = marked.indexOf('|')
        return CfmlQueryScopes.enclosingQueryName(marked.removeRange(caret, caret + 1), caret)
    }

    @Test
    fun `detects enclosing cfoutput and cfloop query`() {
        assertEquals("q", activeName("""<cfoutput query="q">#|#</cfoutput>"""))
        assertEquals("q", activeName("""<cfloop query="q">#|#</cfloop>"""))
    }

    @Test
    fun `no query scope outside a query block`() {
        assertNull(activeName("""<cfoutput>#|#</cfoutput>"""))
        assertNull(activeName("""plain #|# text"""))
    }

    @Test
    fun `innermost query wins when nested`() {
        assertEquals("inner", activeName("""<cfoutput query="outer"><cfoutput query="inner">#|#</cfoutput></cfoutput>"""))
    }
}
