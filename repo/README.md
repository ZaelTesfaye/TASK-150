Project Type: android

# NutriOps -- Offline Operations & Meal Planning

A fully offline Android application for field teams and consumers providing
on-device nutrition planning and service operations without any network
dependency.

> **Environment Policy:** All build and test steps are Docker-contained.
> Emulator/device verification requires host ADB and emulator tooling. No
> host JDK, Android SDK, or Gradle is needed for CI; the only host
> dependencies are the emulator/device tools listed below, used exclusively
> for optional manual verification.

---

## Host Prerequisites (Emulator/Device Only)

Only required if you intend to install the app onto an AVD or a physical
device for manual verification. The Docker image covers every build and
test workflow without them:

| Tool         | Purpose                                                  |
|--------------|----------------------------------------------------------|
| `adb`        | Install the APK and launch the app on a device / emulator |
| `emulator`   | Boot a local AVD                                          |
| `avdmanager` | One-time AVD creation (`NutriOps_AVD`)                    |

All three ship with the Android SDK `platform-tools` + `emulator` packages.
If you only plan to run the CI/Docker test suite you can skip this section
entirely.

---

## Prerequisites

| Target                   | Host tool required                                              |
|--------------------------|-----------------------------------------------------------------|
| Build & test (canonical) | Docker 20+ (with the Compose plugin or legacy `docker-compose`) |
| Emulator / device deploy | Host `adb`, `emulator`, `avdmanager` (see section above)        |

The bundled `Dockerfile` + `docker-compose.yml` provide JDK 17,
Android SDK 34, and Gradle inside the container so the build and test suite
need nothing else on the host. The reviewer uses the host Android tools only
for the optional install/launch step described under "Running the App".

---

## Running the App

Two paths are defined for this repository. They are strictly separated --
the CI/Docker path is the authoritative gate, the Local/Manual path is only
for manual verification and **must not** be followed by strict CI
reviewers:

