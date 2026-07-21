package it.emanuelemelini.cfmlextracompletion.model

enum class SymbolKind { VARIABLE, FUNCTION }

/** A prefix under which a symbol is offered: "" = unscoped / variables scope. */
enum class SymbolScope(val prefix: String) {
    UNSCOPED(""),
    APPLICATION("application"),
    THIS("this"),
    SESSION("session"),
    REQUEST("request"),
}

/** A single function parameter, for signature display and parameter-info hints. */
data class Param(
    val name: String,
    val type: String? = null,
    val optional: Boolean = false,
) {
    /** Rendered form, e.g. "amount : numeric" or "[mask : string]" for an optional parameter. */
    fun render(): String {
        val core = if (type.isNullOrBlank()) name else "$name : $type"
        return if (optional) "[$core]" else core
    }
}

data class Symbol(
    val name: String,
    val kind: SymbolKind,
    val scope: SymbolScope,
    /** Human-readable origin shown as completion type text, e.g. "Application.cfm". */
    val sourceLabel: String,
    /** Parameters (functions only). */
    val params: List<Param> = emptyList(),
    /** Declared return type (functions only), if any. */
    val returnType: String? = null,
    /** Doc text from a preceding comment or the tag's hint attribute (functions only), if any. */
    val doc: String? = null,
    /** Offset of the declaration's name within its source file text (-1 if unknown). Used for navigation. */
    val declarationOffset: Int = -1,
)
