package com.nutriops.app.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Structural + semantic guard tests for every enum declared in
 * `com/nutriops/app/domain/model/Enums.kt`.
 *
 * For each enum we assert:
 *   - the expected constants exist (by name and ordinal)
 *   - any associated properties (display strings, trigger keys) resolve
 *   - any conversion helper (fromString) handles valid input and surfaces
 *     a useful error on invalid input
 *   - any state-machine helper (allowedTransitions / canTransitionTo) matches
 *     the documented transition matrix
 *
 * These tests catch accidental renames, silent reorderings, or broken state
 * machines that would otherwise only surface at runtime.
 */
class EnumsTest {

    // ── Role ──

    @Test
    fun `Role has exactly three constants in the documented order`() {
        assertThat(Role.entries.map { it.name }).containsExactly(
            "ADMINISTRATOR", "AGENT", "END_USER"
        ).inOrder()
    }

    // ── AgeRange ──

    @Test
    fun `AgeRange constants map to their documented display strings`() {
        assertThat(AgeRange.AGE_18_25.display).isEqualTo("18-25")
        assertThat(AgeRange.AGE_26_35.display).isEqualTo("26-35")
        assertThat(AgeRange.AGE_36_45.display).isEqualTo("36-45")
        assertThat(AgeRange.AGE_46_55.display).isEqualTo("46-55")
        assertThat(AgeRange.AGE_56_65.display).isEqualTo("56-65")
        assertThat(AgeRange.AGE_65_PLUS.display).isEqualTo("65+")
    }

    @Test
    fun `AgeRange fromString resolves every declared display string`() {
        for (range in AgeRange.entries) {
            assertThat(AgeRange.fromString(range.display)).isEqualTo(range)
        }
    }

    @Test
    fun `AgeRange fromString throws on unknown input`() {
        val thrown = runCatching { AgeRange.fromString("not-an-age") }.exceptionOrNull()
        assertThat(thrown).isNotNull()
    }

    // ── DietaryPattern ──

    @Test
    fun `DietaryPattern exposes the full controlled vocabulary`() {
        assertThat(DietaryPattern.entries.map { it.name }).containsExactly(
            "STANDARD", "VEGETARIAN", "VEGAN", "KETO",
            "PALEO", "MEDITERRANEAN", "LOW_SODIUM", "GLUTEN_FREE"
        ).inOrder()
    }

    // ── HealthGoal ──

    @Test
    fun `HealthGoal exposes the six supported goals with displays`() {
        assertThat(HealthGoal.entries.map { it.name }).containsExactly(
            "LOSE_HALF_LB_WEEK", "LOSE_1_LB_WEEK", "LOSE_2_LB_WEEK",
            "MAINTAIN", "GAIN_HALF_LB_WEEK", "GAIN_1_LB_WEEK"
        ).inOrder()
        assertThat(HealthGoal.MAINTAIN.display).isEqualTo("Maintain weight")
        assertThat(HealthGoal.LOSE_1_LB_WEEK.display).isEqualTo("Lose 1 lb/week")
        assertThat(HealthGoal.GAIN_1_LB_WEEK.display).isEqualTo("Gain 1 lb/week")
        // Every display is non-empty
        for (goal in HealthGoal.entries) {
            assertThat(goal.display).isNotEmpty()
        }
    }

    // ── MealTime ──

    @Test
    fun `MealTime lists three main meals and three snack slots`() {
        assertThat(MealTime.entries.map { it.name }).containsExactly(
            "BREAKFAST", "MORNING_SNACK", "LUNCH",
            "AFTERNOON_SNACK", "DINNER", "EVENING_SNACK"
        ).inOrder()
    }

    // ── LearningPlanStatus ──

    @Test
    fun `LearningPlanStatus transition matrix matches the documented DAG`() {
        assertThat(LearningPlanStatus.NOT_STARTED.allowedTransitions())
            .containsExactly(LearningPlanStatus.IN_PROGRESS)
        assertThat(LearningPlanStatus.IN_PROGRESS.allowedTransitions())
            .containsExactly(LearningPlanStatus.PAUSED, LearningPlanStatus.COMPLETED)
        assertThat(LearningPlanStatus.PAUSED.allowedTransitions())
            .containsExactly(LearningPlanStatus.IN_PROGRESS, LearningPlanStatus.COMPLETED)
        assertThat(LearningPlanStatus.COMPLETED.allowedTransitions())
            .containsExactly(LearningPlanStatus.ARCHIVED)
        assertThat(LearningPlanStatus.ARCHIVED.allowedTransitions()).isEmpty()
    }

