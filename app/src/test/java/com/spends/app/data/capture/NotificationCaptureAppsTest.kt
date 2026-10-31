package com.spends.app.data.capture

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The Settings checklist offers the phone's own SMS app when it is not already listed. */
class NotificationCaptureAppsTest {

    @Test fun vivo_sms_app_is_added_with_its_label() {
        val list = NotificationCaptureApps.candidatesFor("com.android.mms", "Messages")
        assertThat(list.last()).isEqualTo(
            NotificationCaptureApps.CandidateApp("com.android.mms", "Messages (your SMS app)"),
        )
        assertThat(list).hasSize(NotificationCaptureApps.CANDIDATES.size + 1)
    }

    @Test fun listed_sms_app_is_not_added_twice() {
        assertThat(NotificationCaptureApps.candidatesFor(NotificationCaptureApps.TRUECALLER, "Truecaller"))
            .isEqualTo(NotificationCaptureApps.CANDIDATES)
    }

    @Test fun no_sms_app_means_the_plain_list() {
        assertThat(NotificationCaptureApps.candidatesFor(null, null)).isEqualTo(NotificationCaptureApps.CANDIDATES)
        assertThat(NotificationCaptureApps.candidatesFor("", null)).isEqualTo(NotificationCaptureApps.CANDIDATES)
    }
}
