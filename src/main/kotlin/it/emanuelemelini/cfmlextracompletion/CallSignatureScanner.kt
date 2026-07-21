package it.emanuelemelini.cfmlextracompletion

/**
 * Pure text scanning used by the parameter-info handler: locate the function call surrounding the caret
 * and work out which argument the caret is in. Kept free of PSI/platform types so it can be unit-tested.
 */
object CallSignatureScanner {

    /** A call site around the caret: the function name span and the offset of its opening paren. */
    data class CallSite(val nameStart: Int, val nameEnd: Int, val openParen: Int) {
        fun name(text: CharSequence): String = text.subSequence(nameStart, nameEnd).toString()
    }

    private fun isIdent(c: Char) = c.isLetterOrDigit() || c == '_'

    /**
     * Finds the innermost `name( … <caret> … )` enclosing [offset], scanning left. Returns null if the
     * caret is not inside a call (e.g. inside plain grouping parens, or past a statement boundary).
     */
    fun enclosingCall(text: CharSequence, offset: Int): CallSite? {
        var depth = 0
        var i = (offset - 1).coerceAtMost(text.length - 1)
        while (i >= 0) {
            when (text[i]) {
                ')' -> depth++
                '(' -> {
                    if (depth == 0) {
                        var j = i - 1
                        while (j >= 0 && text[j].isWhitespace()) j--
                        val end = j + 1
                        var s = j
                        while (s >= 0 && isIdent(text[s])) s--
                        val nameStart = s + 1
                        if (nameStart < end) return CallSite(nameStart, end, i)
                        // Grouping paren, not a call — keep scanning outward for an enclosing call.
                    } else {
                        depth--
                    }
                }
                ';', '{', '}' -> if (depth == 0) return null // statement boundary
            }
            i--
        }
        return null
    }

    /** Zero-based index of the argument the caret sits in, counting top-level commas after [openParen]. */
    fun currentParameterIndex(text: CharSequence, openParen: Int, offset: Int): Int {
        var depth = 0
        var commas = 0
        var i = openParen + 1
        val end = offset.coerceAtMost(text.length)
        while (i < end) {
            when (text[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) commas++
            }
            i++
        }
        return commas
    }
}
