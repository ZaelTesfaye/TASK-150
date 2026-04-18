# Test Coverage Audit

## Scope and Constraints

- Audit mode: static inspection only.
- Not executed: code, tests, scripts, Docker, package managers, servers.
- Inspection scope: only files required for endpoint detection, test classification, and README compliance evidence.

## Project Type Detection

- Declared at top of README: `Project Type: android` (`repo/README.md:1`).
- Inference check: Android-only module structure (`repo/settings.gradle.kts:16` includes only `:app`) and Android manifest (`repo/app/src/main/AndroidManifest.xml:1`).
- Final project type: **android**.

## Backend Endpoint Inventory

Definition enforced: endpoint = unique `METHOD + fully resolved PATH`.

Discovery result:

- **No backend HTTP endpoints exist in this repository.**

Evidence:

- No server/controller route definitions in production code (`repo/app/src/main/**`) for common API patterns (`@GetMapping`, `@PostMapping`, `@RequestMapping`, Ktor `routing {}`, Express/Fastify/Nest bootstraps).
- README states offline architecture and no remote API calls (`repo/README.md`, Offline Constraints section).
- Manifest explicitly documents no INTERNET permission (`repo/app/src/main/AndroidManifest.xml:5`).

### Backend Endpoint Inventory Table

| Endpoint (METHOD + PATH) | Covered | Notes                                          |
| ------------------------ | ------- | ---------------------------------------------- |
| None discovered          | N/A     | No backend HTTP surface detected in repository |

## API Test Mapping Table

| Endpoint                       | Covered | Test Type | Test Files | Evidence                                                |
| ------------------------------ | ------- | --------- | ---------- | ------------------------------------------------------- |
| None (no HTTP endpoints exist) | N/A     | N/A       | N/A        | No tests sending HTTP requests to app routes were found |

## API Test Classification

1. True No-Mock HTTP: **0**
2. HTTP with Mocking: **0**
3. Non-HTTP (unit/integration/UI/instrumented): **96 test files**

File-count evidence:

- JVM tests: 88 files matching `repo/app/src/test/**/*Test.kt`.
- Instrumented tests: 8 files matching `repo/app/src/androidTest/**/*Test.kt`.

Representative non-HTTP evidence:

- `repo/app/src/test/java/com/nutriops/app/integration_tests/data/repository/UserRepositoryTest.kt` (`create and retrieve by id returns equal data with correct role`) uses in-memory SQLDelight DB.
- `repo/app/src/test/java/com/nutriops/app/ui/auth/LoginScreenIntegrationTest.kt` wires real `LoginUseCase` and `AuthManager` (no mocks between UI and persistence).
- `repo/app/src/androidTest/java/com/nutriops/app/e2e/LoginFlowTest.kt` uses Compose instrumentation on real `MainActivity`.

## Mock Detection (Strict)

Mocks/stubs detected (partial list, evidence-backed):

- `repo/app/src/test/java/com/nutriops/app/ui/auth/LoginScreenTest.kt`
  - WHAT mocked: `LoginUseCase`, `AuthManager`
  - WHERE: `buildViewModel()`
  - Type: non-HTTP UI unit test with mocking
- `repo/app/src/test/java/com/nutriops/app/unit_tests/domain/usecase/auth/LoginUseCaseTest.kt`
  - WHAT mocked: `AuthManager`
  - WHERE: `setup()` with `mockk(relaxed = true)`
  - Type: non-HTTP unit test with mocking
- `repo/app/src/test/java/com/nutriops/app/unit_tests/domain/usecase/ticket/ManageTicketUseCaseTest.kt`
  - WHAT mocked: `TicketRepository`, `MessageRepository`, domain entities in multiple tests
  - WHERE: `setup()` and test bodies (`coEvery`, `mockk`)
  - Type: non-HTTP unit test with mocking
