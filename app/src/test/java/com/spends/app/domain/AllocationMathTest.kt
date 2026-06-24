package com.spends.app.domain

import com.google.common.truth.Truth.assertThat
import com.spends.app.domain.allocation.AllocationMath
import org.junit.Test

class AllocationMathTest {

    @Test fun equal_split_of_5000_across_3_reconciles_exactly() {
        val parts = AllocationMath.splitEqual(500000, 3) // ₹5000 in paise
        assertThat(parts.sum()).isEqualTo(500000)
        assertThat(parts.toList()).containsExactly(166667L, 166667L, 166666L).inOrder()
    }

    @Test fun equal_split_handles_clean_division() {
        val parts = AllocationMath.splitEqual(900, 3)
        assertThat(parts.toList()).containsExactly(300L, 300L, 300L)
    }

    @Test fun percentage_split_reconciles() {
        val parts = AllocationMath.splitByPercent(100000, intArrayOf(33, 33, 34))
        assertThat(parts.sum()).isEqualTo(100000)
    }

    @Test fun weighted_split_reconciles_for_many_shapes() {
        val totals = longArrayOf(1, 7, 100, 99999, 500000, 1234567)
        val weightSets = listOf(
            longArrayOf(1, 1, 1),
            longArrayOf(1, 2, 3, 4),
            longArrayOf(5, 5),
            longArrayOf(10, 1, 1, 1, 1, 1, 1),
        )
        for (t in totals) {
            for (w in weightSets) {
                val parts = AllocationMath.splitByWeights(t, w)
                assertThat(parts.sum()).isEqualTo(t)
                assertThat(parts.size).isEqualTo(w.size)
                parts.forEach { assertThat(it).isAtLeast(0L) }
            }
        }
    }

    @Test fun zero_weights_fall_back_to_equal_and_reconcile() {
        val parts = AllocationMath.splitByWeights(1000, longArrayOf(0, 0, 0))
        assertThat(parts.sum()).isEqualTo(1000)
    }

    @Test fun reconciles_helper() {
        assertThat(AllocationMath.reconciles(500000, longArrayOf(166667, 166667, 166666))).isTrue()
        assertThat(AllocationMath.reconciles(500000, longArrayOf(1, 2, 3))).isFalse()
    }
}
