package it.emanuelemelini.cfmlextracompletion

import com.intellij.coldFusion.UI.inspections.CfmlReferenceInspection
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Verifies that the CFML "unresolved reference" inspection (WEAK_WARNING) is suppressed for symbols we
 * recognize project-wide, but still fires for genuinely unknown identifiers.
 */
class CfmlSuppressionIntegrationTest : BasePlatformTestCase() {

    private fun weakWarningTexts(): List<String> =
        myFixture.doHighlighting()
            .filter { it.severity == HighlightSeverity.WEAK_WARNING }
            .mapNotNull { it.text }

    fun testUnknownVariableIsStillFlagged() {
        myFixture.enableInspections(CfmlReferenceInspection())
        myFixture.configureByText("page.cfm", "<cfoutput>#totallyUnknownVar#</cfoutput>")

        assertTrue(
            "an unknown variable should still be flagged; got ${weakWarningTexts()}",
            weakWarningTexts().any { it.contains("totallyUnknownVar") },
        )
    }

    fun testApplicationCfmSymbolIsNotFlagged() {
        myFixture.addFileToProject("Application.cfm", "<cfset knownAppVar = 1>")
        myFixture.enableInspections(CfmlReferenceInspection())
        myFixture.configureByText("page.cfm", "<cfoutput>#knownAppVar#</cfoutput>")

        assertTrue(
            "a known Application.cfm symbol must not be flagged; got ${weakWarningTexts()}",
            weakWarningTexts().none { it.contains("knownAppVar") },
        )
    }

    fun testIncludedSymbolIsNotFlagged() {
        myFixture.addFileToProject("inc.cfm", "<cfset includedVar = 1>")
        myFixture.enableInspections(CfmlReferenceInspection())
        myFixture.configureByText(
            "page.cfm",
            """<cfinclude template="inc.cfm"><cfoutput>#includedVar#</cfoutput>""",
        )

        assertTrue(
            "a symbol from an included file must not be flagged; got ${weakWarningTexts()}",
            weakWarningTexts().none { it.contains("includedVar") },
        )
    }

    fun testQueryColumnInsideCfoutputIsNotFlagged() {
        myFixture.enableInspections(CfmlReferenceInspection())
        myFixture.configureByText(
            "page.cfm",
            """
            <cfset report = queryNew("year,total")>
            <cfoutput query="report">#year# #total#</cfoutput>
            """.trimIndent(),
        )
        assertTrue("query columns inside cfoutput query must not be flagged; got ${weakWarningTexts()}",
            weakWarningTexts().none { it == "year" || it == "total" })
    }

    fun testApplicationScopeSymbolIsNotFlagged() {
        myFixture.addFileToProject(
            "Application.cfc",
            """
            <cfcomponent>
                <cffunction name="onApplicationStart">
                    <cfscript> application.knownDsn = "main"; </cfscript>
                </cffunction>
            </cfcomponent>
            """.trimIndent(),
        )
        myFixture.enableInspections(CfmlReferenceInspection())
        myFixture.configureByText("page.cfm", "<cfoutput>#application.knownDsn#</cfoutput>")

        assertTrue(
            "an application-scope symbol must not be flagged; got ${weakWarningTexts()}",
            weakWarningTexts().none { it.contains("knownDsn") },
        )
    }
}
