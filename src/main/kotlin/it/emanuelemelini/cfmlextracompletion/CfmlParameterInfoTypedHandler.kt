package it.emanuelemelini.cfmlextracompletion

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/**
 * Requests the parameter-info popup when "(" (or ",") is typed in a CFML file, so the hint auto-appears
 * for the functions this plugin contributes. The bundled CFML support only auto-triggers for functions it
 * can resolve; ours can't be resolved by it, so we trigger the session ourselves. If no handler finds a
 * call at the caret the platform simply shows nothing, so triggering unconditionally is harmless.
 */
class CfmlParameterInfoTypedHandler : TypedHandlerDelegate() {

    override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if ((charTyped == '(' || charTyped == ',') && CfmlProjectSymbols.isCfmlFile(file)) {
            AutoPopupController.getInstance(project).autoPopupParameterInfo(editor, null)
        }
        return Result.CONTINUE
    }
}
