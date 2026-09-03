package it.emanuelemelini.cfmlextracompletion

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import java.awt.Dimension
import java.awt.datatransfer.StringSelection
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.JTextArea

/**
 * Tools > "CFML Extra Completion: Diagnose Query Schema".
 *
 * Probes the Database Tools integration used to expand `SELECT *` and shows exactly what was found —
 * data sources, which table-listing strategies work, and the columns for a given table. Far easier than
 * reading idea.log, and it bypasses the lookup cache so results are always current.
 */
class CfmlDbDiagnosticAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return

        // Pre-fill with a table from the current file's queries, when we can find one.
        val suggested = e.getData(CommonDataKeys.EDITOR)?.document?.immutableCharSequence?.let { text ->
            CfmlQueryScopes.queryDefs(text.toString()).flatMap { it.tables }.firstOrNull()?.name
        }
        val table = Messages.showInputDialog(
            project,
            "Table name to look up (leave blank to only list data sources):",
            "Diagnose Query Schema",
            null,
            suggested,
            null,
        )

        val report = CfmlDbSchema.diagnose(project, table)
        CopyPasteManager.getInstance().setContents(StringSelection(report))
        ReportDialog(report).show()
    }

    private class ReportDialog(private val report: String) : DialogWrapper(true) {
        init {
            title = "CFML Extra Completion - Query Schema Diagnostics"
            init()
        }

        override fun createCenterPanel(): JComponent {
            val area = JTextArea(report).apply {
                isEditable = false
                lineWrap = false
            }
            return JScrollPane(area).apply { preferredSize = Dimension(760, 460) }
        }

        override fun createActions() = arrayOf(okAction)
    }
}
