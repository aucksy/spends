package com.spends.app.core.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class CalculatorEngineTest {

    private fun eval(s: String): String? = CalculatorEngine.evaluate(s)?.toPlainString()

    @Test fun plainNumber() {
        assertEquals("1200.00", eval("1200"))
        assertEquals("1200.50", eval("1200.5"))
    }

    @Test fun addition() = assertEquals("1550.00", eval("1200+350"))

    @Test fun subtraction() = assertEquals("850.00", eval("1200-350"))

    @Test fun precedenceMultiplyBeforeAdd() = assertEquals("1800.00", eval("1200+300*2"))

    @Test fun divisionRoundsToPaise() = assertEquals("33.33", eval("100/3"))

    @Test fun chainedLeftToRight() = assertEquals("100.00", eval("10*5+50"))

    @Test fun trailingOperatorIsIgnored() = assertEquals("1200.00", eval("1200+"))

    @Test fun trailingDotIsIgnored() = assertEquals("5.00", eval("5."))

    @Test fun divisionByZeroIsNull() = assertNull(eval("5/0"))

    @Test fun emptyIsNull() {
        assertNull(eval(""))
        assertNull(eval("+"))
        assertNull(eval("."))
    }

    @Test fun leadingMinus() = assertEquals("-50.00", eval("-50"))

    // ---- append() keeps the expression always-valid ----

    @Test fun appendDigits() {
        var e = ""
        e = CalculatorEngine.append(e, "1")
        e = CalculatorEngine.append(e, "2")
        e = CalculatorEngine.append(e, "0")
        e = CalculatorEngine.append(e, "0")
        assertEquals("1200", e)
    }

    @Test fun operatorCannotLeadExceptMinus() {
        assertEquals("", CalculatorEngine.append("", "+"))
        assertEquals("", CalculatorEngine.append("", "*"))
        assertEquals("-", CalculatorEngine.append("", "-"))
    }

    @Test fun doubledOperatorReplaces() {
        var e = "1200"
        e = CalculatorEngine.append(e, "+")
        e = CalculatorEngine.append(e, "-") // replaces the +
        assertEquals("1200-", e)
    }

    @Test fun onlyOneDotPerSegment() {
        var e = "5"
        e = CalculatorEngine.append(e, ".")
        e = CalculatorEngine.append(e, ".") // ignored
        e = CalculatorEngine.append(e, "5")
        assertEquals("5.5", e)
    }

    @Test fun dotAfterOperatorStartsZero() {
        var e = "5+"
        e = CalculatorEngine.append(e, ".")
        assertEquals("5+0.", e)
    }

    @Test fun backspaceAndClear() {
        assertEquals("120", CalculatorEngine.append("1200", "<"))
        assertEquals("", CalculatorEngine.append("1200+30", "C"))
    }

    @Test fun equalsCollapsesToResult() {
        assertEquals("1550", CalculatorEngine.append("1200+350", "="))
    }

    @Test fun toPositiveMinorConverts() {
        assertEquals(155000L, CalculatorEngine.toPositiveMinor(BigDecimal("1550.00")))
        assertEquals(3333L, CalculatorEngine.toPositiveMinor(BigDecimal("33.33")))
        assertNull(CalculatorEngine.toPositiveMinor(BigDecimal("0")))
        assertNull(CalculatorEngine.toPositiveMinor(BigDecimal("-5")))
        assertNull(CalculatorEngine.toPositiveMinor(null))
    }

    @Test fun pendingOperationDetection() {
        assertTrue(CalculatorEngine.hasPendingOperation("1200+350"))
        assertTrue(!CalculatorEngine.hasPendingOperation("1200"))
        assertTrue(!CalculatorEngine.hasPendingOperation("-50"))
    }
}
