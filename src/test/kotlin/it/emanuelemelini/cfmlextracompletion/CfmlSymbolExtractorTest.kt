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

    private fun function(text: String, name: String) =
        extract(text).first { it.kind == SymbolKind.FUNCTION && it.name == name }

    private fun function0rVar(text: String, name: String) =
        extract(text).first { it.name == name }

    @Test
    fun `tag function parameters and return type are captured`() {
        val src = """
            <cffunction name="formatMoney" returntype="string">
                <cfargument name="amount" type="numeric" required="true">
                <cfargument name="symbol" type="string">
                <cfreturn "">
            </cffunction>
        """.trimIndent()

        val fn = function(src, "formatMoney")
        assertEquals("string", fn.returnType)
        assertEquals(listOf("amount", "symbol"), fn.params.map { it.name })
        assertEquals(listOf("numeric", "string"), fn.params.map { it.type })
        assertEquals(listOf(false, true), fn.params.map { it.optional }) // required=true -> not optional
        assertEquals("amount : numeric", fn.params[0].render())
        assertEquals("[symbol : string]", fn.params[1].render())
    }

    @Test
    fun `script function parameters and return type are captured`() {
        val src = """
            <cfscript>
                public numeric function add(required numeric a, numeric b = 0) {
                    return a + b;
                }
            </cfscript>
        """.trimIndent()

        val fn = function(src, "add")
        assertEquals("numeric", fn.returnType)
        assertEquals(listOf("a", "b"), fn.params.map { it.name })
        assertEquals(listOf("numeric", "numeric"), fn.params.map { it.type })
        assertEquals(listOf(false, true), fn.params.map { it.optional })
    }

    @Test
    fun `function with no parameters yields empty params`() {
        val src = """<cffunction name="ping" returntype="boolean"><cfreturn true></cffunction>"""
        assertTrue(function(src, "ping").params.isEmpty())
    }

    @Test
    fun `tag function doc comes from preceding CFML comment`() {
        val src = """
            <!--- Formats a number as money. --->
            <cffunction name="formatMoney" returntype="string">
                <cfargument name="amount" type="numeric" required="true">
            </cffunction>
        """.trimIndent()
        assertEquals("Formats a number as money.", function(src, "formatMoney").doc)
    }

    @Test
    fun `tag function doc falls back to hint attribute`() {
        val src = """<cffunction name="ping" hint="Health check" returntype="boolean"><cfreturn true></cffunction>"""
        assertEquals("Health check", function(src, "ping").doc)
    }

    @Test
    fun `script function doc comes from preceding javadoc comment`() {
        val src = """
            <cfscript>
            /** Adds two numbers. */
            numeric function add(numeric a, numeric b) { return a + b; }
            </cfscript>
        """.trimIndent()
        assertEquals("Adds two numbers.", function(src, "add").doc)
    }

    @Test
    fun `function without doc has null doc`() {
        val src = """<cffunction name="bare"><cfreturn 1></cffunction>"""
        assertEquals(null, function(src, "bare").doc)
    }

    @Test
    fun `doc is not taken from a non-adjacent earlier comment`() {
        val src = """
            <!--- File header, not this function's doc. --->
            <cfset x = 1>
            <cffunction name="noDoc"><cfreturn 1></cffunction>
        """.trimIndent()
        assertEquals(null, function(src, "noDoc").doc)
    }

    @Test
    fun `doc does not swallow content across earlier comments`() {
        val src = """
            <!--- File header. --->
            <cfset x = 1>
            <!--- Real doc. --->
            <cffunction name="f"><cfreturn 1></cffunction>
        """.trimIndent()
        assertEquals("Real doc.", function(src, "f").doc)
    }

    @Test
    fun `declaration offset points at the name in the original text`() {
        val src = """<cfset appTitle = "x">"""
        assertEquals(src.indexOf("appTitle"), function0rVar(src, "appTitle").declarationOffset)
    }

    @Test
    fun `declaration offset survives a preceding comment (length-preserving strip)`() {
        val src = "<!--- header comment --->\n<cfset foo = 1>"
        assertEquals(src.indexOf("foo"), function0rVar(src, "foo").declarationOffset)
    }

    @Test
    fun `required attribute accepts yes and 1 as true`() {
        val src = """
            <cffunction name="f">
                <cfargument name="a" type="numeric" required="yes">
                <cfargument name="b" type="string" required="1">
                <cfargument name="c" type="string">
            </cffunction>
        """.trimIndent()
        assertEquals(listOf(false, false, true), function(src, "f").params.map { it.optional })
    }

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
    fun `session and request scopes are captured, other scopes dropped`() {
        val src = """
            <cfset session.userId = 1>
            <cfset request.token = "t">
            <cfset url.page = 2>
            <cfset form.q = "x">
        """.trimIndent()

        assertEquals(setOf("userId"), names(src, SymbolKind.VARIABLE, SymbolScope.SESSION))
        assertEquals(setOf("token"), names(src, SymbolKind.VARIABLE, SymbolScope.REQUEST))
        // url / form / cgi etc. remain out of scope.
        assertTrue(extract(src).none { it.name == "page" || it.name == "q" }, "url/form must not be offered")
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
