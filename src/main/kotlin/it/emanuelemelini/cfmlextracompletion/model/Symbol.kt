package it.emanuelemelini.cfmlextracompletion.model

enum class SymbolKind { VARIABLE, FUNCTION }

/** A prefix under which a symbol is offered: "" = unscoped / variables scope. */
enum class SymbolScope(val prefix: String) {
    UNSCOPED(""),
    APPLICATION("application"),
    THIS("this"),
}

data class Symbol(
    val name: String,
    val kind: SymbolKind,
    val scope: SymbolScope,
    /** Human-readable origin shown as completion type text, e.g. "Application.cfm". */
    val sourceLabel: String,
)