| Path                                                          | When to use                                   | Host tooling required              |
|---------------------------------------------------------------|-----------------------------------------------|------------------------------------|
| [**CI / Docker Path (Canonical)**](#ci--docker-path-canonical) | Every build/test workflow, every CI run        | Docker only                        |
| [**Local / Manual Path (Optional)**](#local--manual-path-optional) | Manual UI verification on an emulator/device  | Host `adb`, `emulator`, `avdmanager` |

---

## CI / Docker Path (Canonical)

> **Audience:** every reviewer, every CI system. This is the single
> authoritative gate. No host Android SDK tooling is used.

```bash
# Build the project and run the full unit + integration test suite
docker-compose up --build

# Or via the canonical test script (same thing, with a pass/fail summary)
./run_tests.sh
```

Everything happens inside the container: JDK 17, Android SDK 34, Gradle,
SQLDelight codegen, and the entire unit + integration suite. Test reports
land under `app/build/reports` via the bound volume. If this command
passes, the suite is green.

To produce the debug APK (used by the optional Local/Manual path below):

```bash
docker-compose run --rm nutriops gradle :app:assembleDebug --no-daemon
# APK produced at: app/build/outputs/apk/debug/app-debug.apk (inside the bound volume)
```

No local Gradle, JDK, or SDK is invoked -- everything runs inside the image.

---

## Local / Manual Path (Optional)

> **Audience:** developers or reviewers who want to install the APK onto an
> emulator or attached device and drive the live UI. This path is **not**
> exercised by CI, is **not** authoritative, and every command below
> requires the host tools listed under "Host Prerequisites (Emulator/Device
> Only)" at the top of this README. Skip this section entirely if you are
> only validating the canonical test suite.

**1. Create the AVD (one-time).**

```bash
avdmanager create avd -n NutriOps_AVD \
  -k "system-images;android-34;google_apis;x86_64" \
  -d "pixel_6"
```

**2. Boot the emulator and install the Docker-built APK.**

```bash
# Start AVD
emulator -avd NutriOps_AVD &
adb wait-for-device

# Install the APK produced by the Docker build
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Launch the app
adb shell am start -n com.nutriops.app.debug/com.nutriops.app.MainActivity
```

**3. Drive the app end-to-end.** Once the emulator is booted and the APK
is installed, a reviewer steps through the key flows:

1. **Bring the emulator surface into view.** Pick whichever path fits
   your workstation:
   - *Android Studio:* open `Tools -> Device Manager`, double-click
     `NutriOps_AVD`. The emulator window attaches to your local `adb`
     daemon.
   - *Standalone `emulator` binary:* the command above launches a window
     directly.
   - *Headless host (e.g. Docker-hosted sandbox):* stream the emulator
     via `scrcpy --tcpip=<host>:5555` or a VNC bridge exposed by the
     sandbox. Both attach over ADB and require no app changes.
2. **Verify app launch.** On a fresh install the app opens on the
   `First-Time Setup` screen; on subsequent launches it opens on `Login`.
3. **Bootstrap the administrator** using the credentials from the "Demo
   Credentials" table below. You are redirected to the `Administrator
   Dashboard`.
4. **Register an End User** (from Login -> "Create an account") or
   create an Agent from `Admin -> Manage Users`. Log in as that role to
   exercise the role-specific navigation graph.
5. **Run one primary flow per role.** For example, as End User open
   `My Profile` -> save any goal, then `Weekly Meal Plan` -> generate.
   As Admin, open `Audit` and confirm every action above is recorded.

A successful walk-through is the definition of "the app works."

### Running All Tests

The canonical test suite is always the Docker command in the CI / Docker
path above (`./run_tests.sh`). The commands below are the optional
instrumented layer that runs on an attached emulator -- they are
supplemental to, not a replacement for, the canonical gate. Execute them
in sequence from the project root:

```bash
# 1. Canonical unit + integration tests (Docker, no emulator needed)
./run_tests.sh

# 2. Boot the emulator (see "Local / Manual Path (Optional)" above for AVD setup)
emulator -avd NutriOps_AVD &
adb wait-for-device

# 3. Build the debug + androidTest APKs inside Docker
docker-compose run --rm nutriops gradle :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon

# 4. Install both APKs onto the running emulator
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

# 5. Run the instrumented E2E flows
adb shell am instrument -w -e package com.nutriops.app.e2e \
  com.nutriops.app.debug.test/com.nutriops.app.di.HiltTestRunner
```

Step 1 is the authoritative CI gate. Steps 2-5 are the instrumented layer
and are optional on a machine without an emulator -- the unit +
integration suite already covers every production path; the E2E flows
exercise the live navigation graph on a real device as additional
coverage.

---

## Demo Credentials

On first launch the app shows a **First-Time Setup** screen that lets you
create the first Administrator account. Administrators then create Agent
accounts; End Users can self-register from the login screen.

Use the credentials below when verifying flows manually:

| Role          | Username         | Password        | How created                        |
|---------------|------------------|-----------------|------------------------------------|
| Administrator | `admin`          | `AdminPass1!`   | Bootstrap screen on first launch   |
| Agent         | `agent`          | `AgentPass1!`   | Admin -> Manage Users -> Create Agent |
| End User      | `user`           | `UserPass1!`    | Login screen -> "Create an account"  |

Password policy: minimum 8 characters. Five failed login attempts lock the
account for `LOCKOUT_DURATION_MINUTES` (default 30 minutes).

---

## Architecture

### Stack

| Component  | Technology                                     |
|------------|------------------------------------------------|
| UI         | Jetpack Compose (Material 3)                   |
| DI         | Hilt                                           |
| Database   | SQLDelight + SQLCipher (encrypted at rest)     |
| Async      | Kotlin Coroutines                              |
| Background | WorkManager                                    |
| Min SDK    | Android 10 (API 29)                            |

### Layer Structure

```
app/src/main/java/com/nutriops/app/
|-- config/          # Centralized configuration (single source of truth)
|-- logging/         # Structured logging with PII redaction
|-- security/        # Auth, RBAC, encryption, password hashing
|-- audit/           # Immutable append-only audit trail
|-- data/
|   |-- local/       # SQLDelight database factory
|   +-- repository/  # Repository layer with transactional writes
|-- domain/
|   |-- model/       # Enums, status machines, domain types
|   +-- usecase/     # Business logic use cases
|-- di/              # Hilt dependency injection modules
|-- worker/          # WorkManager background workers
+-- ui/              # Jetpack Compose screens per role
    |-- auth/        # Login, first-run bootstrap, register
    |-- admin/       # Administrator dashboard, config, rules
    |-- agent/       # Agent ticket management
    +-- enduser/     # End user plans, meals, messages
```

---

## Roles & Flows

### Administrator
- Manage users (create Agents, End Users)
- Operations & Configuration Center (homepage modules, ad slots, campaigns,
  coupons, black/whitelist, purchase limits)
- Config versioning with 10% canary rollout (deterministic assignment)
- Metrics & Rules Engine (compound conditions, hysteresis, back-calculation)
- View immutable audit trail

### Agent
- Ticket management (delay/dispute/lost-item)
- SLA tracking (4 business hour first response, 3 day resolution)
- Evidence review (image/text only)
- Compensation workflow (auto-approve <= $10, agent approval > $10)
- PII reveal with audit logging

### End User
- Profile creation (age range, dietary pattern, allergies, goals)
- Weekly meal plan generation with explainable recommendations
  (>= 2 reasons per meal)
- One-click meal swaps within tolerance (+/-10% calories, +/-5g protein)
- Learning plan lifecycle (NOT_STARTED -> IN_PROGRESS -> PAUSED -> COMPLETED
  -> ARCHIVED)
- Duplicate-before-edit for completed plans
- In-app messages and reminders (quiet hours 9 PM - 7 AM, max 3/day)
- Support ticket creation

---

## Security

- **Authentication:** PBKDF2WithHmacSHA256 password hashing; account lockout
  after 5 failed attempts (30 minutes).
- **Authorization:** Role-Based Access Control (RBAC) with object-level
  ownership checks (BOLA/IDOR protection).
- **Encryption:** SQLCipher database encryption at rest; Android Keystore for
  field-level encryption of compensation amounts and order notes.
- **Audit:** Append-only audit trail (no UPDATE/DELETE) with actor ID and
  timestamp on every critical write.
- **PII:** Masked by default in the UI; agent-only reveal toggle logged to
  audit.
- **Logging:** Automatic redaction of passwords, tokens, SSNs and sensitive
  fields.

---

## Configuration

All configuration is centralized in `AppConfig.kt`. Environment variables are
defined in `docker-compose.yml`:

| Variable                      | Default                         | Description                       |
|-------------------------------|---------------------------------|-----------------------------------|
| `ENABLE_TLS`                  | `false`                         | TLS toggle                        |
| `DB_ENCRYPTION_KEY`           | *(generated via Keystore)*      | Database encryption key           |
| `MAX_LOGIN_ATTEMPTS`          | `5`                             | Failed login threshold            |
| `LOCKOUT_DURATION_MINUTES`    | `30`                            | Account lockout duration          |
| `SLA_FIRST_RESPONSE_HOURS`    | `4`                             | Business hours for first response |
| `SLA_RESOLUTION_DAYS`         | `3`                             | Business days for resolution      |
| `QUIET_HOURS_START`           | `21:00`                         | Reminder quiet period start       |
| `QUIET_HOURS_END`             | `07:00`                         | Reminder quiet period end         |
| `MAX_REMINDERS_PER_DAY`       | `3`                             | Daily reminder cap                |
| `CANARY_PERCENTAGE`           | `10`                            | Canary rollout percentage         |
| `IMAGE_LRU_CACHE_MB`          | `20`                            | Image cache size limit            |
| `COMPENSATION_AUTO_APPROVE_MAX` | `10`                          | Auto-approve threshold            |

---

## Database

SQLDelight schema files in `app/src/main/sqldelight/`:
- All tables with constraints, CHECK clauses and foreign keys.
- Composite indexes: `(userId, date)` for plans, `(status, updatedAt)` for
  tickets.
- `AuditEvents` table: INSERT only, no UPDATE/DELETE queries.

---

## Testing

The canonical test path is `./run_tests.sh` (Docker). The suite covers:

- **Security:** Password hashing, RBAC permission matrix, authorization
  checks, end-to-end AuthManager lifecycle, EncryptionManager round-trip,
  DatabaseKeyManager rotation.
- **Use cases:** Ticket, config, messaging, user-management, learning-plan,
  meal-plan generation, meal-swap, rule evaluation -- against an in-memory
  SQLDelight database with real AES-GCM encryption (no mock at the crypto
  boundary).
- **Repositories:** Every repository has a direct integration test against
  an in-memory SQLDelight database (`app/src/test/.../integration_tests/`).
- **State machines:** Learning plan transitions, ticket status transitions.
- **Business logic:** Macro calculations, rule engine evaluation, compound
  conditions, hysteresis, back-calculation.
- **Workers:** `ReminderWorker`, `SlaCheckWorker`, `RuleEvaluationWorker`
  via `TestListenableWorkerBuilder` against real in-memory repositories.
- **UI:** Host-side Compose tests for login, dashboards, nav-graph route
  resolution, admin, agent, and end-user screens (Robolectric); integration
  counterparts for login and admin-user-management screens wire real use
  cases.
- **ViewModels:** AuthViewModel with coroutine test dispatcher.
- **DI graph validation:** `ModuleBindingsTest` (JVM-side, direct `@Provides`
  exercise) plus `DiModuleValidationTest` (instrumented `@HiltAndroidTest`
  with behavioral assertions over the live component).

Instrumented E2E flow tests (LoginFlow, MealPlanFlow,
AdminUserManagementFlow, TicketFlow, TicketLifecycleFlow, MainActivity)
live in `app/src/androidTest/`. They are the optional layer documented in
the [**Running All Tests**](#running-all-tests) subsection above and
require an attached emulator.

---

## Offline Constraints

- No INTERNET permission in manifest.
- No remote API calls, cloud storage or server-side logic.
- All data stored locally in encrypted SQLite.
- WorkManager for background tasks (reminders, SLA checks, rule evaluation).
- Android 10+ scoped storage for evidence images.
- Images downsampled on load with a 20 MB LRU cache.
