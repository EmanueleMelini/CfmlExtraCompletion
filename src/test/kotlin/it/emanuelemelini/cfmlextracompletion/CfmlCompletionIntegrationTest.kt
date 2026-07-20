package it.emanuelemelini.cfmlextracompletion

import com.intellij.testFramework.fixtures.BasePlatformTestCase

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
        assertTrue("expected application-scope symbols, got $strings",
            strings.containsAll(listOf("datasource", "dateFormat")))
    }
}
