# Rules & SLA Semantics Document

## Rules Engine Semantics

### Compound Conditions
Format: `A AND (B OR C)` expressed as JSON:
```json
{
  "type": "and",
  "children": [
    {"type": "metric", "metricType": "adherence_rate", "operator": ">=", "threshold": 80.0},
    {
      "type": "or",
      "children": [
        {"type": "metric", "metricType": "completion_rate", "operator": ">=", "threshold": 90.0},
        {"type": "metric", "metricType": "exception_count", "operator": "<", "threshold": 3.0}
      ]
    }
  ]
}
```

**Implementation**: `EvaluateRuleUseCase.evaluateCondition()` - recursive evaluation of condition tree.

### Hysteresis
- **Enter threshold**: Rule triggers when metric crosses 80% (configurable per rule)
- **Exit threshold**: Rule un-triggers when metric rises above 90%
- **Purpose**: Prevents rule flapping near boundary values

**Implementation**: `EvaluateRuleUseCase.evaluateAllRules()` checks last metric state to determine if rule was already triggered, then applies exit threshold.

### Minimum Duration
- Condition must hold for 10 minutes (configurable per rule) before triggering
- **Purpose**: Filters transient spikes

**Implementation**: `Rules.sq:minimumDurationMinutes` field stored per rule and rule version.

### Effective Time Windows
- Rules can have optional `effectiveWindowStart` and `effectiveWindowEnd`
- Rules outside their window are skipped during evaluation

**Implementation**: `EvaluateRuleUseCase.isInEffectiveWindow()` - checks current time against window bounds.

### Rule Versioning
- Every rule update increments version and creates a `RuleVersions` entry
- Historical versions preserved for back-calculation

**Implementation**: `RuleRepository.updateRule()` - atomic version increment + version record.

### Back-Calculation
- Replays rule evaluation for historical date ranges using the rule version active at each snapshot time
- Uses `MetricsSnapshots` data and finds the applicable `RuleVersions` entry

**Implementation**: `EvaluateRuleUseCase.backCalculate()` - matches snapshots to versions by date.

## SLA Semantics

### Business Calendar
- **Business days**: Monday to Friday (configurable in `AppConfig.SLA_BUSINESS_DAYS`)
- **Business hours**: 9:00 AM to 6:00 PM
- Weekends and hours outside this range do not count toward SLA

### First Response SLA
- **Target**: 4 business hours from ticket creation
- **Calculation**: `TicketRepository.calculateBusinessHoursDeadline()` - counts only hours within business day/time
- **Tracking**: `slaFirstResponseAt` recorded when ticket first transitions to IN_PROGRESS

### Resolution SLA
- **Target**: 3 business days from ticket creation
- **Calculation**: `TicketRepository.calculateBusinessDaysDeadline()` - counts only business days
- **Tracking**: `slaResolvedAt` recorded when ticket transitions to RESOLVED

### SLA Pause/Resume
- SLA clock can be paused (e.g., waiting for customer evidence)
- `slaPausedAt` records pause start, `slaTotalPauseDurationMinutes` accumulates total pause time
- Effective deadline = original deadline + total pause duration

### SLA Monitoring
- `SlaCheckWorker` runs every 30 minutes via WorkManager
- Sends `SLA_WARNING` when < 1 hour remaining
- Sends `SLA_BREACHED` when deadline passed
- Both events trigger in-app notifications

## Reminder/Messaging Semantics

### Quiet Hours
- **Window**: 9:00 PM to 7:00 AM (crosses midnight)
- **Behavior**: Reminders scheduled during quiet hours receive status `SKIPPED_QUIET_HOURS`
- **Implementation**: `MessageRepository.isQuietHours()` handles midnight-crossing logic

### Daily Cap
- **Limit**: Maximum 3 reminders per user per day
- **Behavior**: Excess reminders receive status `SKIPPED_CAP_REACHED`
- **Implementation**: `MessageRepository.deliverReminder()` queries today's delivered count

### Trigger Events
All messaging is event-driven. Supported triggers:
- `plan_started`, `plan_completed`, `plan_paused`
- `ticket_created`, `ticket_updated`, `ticket_resolved`
- `rule_triggered`, `rule_rollout`
- `compensation_approved`
- `meal_plan_generated`
- `sla_warning`, `sla_breached`

### Template Variables
Templates use `{{variable}}` syntax resolved at send time.
Example: `"Your ticket '{{subject}}' has been updated to {{status}}"`
