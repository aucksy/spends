package com.spends.app.core.calc

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A tiny, exact arithmetic engine for the amount keypad (Monito-style "type 1200+350" math).
 *
 * Two pure entry points, both unit-tested so the release gate covers them:
 *  - [append] mutates an expression string in response to a key press, keeping it always-valid
 *    (no leading operator except '-', no doubled operators, one '.' per number segment);
 *  - [evaluate] computes the result with standard precedence (× ÷ before + −) using [BigDecimal]
 *    so money never touches a float. Returns null for an empty/invalid expression or ÷ by zero.
 *
 * Operators are stored as ASCII (+ - * /) internally; the UI renders * → ×, / → ÷, - → − for display.
 */
object CalculatorEngine {

    private const val OPERATORS = "+-*/"

    /** Apply a key ("0".."9", ".", "+", "-", "*", "/", "=", "C", "<") to [expr], returning the new expression. */
    fun append(expr: String, key: String): String = when (key) {
        "C" -> ""
        "<" -> expr.dropLast(1)
        "=" -> evaluate(expr)?.let { format(it) } ?: expr
        "." -> appendDot(expr)
        in listOf("+", "-", "*", "/") -> appendOperator(expr, key[0])
        else -> if (key.length == 1 && key[0].isDigit()) expr + key else expr
    }

    private fun appendDot(expr: String): String {
        // Only one dot per number segment; start "0." if we're at the very start or right after an operator.
        val seg = currentSegment(expr)
        if (seg.contains('.')) return expr
        if (expr.isEmpty() || expr.last() in OPERATORS) return expr + "0."
        return "$expr."
    }

    private fun appendOperator(expr: String, op: Char): String {
        if (expr.isEmpty()) return if (op == '-') "-" else expr // only a leading minus is allowed
        // Drop a dangling '.' ("5." + "+" -> "5+") then replace any trailing operator with the new one.
        var base = expr
        if (base.last() == '.') base = base.dropLast(1)
        if (base.isEmpty()) return if (op == '-') "-" else ""
        if (base.last() in OPERATORS) base = base.dropLast(1)
        return base + op
    }

    /** The trailing number being typed (everything after the last operator). */
    private fun currentSegment(expr: String): String {
        val idx = expr.indexOfLast { it in OPERATORS }
        return if (idx < 0) expr else expr.substring(idx + 1)
    }

    /** Evaluate [raw]; null if empty/invalid or division by zero. Result is scaled to 2 decimals. */
    fun evaluate(raw: String): BigDecimal? {
        var s = raw.trim()
        // Trim any dangling operators / dot so partial input like "1200+" still evaluates to 1200.
        while (s.isNotEmpty() && (s.last() in OPERATORS || s.last() == '.')) s = s.dropLast(1)
        if (s.isEmpty()) return null
        if (s.first() == '-') s = "0$s" // leading unary minus

        val numbers = ArrayList<BigDecimal>()
        val ops = ArrayList<Char>()
        val seg = StringBuilder()
        for (ch in s) {
            when {
                ch.isDigit() || ch == '.' -> seg.append(ch)
                ch in OPERATORS -> {
                    val n = seg.toString().toBigDecimalOrNull() ?: return null
                    numbers.add(n); seg.clear(); ops.add(ch)
                }
                else -> return null
            }
        }
        numbers.add(seg.toString().toBigDecimalOrNull() ?: return null)

        // Pass 1: × and ÷ (left-to-right).
        var i = 0
        while (i < ops.size) {
            when (ops[i]) {
                '*' -> { numbers[i] = numbers[i].multiply(numbers[i + 1]); numbers.removeAt(i + 1); ops.removeAt(i) }
                '/' -> {
                    if (numbers[i + 1].signum() == 0) return null
                    numbers[i] = numbers[i].divide(numbers[i + 1], 2, RoundingMode.HALF_UP)
                    numbers.removeAt(i + 1); ops.removeAt(i)
                }
                else -> i++
            }
        }
        // Pass 2: + and − (left-to-right).
        var result = numbers[0]
        for (j in ops.indices) {
            result = if (ops[j] == '+') result.add(numbers[j + 1]) else result.subtract(numbers[j + 1])
        }
        return result.setScale(2, RoundingMode.HALF_UP)
    }

    /** Plain string of a result for feeding back into the expression after "=" (e.g. 1500.00 -> "1500.5" trimmed). */
    fun format(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()

    /** Convert an evaluated rupee result to integer paise; null if not a positive amount. */
    fun toPositiveMinor(value: BigDecimal?): Long? =
        value?.takeIf { it.signum() > 0 }?.movePointRight(2)?.setScale(0, RoundingMode.HALF_UP)?.longValueExact()

    /** True when [expr] currently has a trailing operator (used to show the live "= result" hint). */
    fun hasPendingOperation(expr: String): Boolean =
        expr.any { it in OPERATORS && expr.indexOf(it) > 0 } && evaluate(expr) != null
}
