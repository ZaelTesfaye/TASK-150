package com.nutriops.app.domain.model

/** Three roles with enforced permissions */
enum class Role {
    ADMINISTRATOR,
    AGENT,
    END_USER
}

/** Controlled vocabulary for age ranges */
enum class AgeRange(val display: String) {
    AGE_18_25("18-25"),
    AGE_26_35("26-35"),
    AGE_36_45("36-45"),
    AGE_46_55("46-55"),
    AGE_56_65("56-65"),
    AGE_65_PLUS("65+");

    companion object {
        fun fromString(value: String): AgeRange = entries.first { it.display == value }
    }
}

/** Controlled vocabulary for dietary patterns */
enum class DietaryPattern {
    STANDARD,
    VEGETARIAN,
    VEGAN,
    KETO,
    PALEO,
    MEDITERRANEAN,
    LOW_SODIUM,
    GLUTEN_FREE
}

/** Controlled vocabulary for health goals */
enum class HealthGoal(val display: String) {
    LOSE_HALF_LB_WEEK("Lose 0.5 lb/week"),
    LOSE_1_LB_WEEK("Lose 1 lb/week"),
    LOSE_2_LB_WEEK("Lose 2 lb/week"),
    MAINTAIN("Maintain weight"),
    GAIN_HALF_LB_WEEK("Gain 0.5 lb/week"),
    GAIN_1_LB_WEEK("Gain 1 lb/week")
}

/** Meal time slots */
enum class MealTime {
    BREAKFAST,
    MORNING_SNACK,
    LUNCH,
    AFTERNOON_SNACK,
    DINNER,
    EVENING_SNACK
}

/**
 * Learning Plan status with constrained transition matrix:
 * NOT_STARTED -> IN_PROGRESS
 * IN_PROGRESS -> PAUSED | COMPLETED
 * PAUSED -> IN_PROGRESS | COMPLETED
 * COMPLETED -> ARCHIVED (but cannot be edited; must duplicate-before-edit)
 * ARCHIVED -> (terminal)
 */
enum class LearningPlanStatus {
    NOT_STARTED,
    IN_PROGRESS,
    PAUSED,
    COMPLETED,
    ARCHIVED;

    fun allowedTransitions(): Set<LearningPlanStatus> = when (this) {
        NOT_STARTED -> setOf(IN_PROGRESS)
        IN_PROGRESS -> setOf(PAUSED, COMPLETED)
        PAUSED -> setOf(IN_PROGRESS, COMPLETED)
        COMPLETED -> setOf(ARCHIVED)
        ARCHIVED -> emptySet()
    }

    fun canTransitionTo(target: LearningPlanStatus): Boolean =
        target in allowedTransitions()
}

/** Ticket types for exception/after-sales handling */
enum class TicketType {
    DELAY,
    DISPUTE,
    LOST_ITEM
}

/**
 * Ticket status with constrained transitions:
 * OPEN -> ASSIGNED
 * ASSIGNED -> IN_PROGRESS | ESCALATED
 * IN_PROGRESS -> AWAITING_EVIDENCE | RESOLVED | ESCALATED
 * AWAITING_EVIDENCE -> IN_PROGRESS
 * RESOLVED -> CLOSED
 * ESCALATED -> IN_PROGRESS
 * CLOSED -> (terminal)
 */
enum class TicketStatus {
    OPEN,
    ASSIGNED,
    IN_PROGRESS,
    AWAITING_EVIDENCE,
    RESOLVED,
    CLOSED,
    ESCALATED;

    fun allowedTransitions(): Set<TicketStatus> = when (this) {
        OPEN -> setOf(ASSIGNED)
        ASSIGNED -> setOf(IN_PROGRESS, ESCALATED)
        IN_PROGRESS -> setOf(AWAITING_EVIDENCE, RESOLVED, ESCALATED)
        AWAITING_EVIDENCE -> setOf(IN_PROGRESS)
        RESOLVED -> setOf(CLOSED)
        ESCALATED -> setOf(IN_PROGRESS)
        CLOSED -> emptySet()
    }

    fun canTransitionTo(target: TicketStatus): Boolean =
        target in allowedTransitions()
}

enum class TicketPriority {
    LOW, MEDIUM, HIGH, CRITICAL
}

/** Compensation approval status */
enum class CompensationStatus {
    NONE,
    SUGGESTED,
    PENDING_APPROVAL,
    APPROVED,
    REJECTED
}

/** Order status with reconciliation awareness */
enum class OrderStatus {
    PENDING,
    CONFIRMED,
    PROCESSING,
    COMPLETED,
    CANCELLED,
    REFUNDED
}

enum class ReconciliationState {
    UNRECONCILED,
    RECONCILED,
    DISPUTED,
    ADJUSTED
}

enum class ChargingSessionStatus {
    INITIATED,
    AUTHORIZED,
    CAPTURED,
    FAILED,
    REFUNDED
}

/** Evidence types: image/text only */
enum class EvidenceType {
    IMAGE,
    TEXT
}

/** Rule types */
enum class RuleType {
    ADHERENCE,
    EXCEPTION,
    OPERATIONAL_KPI
}

/** Rollout status */
enum class RolloutStatus {
    PENDING,
    CANARY,
    FULL,
    ROLLED_BACK
}

/** Message types */
enum class MessageType {
    NOTIFICATION,
    REMINDER,
    ALERT,
    TODO
}

/** Reminder delivery status */
enum class ReminderStatus {
    PENDING,
    DELIVERED,
    SKIPPED_QUIET_HOURS,
    SKIPPED_CAP_REACHED,
    CANCELLED
}

/** Trigger events for messaging */
enum class TriggerEvent(val key: String) {
    PLAN_STARTED("plan_started"),
    PLAN_COMPLETED("plan_completed"),
    PLAN_PAUSED("plan_paused"),
    TICKET_CREATED("ticket_created"),
    TICKET_UPDATED("ticket_updated"),
    TICKET_RESOLVED("ticket_resolved"),
    RULE_TRIGGERED("rule_triggered"),
    RULE_ROLLOUT("rule_rollout"),
    COMPENSATION_APPROVED("compensation_approved"),
    MEAL_PLAN_GENERATED("meal_plan_generated"),
    REMINDER_DUE("reminder_due"),
    SLA_WARNING("sla_warning"),
    SLA_BREACHED("sla_breached")
}

/** Black/white list type */
enum class ListType {
    BLACK,
    WHITE
}

/** Discount types for coupons */
enum class DiscountType {
    FIXED,
    PERCENTAGE
}

/** Audit action types */
enum class AuditAction {
    CREATE,
    UPDATE,
    DELETE,
    STATUS_CHANGE,
    LOGIN,
    LOGIN_FAILED,
    LOGOUT,
    LOCK_ACCOUNT,
    UNLOCK_ACCOUNT,
    ASSIGN,
    APPROVE,
    REJECT,
    REVEAL_PII,
    SWAP_MEAL,
    DUPLICATE_PLAN,
    ROLLOUT_START,
    ROLLOUT_COMPLETE,
    ROLLOUT_ROLLBACK,
    COMPENSATION_SUGGEST,
    COMPENSATION_APPROVE,
    COMPENSATION_REJECT,
    EVIDENCE_UPLOAD,
    CONFIG_CHANGE,
    RULE_EVALUATE,
    SLA_BREACH
}
