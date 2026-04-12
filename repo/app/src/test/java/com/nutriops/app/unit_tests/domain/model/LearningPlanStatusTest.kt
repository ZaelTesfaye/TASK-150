package com.nutriops.app.unit_tests.domain.model

import com.google.common.truth.Truth.assertThat
import com.nutriops.app.domain.model.LearningPlanStatus
import org.junit.Test

class LearningPlanStatusTest {

    @Test
    fun `NOT_STARTED can transition to IN_PROGRESS`() {
        assertThat(LearningPlanStatus.NOT_STARTED.canTransitionTo(LearningPlanStatus.IN_PROGRESS)).isTrue()
    }

    @Test
    fun `NOT_STARTED cannot transition to COMPLETED`() {
        assertThat(LearningPlanStatus.NOT_STARTED.canTransitionTo(LearningPlanStatus.COMPLETED)).isFalse()
    }

    @Test
    fun `NOT_STARTED cannot transition to PAUSED`() {
        assertThat(LearningPlanStatus.NOT_STARTED.canTransitionTo(LearningPlanStatus.PAUSED)).isFalse()
    }

    @Test
    fun `IN_PROGRESS can transition to PAUSED`() {
        assertThat(LearningPlanStatus.IN_PROGRESS.canTransitionTo(LearningPlanStatus.PAUSED)).isTrue()
    }

    @Test
    fun `IN_PROGRESS can transition to COMPLETED`() {
        assertThat(LearningPlanStatus.IN_PROGRESS.canTransitionTo(LearningPlanStatus.COMPLETED)).isTrue()
    }

    @Test
    fun `PAUSED can transition to IN_PROGRESS`() {
        assertThat(LearningPlanStatus.PAUSED.canTransitionTo(LearningPlanStatus.IN_PROGRESS)).isTrue()
    }

    @Test
    fun `PAUSED can transition to COMPLETED`() {
        assertThat(LearningPlanStatus.PAUSED.canTransitionTo(LearningPlanStatus.COMPLETED)).isTrue()
    }

    @Test
    fun `COMPLETED can transition to ARCHIVED`() {
        assertThat(LearningPlanStatus.COMPLETED.canTransitionTo(LearningPlanStatus.ARCHIVED)).isTrue()
    }

    @Test
    fun `COMPLETED cannot transition to IN_PROGRESS`() {
        assertThat(LearningPlanStatus.COMPLETED.canTransitionTo(LearningPlanStatus.IN_PROGRESS)).isFalse()
    }

    @Test
    fun `COMPLETED cannot be edited - no transition to NOT_STARTED`() {
        assertThat(LearningPlanStatus.COMPLETED.canTransitionTo(LearningPlanStatus.NOT_STARTED)).isFalse()
    }

    @Test
    fun `ARCHIVED has no allowed transitions`() {
        assertThat(LearningPlanStatus.ARCHIVED.allowedTransitions()).isEmpty()
    }

    @Test
    fun `all statuses have valid transition matrix`() {
        for (status in LearningPlanStatus.entries) {
            val transitions = status.allowedTransitions()
            // Self-transition should never be allowed
            assertThat(transitions).doesNotContain(status)
        }
    }
}
