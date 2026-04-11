# Recheck Report for `.tmp/audit-report-1.md`

## Summary
- Rechecked all 10 prior findings against current code.
- Result: **7 fixed, 3 partially fixed, 0 unchanged fail**.

## Issue-by-issue Status

### 1) High - Authorization coverage gaps
- **Status: Fixed**
- Evidence:
  - Config read APIs now enforce RBAC: `repo/app/src/main/java/com/nutriops/app/domain/usecase/config/ManageConfigUseCase.kt:34-37`, `74-77`, `92-95`, `120-123`, `152-160`, `175-178`, `203-206`.
  - Admin user UI now goes through RBAC use case: `repo/app/src/main/java/com/nutriops/app/ui/admin/AdminUsersScreen.kt:31-34`, `45-47`, `56-58`.
  - Ticket read/list APIs now enforce role/object checks: `repo/app/src/main/java/com/nutriops/app/domain/usecase/ticket/ManageTicketUseCase.kt:168-228`.
  - Route-level role guards added: `repo/app/src/main/java/com/nutriops/app/ui/navigation/NavGraph.kt:20-42`, plus guarded routes throughout `:109-283`.

### 2) High - Missing minimum-duration rule enforcement
- **Status: Fixed**
- Evidence:
  - Minimum-duration hold logic implemented: `repo/app/src/main/java/com/nutriops/app/domain/usecase/rules/EvaluateRuleUseCase.kt:86-106`.
  - Hold state persistence added: `repo/app/src/main/sqldelight/com/nutriops/app/data/local/Rules.sq:68-84`.
  - Repository support methods added: `repo/app/src/main/java/com/nutriops/app/data/repository/RuleRepository.kt:165-177`.

### 3) High - Idle-only WorkManager scheduling not enforced
- **Status: Fixed**
- Evidence:
  - Idle constraint added: `repo/app/src/main/java/com/nutriops/app/NutriOpsApplication.kt:44-47`.
  - Applied to reminder/SLA/rule workers: `:50-53`, `:61-64`, `:72-74`.

### 4) High - Sensitive fields persisted plaintext
- **Status: Fixed**
- Evidence:
  - Ticket compensation columns migrated to text/encrypted storage intent: `repo/app/src/main/sqldelight/com/nutriops/app/data/local/Tickets.sq:16-17`.
  - Ticket repository now encrypts/decrypts compensation amounts: `repo/app/src/main/java/com/nutriops/app/data/repository/TicketRepository.kt:246-247`, `289-290`, `364-369`.
  - Order repository encrypts amount/notes writes: `repo/app/src/main/java/com/nutriops/app/data/repository/OrderRepository.kt:35-36`, `86`, `110`, decrypt helpers at `:128`, `:136`.

### 5) High - Image downsampling/LRU path missing
- **Status: Partially Fixed**
- Evidence:
  - LRU cache configured in Coil image loader: `repo/app/src/main/java/com/nutriops/app/di/ImageLoaderModule.kt:24-27`.
  - App wired to `ImageLoaderFactory`: `repo/app/src/main/java/com/nutriops/app/NutriOpsApplication.kt:18`, `23-24`, `32`.
  - But no explicit downsampling usage with `IMAGE_MAX_DIMENSION_PX`/decode sizing found in UI/image requests; constants remain unused: `repo/app/src/main/java/com/nutriops/app/config/AppConfig.kt:49-50`.

### 6) High - Test suite credibility issues (invalid test construction)
- **Status: Fixed**
- Evidence:
  - Invalid inheritance/null-cast patterns replaced with MockK mocks:
    - `repo/app/src/test/java/com/nutriops/app/domain/usecase/rules/EvaluateRuleUseCaseTest.kt:4`, `14`
    - `repo/app/src/test/java/com/nutriops/app/domain/usecase/profile/ManageProfileUseCaseTest.kt:6`, `11-13`
  - Added authorization integration tests: `repo/app/src/test/java/com/nutriops/app/domain/usecase/AuthorizationIntegrationTest.kt:21-184`.

### 7) Medium - Rule evaluation worker exists but not scheduled
- **Status: Fixed**
- Evidence:
  - Rule worker scheduled periodically: `repo/app/src/main/java/com/nutriops/app/NutriOpsApplication.kt:71-80`.

### 8) Medium - Config docs claim env-based config but runtime didn’t load
- **Status: Fixed**
- Evidence:
  - Runtime config initialization now loads env + manifest metadata: `repo/app/src/main/java/com/nutriops/app/config/AppConfig.kt:93-101`, `103-118`, `120-135`.

### 9) Medium - Preferred meal times not user-configurable
- **Status: Fixed**
- Evidence:
  - UI now collects selected meal times: `repo/app/src/main/java/com/nutriops/app/ui/enduser/UserProfileScreen.kt:99`, `167-181`.
  - Save flow now passes selected meal times: `repo/app/src/main/java/com/nutriops/app/ui/enduser/UserProfileScreen.kt:73`, `77-80`.

### 10) Medium - Lexicographic date comparison
- **Status: Fixed**
- Evidence:
  - Use-case date parsing via `LocalDate.parse`: `repo/app/src/main/java/com/nutriops/app/domain/usecase/learningplan/ManageLearningPlanUseCase.kt:43-44`.
  - Repository parsing applied in learning/config modules: 
    - `repo/app/src/main/java/com/nutriops/app/data/repository/LearningPlanRepository.kt:37-38`
    - `repo/app/src/main/java/com/nutriops/app/data/repository/ConfigRepository.kt:153-154`.

## Additional Note
- Evidence upload UI is now present for **text evidence** in both Agent and End User ticket flows:
  - `repo/app/src/main/java/com/nutriops/app/ui/agent/AgentScreens.kt:302`, `332-359`
  - `repo/app/src/main/java/com/nutriops/app/ui/enduser/UserTicketsScreen.kt:144-145`, `198-224`
- Image evidence methods exist in viewmodels (`addImageEvidence`) but no clear image-picker wiring found in current UI; this is why image handling remains only partially complete from static evidence.