- `repo/app/src/test/java/com/nutriops/app/ui/admin/AdminUsersScreenTest.kt`
  - WHAT mocked: `ManageUsersUseCase`, `AuthManager`
  - WHERE: test setup variables using `mockk(relaxed = true)`
  - Type: non-HTTP UI unit test with mocking

No HTTP transport mocking evidence was found because no HTTP API tests/endpoints were found.

## Coverage Summary

- Total endpoints: **0**
- Endpoints with HTTP tests: **0**
- Endpoints with TRUE no-mock HTTP tests: **0**
- HTTP coverage %: **N/A** (no endpoints)
- True API coverage %: **N/A** (no endpoints)

## Unit Test Summary

### Backend Unit Tests

Modules covered (evidence samples):

- Controllers: **N/A** (no backend controllers)
- Services / use cases:
  - `repo/app/src/test/java/com/nutriops/app/unit_tests/domain/usecase/auth/LoginUseCaseTest.kt`
  - `repo/app/src/test/java/com/nutriops/app/unit_tests/domain/usecase/config/ManageConfigUseCaseTest.kt`
  - `repo/app/src/test/java/com/nutriops/app/unit_tests/domain/usecase/learningplan/ManageLearningPlanUseCaseTest.kt`
- Repositories:
  - `repo/app/src/test/java/com/nutriops/app/integration_tests/data/repository/UserRepositoryTest.kt`
  - `repo/app/src/test/java/com/nutriops/app/integration_tests/data/repository/TicketRepositoryTest.kt`
  - `repo/app/src/test/java/com/nutriops/app/integration_tests/data/repository/ConfigRepositoryTest.kt`
- Auth/guards/middleware equivalent (security managers):
  - `repo/app/src/test/java/com/nutriops/app/unit_tests/security/AuthManagerTest.kt`
  - `repo/app/src/test/java/com/nutriops/app/unit_tests/security/RbacManagerTest.kt`
  - `repo/app/src/test/java/com/nutriops/app/unit_tests/security/PasswordHasherTest.kt`

Important backend modules NOT tested:

- **No critical backend HTTP module exists**; no obvious untested critical domain/security/repository component was identified from file-level evidence.

### Frontend Unit Tests (Strict Requirement)

Strict rule trigger:

- Project type is `android` (not `fullstack`/`web`), so web-frontend strict gate is not mandatory.

Frontend test files (mobile UI) evidence:

- `repo/app/src/test/java/com/nutriops/app/ui/auth/LoginScreenTest.kt`
- `repo/app/src/test/java/com/nutriops/app/ui/admin/AdminDashboardScreenTest.kt`
- `repo/app/src/test/java/com/nutriops/app/ui/enduser/UserDashboardScreenTest.kt`

Frameworks/tools detected:

- JUnit4, Robolectric, Jetpack Compose UI test APIs, AndroidX test instrumentation, Hilt test runner.

Components/modules covered:

- Auth screens and view model
- Admin screens
- Agent screens
- End-user screens
- Navigation route logic

Important frontend components/modules NOT tested:

- No clearly critical untested screen/module identified from sampled test inventory; broad screen coverage is present by naming and test distribution.

Mandatory verdict:

- **Frontend unit tests: PRESENT**

### Cross-Layer Observation

- Both app logic and UI layers are tested.
- No FE↔BE HTTP boundary exists in this architecture; balance concern is not applicable.

## API Observability Check

- Endpoint+request+response observability in API tests: **Not applicable** (no API endpoint tests).
- For non-HTTP tests, assertions are explicit on UI state, domain results, DB rows, and auth state (example: `LoginScreenIntegrationTest` verifies role and authenticated session after login).

## Test Quality & Sufficiency

Strengths:

- Real integration coverage against in-memory SQLDelight in repository/use-case tests.
- Security-critical logic tested (auth, RBAC, password hashing, encryption).
- UI integration tests include real use case wiring (`LoginScreenIntegrationTest`).
- Instrumented E2E flows exist under `app/src/androidTest/e2e`.