    @Test
    fun `LearningPlanStatus canTransitionTo matches allowedTransitions`() {
        for (from in LearningPlanStatus.entries) {
            for (to in LearningPlanStatus.entries) {
                val expected = to in from.allowedTransitions()
                assertThat(from.canTransitionTo(to))
                    .isEqualTo(expected)
            }
        }
    }

    @Test
    fun `LearningPlanStatus ARCHIVED is terminal`() {
        for (target in LearningPlanStatus.entries) {
            assertThat(LearningPlanStatus.ARCHIVED.canTransitionTo(target)).isFalse()
        }
    }

    // ── TicketType / TicketPriority ──

    @Test
    fun `TicketType lists the three exception categories`() {
        assertThat(TicketType.entries.map { it.name }).containsExactly(
            "DELAY", "DISPUTE", "LOST_ITEM"
        ).inOrder()
    }

    @Test
    fun `TicketPriority ordinals reflect severity from LOW to CRITICAL`() {
        assertThat(TicketPriority.LOW.ordinal).isEqualTo(0)
        assertThat(TicketPriority.MEDIUM.ordinal).isEqualTo(1)
        assertThat(TicketPriority.HIGH.ordinal).isEqualTo(2)
        assertThat(TicketPriority.CRITICAL.ordinal).isEqualTo(3)
    }

    // ── TicketStatus ──

    @Test
    fun `TicketStatus transition matrix matches the documented DAG`() {
        assertThat(TicketStatus.OPEN.allowedTransitions())
            .containsExactly(TicketStatus.ASSIGNED)
        assertThat(TicketStatus.ASSIGNED.allowedTransitions())
            .containsExactly(TicketStatus.IN_PROGRESS, TicketStatus.ESCALATED)
        assertThat(TicketStatus.IN_PROGRESS.allowedTransitions()).containsExactly(
            TicketStatus.AWAITING_EVIDENCE, TicketStatus.RESOLVED, TicketStatus.ESCALATED
        )
        assertThat(TicketStatus.AWAITING_EVIDENCE.allowedTransitions())
            .containsExactly(TicketStatus.IN_PROGRESS)
        assertThat(TicketStatus.RESOLVED.allowedTransitions())
            .containsExactly(TicketStatus.CLOSED)
        assertThat(TicketStatus.ESCALATED.allowedTransitions())
            .containsExactly(TicketStatus.IN_PROGRESS)
        assertThat(TicketStatus.CLOSED.allowedTransitions()).isEmpty()
    }

    @Test
    fun `TicketStatus canTransitionTo mirrors allowedTransitions for every pair`() {
        for (from in TicketStatus.entries) {
            for (to in TicketStatus.entries) {
                val expected = to in from.allowedTransitions()
                assertThat(from.canTransitionTo(to)).isEqualTo(expected)
            }
        }
    }

    @Test
    fun `TicketStatus OPEN cannot skip directly to RESOLVED`() {
        assertThat(TicketStatus.OPEN.canTransitionTo(TicketStatus.RESOLVED)).isFalse()
    }

    // ── CompensationStatus / OrderStatus / ReconciliationState / ChargingSessionStatus ──

    @Test
    fun `CompensationStatus includes every workflow state`() {
        assertThat(CompensationStatus.entries.map { it.name }).containsExactly(
            "NONE", "SUGGESTED", "PENDING_APPROVAL", "APPROVED", "REJECTED"
        ).inOrder()
    }

    @Test
    fun `OrderStatus includes every pricing lifecycle state`() {
        assertThat(OrderStatus.entries.map { it.name }).containsExactly(
            "PENDING", "CONFIRMED", "PROCESSING", "COMPLETED", "CANCELLED", "REFUNDED"
        ).inOrder()
    }

    @Test
    fun `ReconciliationState covers UNRECONCILED through ADJUSTED`() {
        assertThat(ReconciliationState.entries.map { it.name }).containsExactly(
            "UNRECONCILED", "RECONCILED", "DISPUTED", "ADJUSTED"
        ).inOrder()
    }

    @Test
    fun `ChargingSessionStatus includes every processing state`() {
        assertThat(ChargingSessionStatus.entries.map { it.name }).containsExactly(
            "INITIATED", "AUTHORIZED", "CAPTURED", "FAILED", "REFUNDED"
        ).inOrder()
    }

    // ── EvidenceType / RuleType / RolloutStatus ──

