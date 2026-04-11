# Requirements Traceability Matrix

## Requirement → Implementation → Test Evidence

| # | Requirement | Implementation | Test/Evidence |
|---|------------|---------------|---------------|
| R01 | Three roles: Administrator, Agent, End User | `domain/model/Enums.kt:Role`, `security/RbacManager.kt` | `RbacManagerTest.kt` - all role permission tests |
| R02 | End User profile (age, dietary, allergies, goal, meal times) | `ui/enduser/UserProfileScreen.kt`, `domain/usecase/profile/ManageProfileUseCase.kt` | `ManageProfileUseCaseTest.kt` |
| R03 | Weekly plan with daily calorie budget and macro targets | `domain/usecase/mealplan/GenerateWeeklyPlanUseCase.kt` | `ManageProfileUseCaseTest.kt` - macro calculations |
| R04 | >= 2 explainable reasons per recommendation | `GenerateWeeklyPlanUseCase.kt:generateDailyMeals()` - reasons list always has 3 entries | Static verification: reasons list construction |
| R05 | One-click meal swaps within ±10% cal / ±5g protein | `domain/usecase/mealplan/SwapMealUseCase.kt`, `data/repository/MealPlanRepository.kt:performSwap()` | `AppConfigTest.kt` - tolerance constants |
| R06 | Prevent plans with end < start or frequency > 7 | `data/repository/MealPlanRepository.kt`, `LearningPlanRepository.kt` + SQL CHECK constraints | `LearningPlanStatusTest.kt`, SQL CHECK in .sq files |
| R07 | Learning Plan lifecycle (5 statuses) | `domain/model/Enums.kt:LearningPlanStatus` with transition matrix | `LearningPlanStatusTest.kt` - all transitions |
| R08 | Status changes require confirmation | `ui/enduser/UserLearningPlanScreen.kt` - ConfirmationDialog before transition | UI composable with ConfirmationDialog |
| R09 | Completed plans cannot be edited, must duplicate | `data/repository/LearningPlanRepository.kt:duplicatePlan()` | `LearningPlanStatusTest.kt` - COMPLETED transitions |
| R10 | Operations & Config Center (homepage, ads, campaigns, coupons) | `data/repository/ConfigRepository.kt`, `domain/usecase/config/ManageConfigUseCase.kt` | `AdminConfigScreen.kt` - full CRUD UI |
| R11 | Black/whitelist and purchase limits | `ConfigRepository.kt` - addToList, isBlacklisted, getPurchaseLimits | Config queries in `Configs.sq` |
| R12 | Config versioning | `ConfigRepository.kt:updateConfig()` creates ConfigVersions | SQL schema: ConfigVersions table |
| R13 | 10% canary rollout (deterministic) | `data/repository/RolloutRepository.kt:createRollout()` - sorted IDs, first N% | `Rollouts.sq` schema, rollout logic |
| R14 | Metrics/Rules engine with compound conditions | `domain/usecase/rules/EvaluateRuleUseCase.kt` | `EvaluateRuleUseCaseTest.kt` - AND/OR/nested |
| R15 | Hysteresis (enter 80%, exit 90%) | `EvaluateRuleUseCase.kt:evaluateAllRules()` | `AppConfigTest.kt` - default values |
| R16 | Minimum duration (10 min) | `Rules.sq:minimumDurationMinutes`, `AppConfig.MIN_DURATION_DEFAULT_MINUTES` | Schema + config test |
| R17 | Effective time windows | `EvaluateRuleUseCase.kt:isInEffectiveWindow()` | Unit logic in evaluator |
| R18 | Rule versioning | `RuleRepository.kt:updateRule()` + RuleVersions table | `Rules.sq` - UNIQUE(ruleId, version) |
| R19 | Back-calculation for historical date ranges | `EvaluateRuleUseCase.kt:backCalculate()` | `EvaluateRuleUseCaseTest.kt` |
| R20 | Ticket flow (delay/dispute/lost-item) | `domain/usecase/ticket/ManageTicketUseCase.kt` | `TicketStatusTest.kt` |
| R21 | SLA clocks (4 biz hours first response, 3 days resolution) | `data/repository/TicketRepository.kt:calculateBusinessHoursDeadline()` | `AppConfigTest.kt` - SLA config |
| R22 | Evidence upload (image/text only) | `TicketRepository.kt:addEvidence()` - type check | Schema CHECK constraint |
| R23 | Compensation suggestions ($3-$20, agent approval > $10) | `TicketRepository.kt:suggestCompensation()` | `AppConfigTest.kt` - thresholds |
| R24 | Immutable audit trail | `AuditEvents.sq` - INSERT only, no UPDATE/DELETE | Schema review, `AuditManager.kt` |
| R25 | In-app messaging only (email/SMS disabled) | `MessageRepository.kt` - no external service calls | No INTERNET permission in manifest |
| R26 | Trigger-based messages with templates | `domain/usecase/messaging/ManageMessagingUseCase.kt:sendTriggeredMessage()` | Template resolution logic |
| R27 | Quiet hours (9PM-7AM) and reminder cap (3/day) | `MessageRepository.kt:deliverReminder()` | `AppConfigTest.kt` - quiet hours config |
| R28 | Jetpack Compose UI | All screens in `ui/` package use @Composable | All screen files |
| R29 | Hilt DI | `di/` package modules, @HiltViewModel, @Inject | DI module files |
| R30 | SQLDelight encrypted SQLite | `data/local/DatabaseFactory.kt` + SQLCipher | Build.gradle dependency |
| R31 | Coroutines off main thread | All repository methods use `withContext(Dispatchers.IO)` | Repository files |
| R32 | WorkManager for deferred work | `worker/ReminderWorker.kt`, `SlaCheckWorker.kt`, `RuleEvaluationWorker.kt` | Worker files with scheduling |
| R33 | Indexed queries | All .sq files have CREATE INDEX statements | Schema files |
| R34 | Transactional critical writes | `AuditManager.kt:logWithTransaction()` used in all repositories | Repository audit calls |
| R35 | Actor/time traceability | AuditEvents table: actorId, actorRole, timestamp | Schema + AuditManager |
| R36 | Image downsampling + 20MB LRU cache | `AppConfig.IMAGE_LRU_CACHE_MB=20`, Coil with LRU | Config + Coil dependency |
| R37 | Android 10+ scoped storage | `minSdk=29` in build.gradle, scoped storage permissions | AndroidManifest.xml |
| R38 | Encrypted sensitive fields at rest | `security/EncryptionManager.kt` - AES-256-GCM via Keystore | EncryptionManager implementation |
| R39 | PII masked with agent-only reveal toggle | `AgentScreens.kt:togglePiiReveal()` + audit logging | UI + audit trail |
| R40 | First-run admin bootstrap | `security/AuthManager.kt:bootstrapAdmin()` | `LoginScreen.kt` -> bootstrap flow |
| R41 | Login lockout policy | `AuthManager.kt:login()` - 5 attempts, 30 min lockout | `AppConfigTest.kt` |
| R42 | Coupon usage limits (2 per user per 30 days) | `ConfigRepository.kt:validateCouponUsage()` | SQL query + config |
| R43 | No network dependency | No INTERNET permission, no remote API calls | AndroidManifest.xml |
