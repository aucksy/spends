package com.spends.app.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.spends.app.R
import com.spends.app.core.MainActivity
import com.spends.app.core.money.Money
import com.spends.app.data.repo.MaterializedTxn
import com.spends.app.domain.model.TxnKind
import com.spends.app.receiver.RecurringActionReceiver

/**
 * Posts the "recurring added" heads-up when the daily pass materialises scheduled transactions while the
 * user isn't in the app (#3). Shared by the exact-alarm path ([com.spends.app.receiver.RecurringAlarmReceiver])
 * so there's a single channel definition + notification builder.
 *
 * ## It says WHAT was added, not just how many
 * The old notification read "1 scheduled transaction was added" and stopped there — the user had to open
 * the app and hunt for it to find out whether it was rent or a subscription, and whether the amount was
 * still right. Each transaction now gets its own notification naming it, with its note and amount, and two
 * actions: **Edit** opens that exact transaction, **Dismiss** clears the notification and nothing else.
 *
 * ## Why one notification per transaction, up to a cap
 * A per-transaction notification is the only shape where "Edit" has an unambiguous target. But
 * materialisation also back-fills: someone who hasn't opened the app for three months can have a hundred
 * occurrences created in one pass, and a hundred heads-ups is an app the user turns off. Past
 * [MAX_INDIVIDUAL] the batch collapses to a single roll-up that just opens the app — the honest shape,
 * because at that size there is no single transaction the user meant to edit.
 *
 * No explicit notification group is used. Android already bundles several notifications from one app, and
 * an app-managed group summary outlives its children on some OEM builds — leaving a stuck "3 added" row
 * after all three were dismissed.
 */
object RecurringNotifier {

    private const val CHANNEL_ID = "recurring"

    /** Roll-up id, and the base the per-transaction ids are spread above it. */
    private const val ROLLUP_NOTIF_ID = 71_001
    private const val NOTIF_ID_BASE = 71_100

    /** Above this many occurrences in one pass, post one roll-up instead of a wall of heads-ups. */
    private const val MAX_INDIVIDUAL = 5

    /** Tell the user what was added. Best-effort — never throws. */
    fun notify(context: Context, created: List<MaterializedTxn>) {
        if (created.isEmpty()) return
        runCatching {
            ensureChannel(context)
            val manager = NotificationManagerCompat.from(context)
            if (created.size > MAX_INDIVIDUAL) {
                manager.notify(ROLLUP_NOTIF_ID, rollup(context, created.size))
            } else {
                created.forEach { txn ->
                    manager.notify(notifIdFor(txn.expenseId), single(context, txn))
                }
            }
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Recurring transactions",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "Tells you when scheduled transactions (rent, EMIs, subscriptions) are added." }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    /** One added transaction, named, with Edit + Dismiss. */
    private fun single(context: Context, txn: MaterializedTxn) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(titleFor(txn))
            .setContentText(detailFor(txn))
            // The note is the field most likely to be cut off in a collapsed row, and it is exactly what
            // tells the user which of two similar standing payments this is.
            .setStyle(NotificationCompat.BigTextStyle().bigText(detailFor(txn)))
            .setSubText("Added automatically")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openTransaction(context, txn.expenseId, req = 0))
            .addAction(0, "Edit", openTransaction(context, txn.expenseId, req = 1))
            .addAction(0, "Dismiss", dismiss(context, notifIdFor(txn.expenseId)))
            .setAutoCancel(true)
            .build()

    /** A back-fill too large to name one by one — opens the app, nothing more. */
    private fun rollup(context: Context, count: Int) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Recurring added")
            .setContentText("$count scheduled transactions were added")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()

    /**
     * The name the user gave the rule. Falling back to the note before the generic wording matters: a rule
     * saved with only a note ("Landlord — 2BHK") would otherwise read "Scheduled transaction" and tell them
     * nothing, which is the exact complaint this change exists to fix.
     */
    internal fun titleFor(txn: MaterializedTxn): String =
        txn.name ?: txn.note ?: "Scheduled transaction"

    /**
     * Amount first (signed the way the ledger shows it), then the note — but never the note twice, which is
     * what would happen for a rule that has a note and no name, since [titleFor] has already used it.
     */
    internal fun detailFor(txn: MaterializedTxn): String {
        val sign = if (txn.kind == TxnKind.INCOME) "+" else "-"
        val amount = sign + Money.formatRupees(txn.amountMinor)
        val note = txn.note?.takeIf { it != titleFor(txn) }
        return if (note != null) "$amount · $note" else amount
    }

    /**
     * A stable id per transaction, so re-posting the same occurrence replaces its row rather than stacking.
     * The modulo bounds it into a small band; two ids exactly [ID_SPREAD] apart would share a row, which
     * cannot happen inside one pass (a pass creates far fewer than that) and would merely replace a
     * notification, never mis-target one — the expense id travels in the intent, not in this number.
     */
    private fun notifIdFor(expenseId: Long): Int = NOTIF_ID_BASE + (expenseId % ID_SPREAD).toInt()

    private const val ID_SPREAD = 5_000L

    private fun openTransaction(context: Context, expenseId: Long, req: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_EXPENSE_ID, expenseId)
        }
        // Request code varies per transaction AND per action, or the two actions of two different
        // notifications would share one PendingIntent and the second would open the first's transaction.
        return PendingIntent.getActivity(context, notifIdFor(expenseId) * 4 + req, intent, PENDING_FLAGS)
    }

    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(context, ROLLUP_NOTIF_ID, intent, PENDING_FLAGS)
    }

    private fun dismiss(context: Context, notifId: Int): PendingIntent {
        val intent = Intent(context, RecurringActionReceiver::class.java).apply {
            action = RecurringActionReceiver.ACTION_DISMISS
            putExtra(RecurringActionReceiver.EXTRA_NOTIF_ID, notifId)
        }
        return PendingIntent.getBroadcast(context, notifId * 4 + 3, intent, PENDING_FLAGS)
    }

    private const val PENDING_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
}
