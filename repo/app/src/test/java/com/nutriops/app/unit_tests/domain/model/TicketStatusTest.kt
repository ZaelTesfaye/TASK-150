package com.nutriops.app.unit_tests.domain.model

import com.google.common.truth.Truth.assertThat
import com.nutriops.app.domain.model.TicketStatus
import org.junit.Test

class TicketStatusTest {

    @Test
    fun `OPEN can only transition to ASSIGNED`() {
        assertThat(TicketStatus.OPEN.allowedTransitions()).containsExactly(TicketStatus.ASSIGNED)
    }

    @Test
    fun `ASSIGNED can transition to IN_PROGRESS or ESCALATED`() {
        assertThat(TicketStatus.ASSIGNED.allowedTransitions())
            .containsExactly(TicketStatus.IN_PROGRESS, TicketStatus.ESCALATED)
    }

    @Test
    fun `IN_PROGRESS can transition to AWAITING_EVIDENCE, RESOLVED, or ESCALATED`() {
        assertThat(TicketStatus.IN_PROGRESS.allowedTransitions())
            .containsExactly(TicketStatus.AWAITING_EVIDENCE, TicketStatus.RESOLVED, TicketStatus.ESCALATED)
    }

    @Test
    fun `AWAITING_EVIDENCE can only transition back to IN_PROGRESS`() {
        assertThat(TicketStatus.AWAITING_EVIDENCE.allowedTransitions())
            .containsExactly(TicketStatus.IN_PROGRESS)
    }

    @Test
    fun `RESOLVED can only transition to CLOSED`() {
        assertThat(TicketStatus.RESOLVED.allowedTransitions()).containsExactly(TicketStatus.CLOSED)
    }

    @Test
    fun `ESCALATED can transition to IN_PROGRESS`() {
        assertThat(TicketStatus.ESCALATED.allowedTransitions()).containsExactly(TicketStatus.IN_PROGRESS)
    }

    @Test
    fun `CLOSED is terminal`() {
        assertThat(TicketStatus.CLOSED.allowedTransitions()).isEmpty()
    }

    @Test
    fun `invalid transitions are rejected`() {
        assertThat(TicketStatus.OPEN.canTransitionTo(TicketStatus.RESOLVED)).isFalse()
        assertThat(TicketStatus.OPEN.canTransitionTo(TicketStatus.CLOSED)).isFalse()
        assertThat(TicketStatus.CLOSED.canTransitionTo(TicketStatus.OPEN)).isFalse()
        assertThat(TicketStatus.RESOLVED.canTransitionTo(TicketStatus.IN_PROGRESS)).isFalse()
    }
}
