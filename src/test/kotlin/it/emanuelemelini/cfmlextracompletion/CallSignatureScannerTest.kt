package it.emanuelemelini.cfmlextracompletion

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CallSignatureScannerTest {

    /** Places the caret at the '|' marker and returns (text-without-marker, caretOffset). */
    private fun at(marked: String): Pair<String, Int> {
        val i = marked.indexOf('|')
        return marked.removeRange(i, i + 1) to i
    }

    private fun callName(marked: String): String? {
        val (text, caret) = at(marked)
        val call = CallSignatureScanner.enclosingCall(text, caret) ?: return null
        return call.name(text)
    }

    private fun paramIndex(marked: String): Int {
        val (text, caret) = at(marked)
        val call = CallSignatureScanner.enclosingCall(text, caret)!!
        return CallSignatureScanner.currentParameterIndex(text, call.openParen, caret)
    }

    @Test
    fun `finds enclosing call name`() {
        assertEquals("formatMoney", callName("#formatMoney(|)#"))
        assertEquals("formatMoney", callName("<cfset x = formatMoney(12|3)>"))
    }

    @Test
    fun `current parameter index counts top-level commas`() {
        assertEquals(0, paramIndex("foo(|)"))
        assertEquals(0, paramIndex("foo(1|)"))
        assertEquals(1, paramIndex("foo(1, |)"))
        assertEquals(2, paramIndex("foo(1, 2, 3|)"))
    }

    @Test
    fun `commas inside nested calls do not advance the index`() {
        assertEquals(1, paramIndex("outer(a, inner(b, c)|)")) // caret in outer's 2nd arg
        assertEquals(1, paramIndex("outer(inner(b, |c))")) // caret in inner's 2nd arg
    }

    @Test
    fun `not a call returns null`() {
        assertNull(callName("x = (1 + |2)")) // grouping parens, no function name
        assertNull(callName("plain text |here"))
    }
}
