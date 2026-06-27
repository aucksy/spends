package com.spends.app.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.spends.app.core.Haptics

/**
 * Invisible foreground trampoline for the summary widget's eye tap (#1). The mask toggle itself is a
 * background broadcast, and many OEMs SUPPRESS vibration from the background — so the eye never buzzed
 * (while the "+" did, because it opens a foreground activity). Launching this tiny transparent activity
 * gives a foreground context that CAN vibrate: it fires the haptic, hands the actual toggle back to
 * [SummaryWidget], and finishes immediately (transparent theme + no content = nothing visible).
 */
class WidgetEyeToggleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Haptics.click(this) // foreground → actually vibrates (unlike the background broadcast)
        val id = intent.getIntExtra(EXTRA_WIDGET_ID, -1)
        if (id != -1) SummaryWidget.sendToggle(this, id)
        finish()
        overridePendingTransition(0, 0) // no enter/exit animation — keep it invisible
    }

    companion object {
        const val EXTRA_WIDGET_ID = "widget_id"
    }
}
