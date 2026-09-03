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

    fun testGotoQueryColumn() {
        myFixture.configureByText(
            "page.cfm",
            "<cfquery name=\"users\">SELECT id, userName FROM t</cfquery>\n" +
                "<cfoutput query=\"users\">#user<caret>Name#</cfoutput>",
        )
        val target = targetAtCaret()!!.single()
        val defEnd = myFixture.file.text.indexOf("</cfquery>")
        assertTrue("column go-to should land in the query definition (before </cfquery>), got ${target.textRange}",
            target.textRange.startOffset < defEnd)
    }

    fun testGotoQueryName() {
        myFixture.configureByText(
            "page.cfm",
            "<cfquery name=\"users\">SELECT id FROM t</cfquery>\n" +
                "<cfoutput query=\"us<caret>ers\"></cfoutput>",
        )
        val target = targetAtCaret()!!.single()
        val defEnd = myFixture.file.text.indexOf("</cfquery>")
        assertTrue("query-name go-to should land in the definition, got ${target.textRange}",
            target.textRange.startOffset < defEnd)
    }

    fun testGotoQualifiedQueryColumnLikeRealFile() {
        // Mirrors the real sample: a doc comment above, qualified #users.IDutente# reference.
        myFixture.configureByText(
            "queryDemo.cfm",
            "<!--- a comment mentioning <cfquery> and queryNew --->\n" +
                "<cfquery name=\"users\">\n    SELECT IDutente, userid FROM utenti\n</cfquery>\n" +
                "<cfoutput query=\"users\">\n    #users.IDut<caret>ente#\n</cfoutput>",
        )
        val targets = targetAtCaret()
        assertNotNull("qualified query column should have a go-to target", targets)
        val off = targets!!.single().textRange.startOffset
        val expected = myFixture.file.text.indexOf("IDutente")
        assertEquals("should land on IDutente inside the cfquery", expected, off)
    }

    fun testGotoBareQueryColumnWithCommentAbove() {
        myFixture.configureByText(
            "queryDemo.cfm",
            "<!--- comment with <cfquery> text --->\n" +
                "<cfquery name=\"users\">\n    SELECT IDutente, userid FROM utenti\n</cfquery>\n" +
                "<cfoutput query=\"users\">\n    #IDut<caret>ente#\n</cfoutput>",
        )
        val targets = targetAtCaret()
        assertNotNull("bare query column should have a go-to target", targets)
        val off = targets!!.single().textRange.startOffset
        assertEquals("should land on IDutente inside the cfquery",
            myFixture.file.text.indexOf("IDutente"), off)
    }

    fun testGotoQualifiedQueryColumnOutsideLoop() {
        myFixture.configureByText(
            "page.cfm",
            "<cfquery name=\"users\">SELECT IDutente, userid FROM utenti</cfquery>\n" +
                "<cfset test = users.IDut<caret>ente>",
        )
        val targets = targetAtCaret()
        assertNotNull("q.col outside a loop should navigate", targets)
        assertEquals("should land on IDutente inside the cfquery",
            myFixture.file.text.indexOf("IDutente"), targets!!.single().textRange.startOffset)
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