Risks / weaknesses:

- High mock density in many unit/UI tests can hide seam-level regressions.
- API-level coverage metrics cannot be used because there is no HTTP API surface.

`run_tests.sh` check:

- `repo/run_tests.sh` is Docker-based canonical path (meets requirement).
- `repo/scripts/dev/run_tests_local.sh` uses local Gradle/JDK and is explicitly non-canonical; this is documented.

## End-to-End Expectations

- Fullstack FE↔BE E2E expectation: not applicable (project is android).
- Mobile end-to-end expectation: partially satisfied via instrumented Compose flows (`LoginFlowTest`, `MealPlanFlowTest`, `TicketFlowTest`, etc.).

## Tests Check

- Static-only constraint respected: **Yes**.
- Endpoint extraction completed: **Yes (result = 0 HTTP endpoints)**.
- API mapping table completed: **Yes (N/A due no endpoints)**.
- Mock detection completed with evidence: **Yes**.

## Test Coverage Score (0-100)

- **90/100**

## Score Rationale

- Positive: broad and deep non-HTTP coverage across domain, repositories, security, workers, UI, and instrumentation.
- Negative: mock-heavy unit/UI suite in multiple files; no API layer to validate via true HTTP tests (architectural constraint).

## Key Gaps

1. No HTTP API surface; endpoint-based API coverage KPIs are structurally unavailable.
2. Mock-heavy tests around use-case and UI seams increase residual integration risk.

## Confidence & Assumptions

- Confidence: **High** for endpoint absence and test-type classification.
- Assumptions:
  - Audit based solely on files inside this repository workspace.
  - No hidden/generated backend server module outside inspected tree.

## Test Coverage Verdict

- **PASS (Android architecture, strong non-HTTP coverage, with explicit mock-density caution).**

---

# README Audit

## README Location

- Required file exists: `repo/README.md`.

## Hard Gates

### Formatting

- PASS: structured markdown, tables, headings, and command blocks are clear and readable.

### Startup Instructions

Android requirement: build + emulator/device steps must be present.

- PASS.
- Evidence:
  - Docker build/test startup includes `docker-compose up --build` and `./run_tests.sh`.
  - Emulator/device setup and launch commands are documented (`avdmanager`, `emulator`, `adb install`, `adb shell am start`).

### Access Method

- PASS.
- README provides concrete app access/launch instructions for emulator/device and describes expected first screen behavior.

### Verification Method

- PASS.
- README includes explicit verification flows and checkpoints (bootstrap/login/dashboard, role-based flows, audit verification).

### Environment Rules (Strict)

Rule: no runtime package installs/manual DB setup; Docker-contained workflow expected.

- PASS (strict with note).
- Canonical build/test path is Docker-contained and explicitly designated authoritative.
- Optional manual emulator path exists and requires host Android tools, but it is clearly marked optional/non-canonical.

### Demo Credentials (Conditional)

Auth exists? **Yes** (evidence: `repo/app/src/main/java/com/nutriops/app/security/AuthManager.kt`).

- PASS.
- README provides credentials for all roles:
  - Administrator
  - Agent
  - End User

## Engineering Quality

- Tech stack clarity: strong.
- Architecture explanation: strong and concrete.
- Testing instructions: strong separation of canonical vs optional paths.
- Security/roles/workflows: clearly documented.
- Presentation quality: high.

## High Priority Issues

- None.

## Medium Priority Issues

1. Canonical and optional execution paths are both extensive; skimming readers may still follow optional path first despite warnings.

## Low Priority Issues

1. Presence of local non-canonical runner script can still invite accidental misuse outside CI context.

## Hard Gate Failures

- None.

## README Verdict

- **PASS**

---

## Final Combined Verdicts

1. **Test Coverage Audit Verdict: PASS**
2. **README Audit Verdict: PASS**
