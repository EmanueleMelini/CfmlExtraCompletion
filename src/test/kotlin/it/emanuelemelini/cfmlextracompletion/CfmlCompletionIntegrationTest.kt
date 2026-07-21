package it.emanuelemelini.cfmlextracompletion

import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import it.emanuelemelini.cfmlextracompletion.model.Symbol

/**
 * Exercises the contributor the way the editor does: parse a CFML file with the real CFML plugin loaded,
 * invoke basic completion, and assert our project-wide symbols show up in the lookup.
 */
class CfmlCompletionIntegrationTest : BasePlatformTestCase() {

    fun testApplicationCfmSymbolsComplete() {
        myFixture.addFileToProject(
            "Application.cfm",
            """
            <cfset appTitle = "My App">
            <cfset appVersion = "1.0">
            <cffunction name="appHelper"><cfreturn 1></cffunction>
            """.trimIndent(),
        )
        myFixture.configureByText("page.cfm", "<cfset x = app<caret>>")
        myFixture.completeBasic()

        val strings = myFixture.lookupElementStrings ?: emptyList()
        assertTrue("expected Application.cfm symbols, got $strings",
            strings.containsAll(listOf("appTitle", "appVersion", "appHelper")))
    }

    fun testCfincludeSymbolsComplete() {
        myFixture.addFileToProject("header.cfm", """<cfset headerVar = 1><cfset headerFlag = 2>""")
        myFixture.configureByText(
            "page.cfm",
            """<cfinclude template="header.cfm"><cfset x = head<caret>>""",
        )
        myFixture.completeBasic()

        val strings = myFixture.lookupElementStrings ?: emptyList()
        assertTrue("expected cfinclude symbols, got $strings",
            strings.containsAll(listOf("headerVar", "headerFlag")))
    }

    fun testApplicationScopeCompletesAfterDot() {
        myFixture.addFileToProject(
            "Application.cfc",
            """
            <cfcomponent>
                <cffunction name="onApplicationStart">
                    <cfscript>
                        application.datasource = "main";
                        application.dateFormat = "yyyy-mm-dd";
                    </cfscript>
                </cffunction>
            </cfcomponent>
            """.trimIndent(),
        )
        myFixture.configureByText("page.cfm", "<cfset y = application.da<caret>>")
        myFixture.completeBasic()

        val strings = myFixture.lookupElementStrings ?: emptyList()
        assertTrue("expected qualified application-scope symbols, got $strings",
            strings.containsAll(listOf("application.datasource", "application.dateFormat")))
    }

    fun testApplicationScopeSetInApplicationCfmCompletes() {
        // Older style: application.* assigned directly in Application.cfm (not via Application.cfc).
        myFixture.addFileToProject(
            "Application.cfm",
            """
            <cfset appTitle = "My App">
            <cfset application.test = 1>
            <cfset application.dsn = "main">
            """.trimIndent(),
        )
        myFixture.configureByText("page.cfm", "<cfset y = application.<caret>>")
        myFixture.completeBasic()

        val strings = myFixture.lookupElementStrings ?: emptyList()
        assertTrue("expected application.* from Application.cfm, got $strings",
            strings.containsAll(listOf("application.test", "application.dsn")))
        assertTrue("unscoped page symbol must not leak into application scope, got $strings",
            !strings.contains("appTitle") && !strings.contains("application.appTitle"))
    }

    fun testApplicationScopeAppearsWhileTypingScopeNameBeforeDot() {
        myFixture.addFileToProject(
            "Application.cfc",
            """
            <cfcomponent>
                <cffunction name="onApplicationStart">
                    <cfscript> application.appWideThing = "x"; </cfscript>
                </cffunction>
            </cfcomponent>
            """.trimIndent(),
        )
        // No dot typed yet — like the built-in, our application.* should already show.
        myFixture.configureByText("page.cfm", "<cfset y = app<caret>>")
        myFixture.completeBasic()

        val strings = myFixture.lookupElementStrings ?: emptyList()
        assertTrue("qualified application symbol should surface before the dot, got $strings",
            strings.contains("application.appWideThing"))
    }

    fun testCurrentFileSymbolsAreNotReContributed() {
        // Editing Application.cfm itself: its own symbols must NOT be added by us (the built-in already
        // completes same-file symbols), otherwise every entry would be duplicated.
        myFixture.configureByText(
            "Application.cfm",
            """
            <cfset selfVar = 1>
            <cffunction name="selfFn"><cfreturn 1></cffunction>
            """.trimIndent(),
        )
        val agg = CfmlProjectSymbols.of(myFixture.file)
        assertTrue(
            "current-file symbols must not be re-contributed: ${agg.unscoped.map { it.name }}",
            agg.unscoped.none { it.name == "selfVar" || it.name == "selfFn" },
        )
    }

    fun testStandardThisSettingsAreNotDuplicated() {
        myFixture.addFileToProject(
            "Application.cfc",
            """
            <cfcomponent>
                <cfset this.name = "app">
                <cfset this.sessionManagement = true>
                <cfset this.test = "custom">
            </cfcomponent>
            """.trimIndent(),
        )
        myFixture.configureByText("page.cfm", "<cfset x = this.<caret>>")
        myFixture.completeBasic()

        // Look only at OUR contributions (their lookup object is a Symbol) so the built-in's own entries
        // don't muddy the assertion.
        val ours = (myFixture.lookupElements ?: emptyArray())
            .filter { it.`object` is Symbol }
            .map { it.lookupString }
        assertTrue("custom this.test should be offered by us, got $ours", ours.contains("this.test"))
        assertTrue("standard this.* settings must NOT be re-contributed by us, got $ours",
            ours.none { it == "this.name" || it == "this.sessionManagement" })
    }

    fun testSessionScopeFromApplicationCfmCompletes() {
        myFixture.addFileToProject(
            "Application.cfm",
            """
            <cfset session.userId = 0>
            <cfset session.cart = []>
            """.trimIndent(),
        )
        myFixture.configureByText("page.cfm", "<cfset x = session.<caret>>")
        myFixture.completeBasic()

        val strings = myFixture.lookupElementStrings ?: emptyList()
        assertTrue("expected session.* from Application.cfm, got $strings",
            strings.containsAll(listOf("session.userId", "session.cart")))
    }

    fun testFunctionCompletionShowsSignature() {
        myFixture.addFileToProject(
            "Application.cfm",
            """
            <cffunction name="formatMoney" returntype="string">
                <cfargument name="amount" type="numeric" required="true">
                <cfargument name="symbol" type="string">
                <cfreturn "">
            </cffunction>
            <cffunction name="formatMonth" returntype="string">
                <cfreturn "">
            </cffunction>
            """.trimIndent(),
        )
        // Two "formatMon*" matches keep the popup open (a lone match would auto-insert).
        myFixture.configureByText("page.cfm", "<cfset x = formatMon<caret>>")
        myFixture.completeBasic()

        val item = myFixture.lookupElements!!.first { it.lookupString == "formatMoney" }
        val p = LookupElementPresentation().also { item.renderElement(it) }
        assertEquals("expected parameter signature as tail text",
            "(amount : numeric, [symbol : string])", p.tailText)
    }
}
