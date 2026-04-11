# Performance Evidence Summary

## Index Strategy

All query-critical paths have composite indexes defined in SQLDelight schema files:

| Table | Index | Purpose | Expected Query Time |
|-------|-------|---------|-------------------|
| MealPlans | `(userId, weekStartDate)` | Plan lookup by user and date range | < 50ms @ 1M rows |
| Tickets | `(status, updatedAt)` | Open ticket listing, SLA checks | < 50ms @ 1M rows |
| Tickets | `(userId)`, `(agentId)` | User/agent ticket views | < 50ms @ 1M rows |
| Tickets | `(slaFirstResponseDue, slaResolutionDue)` | SLA deadline queries | < 50ms @ 1M rows |
| Meals | `(mealPlanId)`, `(mealPlanId, dayOfWeek)` | Meal listing by plan and day | < 50ms @ 1M rows |
| AuditEvents | `(entityType, entityId)`, `(actorId)`, `(timestamp)` | Audit trail queries | < 50ms @ 1M rows |
| MetricsSnapshots | `(userId, snapshotDate)`, `(ruleId, snapshotDate)` | Historical metric queries | < 50ms @ 1M rows |
| Messages | `(userId)`, `(userId, isRead)` | Message inbox queries | < 50ms @ 1M rows |
| Reminders | `(scheduledAt, status)`, `(userId, scheduledAt)` | Pending reminder queries | < 50ms @ 1M rows |
| Coupons | `(code)` | Coupon validation | < 50ms |
| CouponUsages | `(couponId, userId)` | Usage count per user | < 50ms |

## Off-Main-Thread Execution

| Operation | Dispatcher | Implementation |
|-----------|-----------|---------------|
| All repository queries | `Dispatchers.IO` | `withContext(Dispatchers.IO)` in every repository method |
| Rule evaluation | `Dispatchers.Default` | `EvaluateRuleUseCase.evaluateAllRules()` |
| Back-calculation | `Dispatchers.Default` | `EvaluateRuleUseCase.backCalculate()` |
| Evidence processing | `Dispatchers.IO` | Repository layer |
| Reminder delivery | WorkManager | `ReminderWorker` - background coroutine |
| SLA checks | WorkManager | `SlaCheckWorker` - background coroutine |
| Rule evaluation (periodic) | WorkManager | `RuleEvaluationWorker` - background coroutine |

## Memory Safety

| Control | Implementation | Evidence |
|---------|---------------|----------|
| Image downsampling | Coil image loader with size constraints | `AppConfig.IMAGE_MAX_DIMENSION_PX = 1920` |
| LRU cache | 20 MB cap on image cache | `AppConfig.IMAGE_LRU_CACHE_MB = 20`, Coil configuration |
| Lazy lists | Compose LazyColumn for all list views | All screen composables use `LazyColumn` |
| No full-list loading | Paginated/limited queries where appropriate | SQL LIMIT clauses |

## WorkManager Scheduling

| Worker | Interval | Constraints | Purpose |
|--------|----------|-------------|---------|
| ReminderWorker | 15 min | Battery not low | Deliver pending reminders |
| SlaCheckWorker | 30 min | Battery not low | Check SLA compliance |
| RuleEvaluationWorker | On-demand | None | Evaluate rules for users |

## Background Execution Compliance

- `minSdk = 29` (Android 10+): scoped storage and background execution limits respected
- WorkManager handles OS-level scheduling constraints
- No foreground services or wake locks
- Battery-aware constraints on all periodic workers

## Benchmark Methodology

For production verification:
1. Seed database with 1M rows using test data generator
2. Measure indexed query times via `System.nanoTime()` bracketing
3. Verify all critical paths complete in < 50ms
4. Monitor heap usage during list scrolling to verify no OOM
5. Profile with Android Studio Profiler for 60 fps scrolling verification

Note: Static analysis confirms index presence and off-thread execution. Runtime benchmarks require a physical device.
