package com.spends.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Conservative learn-from-ignore (#7): how many times the user has ignored the SAME detection alert
 * (keyed by sender + merchant + amount). Once the count crosses a threshold, the live alert for that exact
 * pattern is no longer posted — instead the transaction is dropped SILENTLY into the review queue, so it's
 * never lost, just not nagged about. Device-local learning (not part of the backup snapshot).
 */
@Entity(tableName = "ignored_patterns")
data class IgnoredPatternEntity(
    @PrimaryKey val patternKey: String,
    val ignoreCount: Int,
    val updatedAt: Long,
)
