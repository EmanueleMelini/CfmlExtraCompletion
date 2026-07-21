package it.emanuelemelini.cfmlextracompletion

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Verifies Ctrl/Cmd+Click navigation: the handler resolves our contributed symbols to their declaration
 * site in Application.cfm / Application.cfc / included files.
 */
class CfmlGotoDeclarationTest : BasePlatformTestCase() {

    private val handler = CfmlGotoDeclarationHandler()

    private fun targetAtCaret() = run {
        val leaf = myFixture.file.findElementAt(myFixture.caretOffset)!!
        handler.getGotoDeclarationTargets(leaf, myFixture.caretOffset, myFixture.editor)
    }

    fun testGotoVariableDeclaration() {
        val app = myFixture.addFileToProject("Application.cfm", """<cfset sharedVar = 1>""")
        myFixture.configureByText("page.cfm", "<cfoutput>#shared<caret>Var#</cfoutput>")

        val targets = targetAtCaret()
        assertNotNull("expected a navigation target", targets)
        val target = targets!!.single()
        assertEquals("Application.cfm", target.containingFile.name)
        assertEquals(app.text.indexOf("sharedVar"), target.textRange.startOffset)
    }

    fun testGotoFunctionDeclaration() {
        val app = myFixture.addFileToProject(
            "Application.cfm",
            """
            <cffunction name="doThing" returntype="string"><cfreturn ""></cffunction>
            """.trimIndent(),
        )
        myFixture.configureByText("page.cfm", "<cfset x = doTh<caret>ing()>")

        val targets = targetAtCaret()
        assertNotNull("expected a navigation target", targets)
        val target = targets!!.single()
        assertEquals("Application.cfm", target.containingFile.name)
        assertEquals(app.text.indexOf("doThing"), target.textRange.startOffset)
    }

    fun testUnknownIdentifierHasNoTarget() {
        myFixture.addFileToProject("Application.cfm", """<cfset knownVar = 1>""")
        myFixture.configureByText("page.cfm", "<cfoutput>#totallyUnk<caret>nown#</cfoutput>")
        assertNull(targetAtCaret())
    }

    fun testGotoMappedIncludeFile() {
        val target = myFixture.addFileToProject("common/util/helpers.cfm", "<cfset mv = 1>")
        myFixture.addFileToProject(
            "Application.cfc",
            """
            <cfcomponent>
                <cfset this.mappings["/util"] = expandPath("./common/util")>
            </cfcomponent>
            """.trimIndent(),
        )
        settingsOn()
        myFixture.configureByText("page.cfm", """<cfinclude template="/util/hel<caret>pers.cfm">""")

        val targets = targetAtCaret()
        assertNotNull("expected the mapped include file as a target", targets)
        assertEquals("helpers.cfm", targets!!.single().containingFile.name)
    }

    private fun settingsOn() {
        it.emanuelemelini.cfmlextracompletion.settings.CfmlExtraSettings.getInstance().resolveCfcMappings = true
    }

    override fun tearDown() {
        try {
            it.emanuelemelini.cfmlextracompletion.settings.CfmlExtraSettings.getInstance().resolveCfcMappings = false
        } finally {
            super.tearDown()
        }
    }
}
