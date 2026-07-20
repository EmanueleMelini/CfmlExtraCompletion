package it.emanuelemelini.cfmlextracompletion

import it.emanuelemelini.cfmlextracompletion.model.SymbolKind
import it.emanuelemelini.cfmlextracompletion.model.SymbolScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CfmlSymbolExtractorTest {

    private fun extract(text: String) = CfmlSymbolExtractor.extract(text, "test")

    private fun names(text: String, kind: SymbolKind, scope: SymbolScope) =
        extract(text).filter { it.kind == kind && it.scope == scope }.map { it.name }.toSet()

    @Test
    fun `tag-style unscoped vars and functions`() {
        val src = """
            <cfset appTitle = "My App">
            <cfset variables.counter = 0>
            <cfparam name="pageName" default="home">
            <cffunction name="doThing" returntype="string"><cfreturn "x"></cffunction>
        """.trimIndent()

        assertEquals(setOf("appTitle", "counter", "pageName"), names(src, SymbolKind.VARIABLE, SymbolScope.UNSCOPED))
        assertEquals(setOf("doThing"), names(src, SymbolKind.FUNCTION, SymbolScope.UNSCOPED))
    }

    @Test
    fun `application and this scopes are separated`() {
        val src = """
            <cfset application.dsn = "main">
            <cfset this.name = "app1">
            <cfscript>
                application.timeout = 30;
                this.sessionManagement = true;
            </cfscript>
        """.trimIndent()

        assertEquals(setOf("dsn", "timeout"), names(src, SymbolKind.VARIABLE, SymbolScope.APPLICATION))
        assertEquals(setOf("name", "sessionManagement"), names(src, SymbolKind.VARIABLE, SymbolScope.THIS))
    }

    @Test
    fun `script assignments and functions`() {
        val src = """
            <cfscript>
                greeting = "hi";
                variables.total = 5;
                function helper(a, b) { return a + b; }
                public string function typed() { return ""; }
            </cfscript>
        """.trimIndent()

        val unscopedVars = names(src, SymbolKind.VARIABLE, SymbolScope.UNSCOPED)
        assertTrue(unscopedVars.containsAll(setOf("greeting", "total")), "got $unscopedVars")
        assertEquals(setOf("helper", "typed"), names(src, SymbolKind.FUNCTION, SymbolScope.UNSCOPED))
    }

    @Test
    fun `assignment on same line as opening tag is captured`() {
        val src = """<cfscript> application.knownDsn = "main"; greeting = "hi"; </cfscript>"""
        assertEquals(setOf("knownDsn"), names(src, SymbolKind.VARIABLE, SymbolScope.APPLICATION))
        assertTrue(names(src, SymbolKind.VARIABLE, SymbolScope.UNSCOPED).contains("greeting"))
    }

    @Test
    fun `tag attributes are not captured as variables`() {
        val src = """<cffunction name="doThing" returntype="string" access="public">"""
        val vars = names(src, SymbolKind.VARIABLE, SymbolScope.UNSCOPED)
        assertTrue(!vars.contains("name") && !vars.contains("returntype") && !vars.contains("access"),
            "tag attributes leaked as vars: $vars")
    }

    @Test
    fun `out-of-scope scopes are dropped`() {
        val src = """
            <cfset session.userId = 1>
            <cfset request.token = "t">
            <cfset url.page = 2>
        """.trimIndent()

        assertTrue(extract(src).isEmpty(), "session/request/url must not be offered")
    }

    @Test
    fun `comments are ignored`() {
        val src = """
            <!--- <cfset ghostVar = 1> --->
            <cfscript>
                // realVar was here: fakeVar = 9
                realVar = 1;
                /* blockVar = 2; */
            </cfscript>
        """.trimIndent()

        val vars = names(src, SymbolKind.VARIABLE, SymbolScope.UNSCOPED)
        assertEquals(setOf("realVar"), vars, "got $vars")
    }

    @Test
    fun `comparison operators are not treated as assignments`() {
        val src = """
            <cfscript>
                if (x == y) { z = 1; }
                if (a <= b) { }
            </cfscript>
        """.trimIndent()

        val vars = names(src, SymbolKind.VARIABLE, SymbolScope.UNSCOPED)
        assertTrue(vars.contains("z"), "got $vars")
        assertTrue(!vars.contains("y") && !vars.contains("b"), "comparison RHS leaked: $vars")
    }
}
