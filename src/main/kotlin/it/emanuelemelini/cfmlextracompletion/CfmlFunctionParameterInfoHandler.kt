package it.emanuelemelini.cfmlextracompletion

import com.intellij.lang.parameterInfo.CreateParameterInfoContext
import com.intellij.lang.parameterInfo.ParameterInfoHandler
import com.intellij.lang.parameterInfo.ParameterInfoUIContext
import com.intellij.lang.parameterInfo.UpdateParameterInfoContext
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import it.emanuelemelini.cfmlextracompletion.model.Symbol
import it.emanuelemelini.cfmlextracompletion.model.SymbolKind

/**
 * Shows a parameter-info hint (Ctrl+P / after typing "(") for functions this plugin contributes —
 * Application.cfm/.cfc and cfinclude'd functions — which the bundled CFML plugin can't resolve and so
 * shows no hint for. Coexists with the built-in handler: for functions we don't know we return null and
 * let CFML handle them.
 */
class CfmlFunctionParameterInfoHandler : ParameterInfoHandler<PsiElement, Symbol> {

    override fun findElementForParameterInfo(context: CreateParameterInfoContext): PsiElement? {
        val file = context.file
        if (!CfmlProjectSymbols.isCfmlFile(file)) return null
        val text = file.text
        val call = CallSignatureScanner.enclosingCall(text, context.offset) ?: return null
        val fn = findFunction(file, call.name(text)) ?: return null
        context.itemsToShow = arrayOf(fn)
        return file.findElementAt(call.openParen) ?: file
    }

    override fun showParameterInfo(element: PsiElement, context: CreateParameterInfoContext) {
        context.showHint(element, element.textRange.startOffset, this)
    }

    override fun findElementForUpdatingParameterInfo(context: UpdateParameterInfoContext): PsiElement? {
        val file = context.file
        val call = CallSignatureScanner.enclosingCall(file.text, context.offset) ?: return null
        return file.findElementAt(call.openParen) ?: file
    }

    override fun updateParameterInfo(element: PsiElement, context: UpdateParameterInfoContext) {
        val file = context.file
        val call = CallSignatureScanner.enclosingCall(file.text, context.offset)
        if (call == null) {
            context.setCurrentParameter(-1)
            return
        }
        context.setCurrentParameter(CallSignatureScanner.currentParameterIndex(file.text, call.openParen, context.offset))
    }

    override fun updateUI(p: Symbol, context: ParameterInfoUIContext) {
        if (p.params.isEmpty()) {
            context.setupUIComponentPresentation("<no parameters>", 0, 0, false, false, false, context.defaultParameterColor)
            return
        }
        val idx = context.currentParameterIndex
        val sb = StringBuilder()
        var highlightStart = -1
        var highlightEnd = -1
        p.params.forEachIndexed { i, param ->
            if (i > 0) sb.append(", ")
            if (i == idx) highlightStart = sb.length
            sb.append(param.render())
            if (i == idx) highlightEnd = sb.length
        }
        context.setupUIComponentPresentation(
            sb.toString(),
            highlightStart.coerceAtLeast(0),
            highlightEnd.coerceAtLeast(0),
            false,
            false,
            false,
            context.defaultParameterColor,
        )
    }

    private fun findFunction(file: PsiFile, name: String): Symbol? {
        val agg = CfmlProjectSymbols.of(file)
        return (agg.unscoped.asSequence() + agg.application.asSequence() + agg.thisScope.asSequence())
            .firstOrNull { it.kind == SymbolKind.FUNCTION && it.name.equals(name, ignoreCase = true) }
    }
}
