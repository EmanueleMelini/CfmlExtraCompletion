package it.emanuelemelini.cfmlextracompletion

import com.intellij.coldFusion.UI.inspections.CfmlFileReferenceInspection
import com.intellij.coldFusion.UI.inspections.CfmlReferenceInspection
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import it.emanuelemelini.cfmlextracompletion.settings.CfmlExtraSettings

/**
 * Covers the two opt-in features and, crucially, that they do nothing while disabled (the default).
 */
class CfmlOptionalFeaturesTest : BasePlatformTestCase() {

    private val settings get() = CfmlExtraSettings.getInstance()

    override fun tearDown() {
        try {
            settings.resolveCfcMappings = false
            settings.mergeAncestorApplicationFiles = false
        } finally {
            super.tearDown()
        }
    }

    private fun weakWarns(): List<String> =
        myFixture.doHighlighting()
            .filter { it.severity == HighlightSeverity.WEAK_WARNING }
            .mapNotNull { it.text }

    // --- this.mappings resolution -------------------------------------------------------------------

    private fun setUpMappingProject() {
        myFixture.addFileToProject("common/util/helpers.cfm", "<cfset mappedVar = 1>")
        myFixture.addFileToProject(
            "Application.cfc",
            """
            <cfcomponent>
                <cfset this.mappings["/util"] = expandPath("./common/util")>
            </cfcomponent>
            """.trimIndent(),
        )
        myFixture.enableInspections(CfmlReferenceInspection(), CfmlFileReferenceInspection())
    }

    private fun fileRefWarnings(): List<String> =
        myFixture.doHighlighting()
            .filter { it.severity == HighlightSeverity.WARNING }
            .mapNotNull { it.text }

    fun testMappingResolvedWhenEnabled() {
        setUpMappingProject()
        settings.resolveCfcMappings = true
        myFixture.configureByText(
            "page.cfm",
            """<cfinclude template="/util/helpers.cfm"><cfoutput>#mappedVar#</cfoutput>""",
        )
        assertTrue("mapped include symbol should resolve; got ${weakWarns()}",
            weakWarns().none { it.contains("mappedVar") })
    }

    fun testMappingIgnoredWhenDisabled() {
        setUpMappingProject()
        // resolveCfcMappings stays false (default)
        myFixture.configureByText(
            "page.cfm",
            """<cfinclude template="/util/helpers.cfm"><cfoutput>#mappedVar#</cfoutput>""",
        )
        assertTrue("mapping must not resolve while disabled; got ${weakWarns()}",
            weakWarns().any { it.contains("mappedVar") })
    }

    fun testMappedIncludePathNotFlaggedWhenEnabled() {
        setUpMappingProject()
        settings.resolveCfcMappings = true
        myFixture.configureByText("page.cfm", """<cfinclude template="/util/helpers.cfm">""")

        // The CfmlFileReference inspection (WARNING) flags an unresolved template path segment.
        assertTrue("mapped include path should not be flagged; got ${fileRefWarnings()}",
            fileRefWarnings().isEmpty())
    }

    fun testMappedIncludePathFlaggedWhenDisabled() {
        setUpMappingProject()
        // resolveCfcMappings stays false (default)
        myFixture.configureByText("page.cfm", """<cfinclude template="/util/helpers.cfm">""")

        assertTrue("unresolvable include path should be flagged while disabled; got ${fileRefWarnings()}",
            fileRefWarnings().isNotEmpty())
    }

    // --- ancestor Application merge -----------------------------------------------------------------

    private fun setUpNestedApplicationProject() = myFixture.run {
        addFileToProject("Application.cfm", "<cfset siteWide = 1>")
        addFileToProject("admin/Application.cfm", "<cfset adminOnly = 2>")
        enableInspections(CfmlReferenceInspection())
        addFileToProject("admin/page.cfm", "<cfoutput>#siteWide# #adminOnly#</cfoutput>")
    }

    fun testAncestorMergedWhenEnabled() {
        val page = setUpNestedApplicationProject()
        settings.mergeAncestorApplicationFiles = true
        myFixture.configureFromExistingVirtualFile(page.virtualFile)
        val warns = weakWarns()
        assertTrue("ancestor Application.cfm symbol should resolve; got $warns", warns.none { it.contains("siteWide") })
        assertTrue("nearest Application.cfm symbol should resolve; got $warns", warns.none { it.contains("adminOnly") })
    }

    fun testOnlyNearestWhenDisabled() {
        val page = setUpNestedApplicationProject()
        // mergeAncestorApplicationFiles stays false (default)
        myFixture.configureFromExistingVirtualFile(page.virtualFile)
        val warns = weakWarns()
        assertTrue("ancestor symbol must NOT resolve while disabled; got $warns", warns.any { it.contains("siteWide") })
        assertTrue("nearest symbol should still resolve; got $warns", warns.none { it.contains("adminOnly") })
    }
}
