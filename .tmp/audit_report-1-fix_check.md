# Recheck of `.tmp/audit-report-1-recheck.md`

## Verdict
- Re-verified all 10 tracked issues against current code.
- **Current status: 10 fixed, 0 partial, 0 unresolved.**

## Findings

### 1) Authorization coverage gaps
- **Status: Fixed**
- Fresh evidence:
  - RBAC checks on config reads/writes: `repo/app/src/main/java/com/nutriops/app/domain/usecase/config/ManageConfigUseCase.kt:34-37`, `74-77`, `92-95`, `120-123`, `152-160`, `175-178`, `203-206`
  - Admin user UI uses RBAC use case (no direct repo bypass): `repo/app/src/main/java/com/nutriops/app/ui/admin/AdminUsersScreen.kt:31-34`, `45-47`, `56-58`
  - Ticket read/list ownership and role checks: `repo/app/src/main/java/com/nutriops/app/domain/usecase/ticket/ManageTicketUseCase.kt:168-228`
  - Route-level role guards present: `repo/app/src/main/java/com/nutriops/app/ui/navigation/NavGraph.kt:20-42`, guarded routes `:109-283`

### 2) Minimum-duration rule enforcement missing
- **Status: Fixed**
- Fresh evidence:
  - Duration hold logic implemented: `repo/app/src/main/java/com/nutriops/app/domain/usecase/rules/EvaluateRuleUseCase.kt:86-106`
  - Hold storage + queries: `repo/app/src/main/sqldelight/com/nutriops/app/data/local/Rules.sq:68-84`
  - Repository methods wired: `repo/app/src/main/java/com/nutriops/app/data/repository/RuleRepository.kt:165-177`

### 3) Idle-only WorkManager scheduling
- **Status: Fixed**
- Fresh evidence:
  - Idle constraint configured: `repo/app/src/main/java/com/nutriops/app/NutriOpsApplication.kt:44-47`
  - Applied to reminder/SLA/rule workers: `:50-53`, `:61-64`, `:72-74`

### 4) Sensitive fields plaintext at rest
- **Status: Fixed**
- Fresh evidence:
  - Ticket compensation fields switched to text storage: `repo/app/src/main/sqldelight/com/nutriops/app/data/local/Tickets.sq:16-17`
  - Ticket encryption/decryption in repository: `repo/app/src/main/java/com/nutriops/app/data/repository/TicketRepository.kt:246-247`, `289-290`, `364-369`
  - Order amount/notes encrypted in repository: `repo/app/src/main/java/com/nutriops/app/data/repository/OrderRepository.kt:35-36`, `86`, `110`, `128`, `136`

### 5) Image downsampling/LRU path
- **Status: Fixed**
- Fresh evidence:
  - LRU memory cache configured: `repo/app/src/main/java/com/nutriops/app/di/ImageLoaderModule.kt:59-63`
  - Global downsample interceptor with max dimension cap: `repo/app/src/main/java/com/nutriops/app/di/ImageLoaderModule.kt:22-25`, `26-48`, `73-75`
  - Reusable downsampled image composable: `repo/app/src/main/java/com/nutriops/app/ui/common/CommonComponents.kt:108-136`
  - Image picker + image evidence upload wired in both roles:
    - End user: `repo/app/src/main/java/com/nutriops/app/ui/enduser/UserTicketsScreen.kt:118-129`, `166-167`
    - Agent: `repo/app/src/main/java/com/nutriops/app/ui/agent/AgentScreens.kt:274-283`, `337`, `350-354`

### 6) Test credibility issues (invalid construction)
- **Status: Fixed**
- Fresh evidence:
  - MockK-based repository mocking in prior problematic tests:
    - `repo/app/src/test/java/com/nutriops/app/domain/usecase/rules/EvaluateRuleUseCaseTest.kt:4`, `14`
    - `repo/app/src/test/java/com/nutriops/app/domain/usecase/profile/ManageProfileUseCaseTest.kt:6`, `11-13`
  - Authorization integration tests present: `repo/app/src/test/java/com/nutriops/app/domain/usecase/AuthorizationIntegrationTest.kt:21-184`

### 7) Rule worker not scheduled
- **Status: Fixed**
- Fresh evidence:
  - Rule worker periodic scheduling: `repo/app/src/main/java/com/nutriops/app/NutriOpsApplication.kt:71-80`

### 8) Config env/metadata loading mismatch
- **Status: Fixed**
- Fresh evidence:
  - Initialize now loads environment + manifest metadata: `repo/app/src/main/java/com/nutriops/app/config/AppConfig.kt:93-101`, `103-118`, `120-135`

### 9) Preferred meal times not user-configurable
- **Status: Fixed**
- Fresh evidence:
  - Meal-time selection state/UI: `repo/app/src/main/java/com/nutriops/app/ui/enduser/UserProfileScreen.kt:99`, `167-181`
  - Selected meal times passed to use case: `repo/app/src/main/java/com/nutriops/app/ui/enduser/UserProfileScreen.kt:73`, `77-80`

### 10) Lexicographic date comparisons
- **Status: Fixed**
- Fresh evidence:
  - Use-case date parsing: `repo/app/src/main/java/com/nutriops/app/domain/usecase/learningplan/ManageLearningPlanUseCase.kt:43-44`
  - Repository date parsing:
    - `repo/app/src/main/java/com/nutriops/app/data/repository/LearningPlanRepository.kt:37-38`
    - `repo/app/src/main/java/com/nutriops/app/data/repository/ConfigRepository.kt:153-154`

## Notes
- This is a static recheck only; no runtime execution was used.
