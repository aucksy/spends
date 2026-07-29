package com.spends.app.core.calc

/**
 * The single definition of carry-forward.
 *
 * **Why this exists as one function.** The rule used to be written out at each place that needed it —
 * twice inside `TransactionsViewModel` and, crucially, *not at all* in the home-screen widget, which
 * simply showed `income − expense`. Anyone with carry-forward switched on therefore read one balance on
 * their home screen and a different one inside the app, with no hint which was right. Copying a money
 * rule is how the copies drift; there is now one, and every caller supplies only its own inputs.
 *
 * **The guards are the load-bearing part**, not the arithmetic:
 *  - carry-forward REQUIRES an anchor date. Without one, folding in all of an incomplete history
 *    produced a hugely-negative balance — a real defect this guard exists to prevent;
 *  - a window starting before the anchor gets no carry-in, because the opening balance is only claimed
 *    to be true *as of* the anchor;
 *  - [applies] lets a caller opt out entirely: carry-forward is a whole-account running balance, so it
 *    is meaningless over a single card's statement.
 */
object CarryForward {

    /**
     * The amount carried into a window, or `null` when carry-forward does not apply — `null` and `0`
     * mean different things to the UI, which shows a "Carry forward" tile only for a non-null value.
     *
     * [netSinceAnchor] is evaluated **only if** every guard passes, so a caller can put a database read
     * behind it without paying for one when the feature is off. It must return the net (income − expense)
     * of everything from the anchor up to, but not including, [periodStartMillis].
     */
    inline fun resolve(
        enabled: Boolean,
        anchorMillis: Long,
        openingMinor: Long,
        periodStartMillis: Long,
        applies: Boolean = true,
        netSinceAnchor: () -> Long,
    ): Long? = when {
        !enabled -> null
        anchorMillis <= 0 -> null
        !applies -> null
        periodStartMillis < anchorMillis -> null
        else -> openingMinor + netSinceAnchor()
    }
}