    @Test
    fun `EvidenceType is strictly image or text - matches schema CHECK constraint`() {
        assertThat(EvidenceType.entries.map { it.name }).containsExactly("IMAGE", "TEXT").inOrder()
    }

    @Test
    fun `RuleType covers adherence, exception and operational KPI categories`() {
        assertThat(RuleType.entries.map { it.name }).containsExactly(
            "ADHERENCE", "EXCEPTION", "OPERATIONAL_KPI"
        ).inOrder()
    }

    @Test
    fun `RolloutStatus covers the canary lifecycle`() {
        assertThat(RolloutStatus.entries.map { it.name }).containsExactly(
            "PENDING", "CANARY", "FULL", "ROLLED_BACK"
        ).inOrder()
    }

    // ── MessageType / ReminderStatus / ListType / DiscountType ──

    @Test
    fun `MessageType covers notification, reminder, alert, todo`() {
        assertThat(MessageType.entries.map { it.name }).containsExactly(
            "NOTIFICATION", "REMINDER", "ALERT", "TODO"
        ).inOrder()
    }

    @Test
    fun `ReminderStatus covers delivery and skip reasons`() {
        assertThat(ReminderStatus.entries.map { it.name }).containsExactly(
            "PENDING", "DELIVERED", "SKIPPED_QUIET_HOURS", "SKIPPED_CAP_REACHED", "CANCELLED"
        ).inOrder()
    }

    @Test
    fun `ListType is binary - BLACK or WHITE`() {
        assertThat(ListType.entries.map { it.name }).containsExactly("BLACK", "WHITE").inOrder()
    }

    @Test
    fun `DiscountType is FIXED or PERCENTAGE`() {
        assertThat(DiscountType.entries.map { it.name }).containsExactly("FIXED", "PERCENTAGE").inOrder()
    }

    // ── TriggerEvent ──

    @Test
    fun `TriggerEvent exposes a non-empty unique snake_case key per entry`() {
        for (event in TriggerEvent.entries) {
            assertThat(event.key).isNotEmpty()
            assertThat(event.key).matches("[a-z_]+")
        }
        // Keys must be unique across the enum
        val keys = TriggerEvent.entries.map { it.key }
        assertThat(keys.toSet()).hasSize(keys.size)
    }

    @Test
    fun `TriggerEvent keys match the documented external names`() {
        assertThat(TriggerEvent.TICKET_CREATED.key).isEqualTo("ticket_created")
        assertThat(TriggerEvent.TICKET_UPDATED.key).isEqualTo("ticket_updated")
        assertThat(TriggerEvent.TICKET_RESOLVED.key).isEqualTo("ticket_resolved")
        assertThat(TriggerEvent.PLAN_STARTED.key).isEqualTo("plan_started")
        assertThat(TriggerEvent.PLAN_COMPLETED.key).isEqualTo("plan_completed")
        assertThat(TriggerEvent.PLAN_PAUSED.key).isEqualTo("plan_paused")
        assertThat(TriggerEvent.COMPENSATION_APPROVED.key).isEqualTo("compensation_approved")
        assertThat(TriggerEvent.SLA_WARNING.key).isEqualTo("sla_warning")
        assertThat(TriggerEvent.SLA_BREACHED.key).isEqualTo("sla_breached")
    }

    // ── AuditAction ──

    @Test
    fun `AuditAction includes every documented write category`() {
        val names = AuditAction.entries.map { it.name }.toSet()
        // Sanity-check: the audit vocabulary must include at least these core
        // actions. The enum may grow -- this assertion guards against
        // *removals* without updating the consumer code that references them.
        assertThat(names).containsAtLeast(
            "CREATE", "UPDATE", "DELETE",
            "STATUS_CHANGE", "LOGIN", "LOGIN_FAILED", "LOGOUT",
            "LOCK_ACCOUNT", "ASSIGN",
            "COMPENSATION_SUGGEST", "COMPENSATION_APPROVE", "COMPENSATION_REJECT",
            "SWAP_MEAL", "DUPLICATE_PLAN", "EVIDENCE_UPLOAD",
            "ROLLOUT_START", "ROLLOUT_COMPLETE", "ROLLOUT_ROLLBACK",
            "CONFIG_CHANGE", "RULE_EVALUATE", "SLA_BREACH", "REVEAL_PII"
        )
    }

    @Test
    fun `AuditAction constants are unique - no duplicate names`() {
        val names = AuditAction.entries.map { it.name }
        assertThat(names.toSet()).hasSize(names.size)
    }
}
