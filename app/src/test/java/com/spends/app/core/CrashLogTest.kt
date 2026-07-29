package com.spends.app.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [CrashLog] is the only thing standing between an on-device crash and no information at all, so it is
 * tested end-to-end through the real uncaught-exception handler rather than by calling its writer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CrashLogTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(null)
        CrashLog.clear(context)
    }

    /**
     * A no-op handler is installed FIRST so that [CrashLog.install] captures it as the one to delegate
     * to. Without that, driving a crash through the handler would hand it to the JVM's real default and
     * take the test runner down with it.
     */
    private fun installOverANoOpHandler() {
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
        CrashLog.install(context)
    }

    @Test
    fun nothing_is_reported_before_a_crash() {
        CrashLog.clear(context)
        assertThat(CrashLog.read(context)).isNull()
    }

    @Test
    fun a_crash_is_recorded_and_can_be_read_back() {
        CrashLog.clear(context)
        installOverANoOpHandler()

        Thread.getDefaultUncaughtExceptionHandler()!!
            .uncaughtException(Thread.currentThread(), IllegalStateException("boom-from-the-test"))

        val report = CrashLog.read(context)
        assertThat(report).isNotNull()
        // The exception itself, and the context needed to act on an OEM-specific fault.
        assertThat(report).contains("boom-from-the-test")
        assertThat(report).contains("IllegalStateException")
        assertThat(report).contains("SPENDS — LAST CRASH")
        assertThat(report).contains("Android:")
        assertThat(report).contains("Device:")
    }

    /** The crash must still reach Android's own handler — recording it must not swallow it. */
    @Test
    fun the_crash_is_passed_on_to_the_previous_handler() {
        var delegated: Throwable? = null
        Thread.setDefaultUncaughtExceptionHandler { _, e -> delegated = e }
        CrashLog.install(context)

        val thrown = IllegalStateException("must-be-passed-on")
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), thrown)

        assertThat(delegated).isSameInstanceAs(thrown)
    }

    @Test
    fun dismissing_clears_it() {
        CrashLog.clear(context)
        installOverANoOpHandler()
        Thread.getDefaultUncaughtExceptionHandler()!!
            .uncaughtException(Thread.currentThread(), IllegalStateException("boom"))
        assertThat(CrashLog.read(context)).isNotNull()

        CrashLog.clear(context)

        assertThat(CrashLog.read(context)).isNull()
    }
}
