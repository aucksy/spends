package com.spends.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spends.app.data.demo.DemoDataSeeder
import com.spends.app.data.demo.DemoMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Drives the Demo mode row in Settings → Data & Trash. */
@HiltViewModel
class DemoModeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val seeder: DemoDataSeeder,
) : ViewModel() {

    /**
     * Whether this process is running the demo sandbox. Deliberately a plain `val`, not a Flow: it is fixed
     * for the life of the process, because changing it *is* a process restart.
     */
    val demoActive: Boolean = DemoMode.isEnabled(context)

    private val _working = MutableStateFlow(false)
    val working: StateFlow<Boolean> = _working

    private val _resetDone = MutableStateFlow(false)
    val resetDone: StateFlow<Boolean> = _resetDone


    fun consumeResetDone() { _resetDone.value = false }

    /**
     * Switch modes. Nothing is seeded here — the app has to restart *first* so that Room and the settings
     * DataStore are pointed at the demo files; `MainViewModel` then builds the sample account behind the
     * splash screen on the way back up.
     *
     * Pass an Activity context: the launch intent is fired while we are still foreground.
     */
    fun switchMode(activityContext: Context, intoDemo: Boolean) {
        // Only returns at all if the relaunch was refused — normally the process is already gone.
        if (!DemoMode.restartInto(activityContext, intoDemo)) {
            _switchFailed.value = true
        }
    }

    private val _switchFailed = MutableStateFlow(false)
    val switchFailed: StateFlow<Boolean> = _switchFailed

    fun consumeSwitchFailed() { _switchFailed.value = false }

    /** Rebuild the sample account from scratch, discarding anything changed during the demo. */
    fun resetDemoData() = viewModelScope.launch {
        if (!demoActive) return@launch
        _working.value = true
        runCatching { seeder.seed() }
        _working.value = false
        _resetDone.value = true
    }
}
