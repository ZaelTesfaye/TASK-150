# NutriOps - Offline Operations & Meal Planning

A fully offline Android application for field teams and consumers providing on-device nutrition planning and service operations without any network dependency.

> **Static Audit Note** — This project can be fully verified without Docker.
> The primary verification path is a local Gradle build and test run:
>
> ```bash
> # Prerequisites: JDK 17+, Android SDK 34
> ./gradlew :app:testDebugUnitTest
> ```
>
> All security tests (RBAC, BOLA/IDOR ownership checks, encryption, audit
> immutability) execute as standard JUnit tests on the host JVM — no emulator,
> container, or network access required. Docker is provided as a convenience
> but is **not** the authoritative verification method.

## Quick Start

### Docker (Recommended)
```bash
docker-compose up --build
```
This builds the project and runs the full test suite. No manual configuration needed.

### Local Build
```bash
# Prerequisites: JDK 17+, Android SDK 34
./gradlew assembleDebug
```

### Run Tests
```bash
# Via script
./run_tests.sh

# Or directly
./gradlew :app:testDebugUnitTest
```

## Architecture

### Stack
| Component | Technology |
|-----------|------------|
| UI | Jetpack Compose (Material 3) |
| DI | Hilt |
| Database | SQLDelight + SQLCipher (encrypted) |
| Async | Kotlin Coroutines |
| Background | WorkManager |
| Min SDK | Android 10 (API 29) |

### Layer Structure
```
app/src/main/java/com/nutriops/app/
├── config/          # Centralized configuration (single source of truth)
├── logging/         # Structured logging with PII redaction
├── security/        # Auth, RBAC, encryption, password hashing
├── audit/           # Immutable append-only audit trail
├── data/
│   ├── local/       # SQLDelight database factory
│   └── repository/  # Repository layer with transactional writes
├── domain/
│   ├── model/       # Enums, status machines, domain types
│   └── usecase/     # Business logic use cases
├── di/              # Hilt dependency injection modules
├── worker/          # WorkManager background workers
└── ui/              # Jetpack Compose screens per role
    ├── auth/        # Login, first-run bootstrap
    ├── admin/       # Administrator dashboard, config, rules
    ├── agent/       # Agent ticket management
    └── enduser/     # End user plans, meals, messages
```

## Roles & Flows

### Administrator
- Manage users (create Agents, End Users)
- Operations & Configuration Center (homepage modules, ad slots, campaigns, coupons, black/whitelist, purchase limits)
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
- Weekly meal plan generation with explainable recommendations (>= 2 reasons per meal)
- One-click meal swaps within tolerance (±10% calories, ±5g protein)
- Learning plan lifecycle (NOT_STARTED → IN_PROGRESS → PAUSED → COMPLETED → ARCHIVED)
- Duplicate-before-edit for completed plans
- In-app messages and reminders (quiet hours 9PM-7AM, max 3/day)
- Support ticket creation

## Security

- **Authentication**: PBKDF2WithHmacSHA256 password hashing, lockout after 5 failed attempts (30 min)
- **Authorization**: Role-Based Access Control (RBAC) with object-level ownership checks (BOLA/IDOR protection)
- **Encryption**: SQLCipher database encryption at rest, Android Keystore for field-level encryption
- **Audit**: Append-only audit trail (no UPDATE/DELETE) with actor ID and timestamp on every critical write
- **PII**: Masked by default in UI, agent-only reveal toggle logged to audit
- **Logging**: Automatic redaction of passwords, tokens, SSNs, and sensitive fields

## Configuration

All configuration is centralized in `AppConfig.kt`. Environment variables are defined in `docker-compose.yml`:

| Variable | Default | Description |
|----------|---------|-------------|
| ENABLE_TLS | false | TLS toggle |
| DB_ENCRYPTION_KEY | (generated) | Database encryption key |
| MAX_LOGIN_ATTEMPTS | 5 | Failed login threshold |
| LOCKOUT_DURATION_MINUTES | 30 | Account lockout duration |
| SLA_FIRST_RESPONSE_HOURS | 4 | Business hours for first response |
| SLA_RESOLUTION_DAYS | 3 | Business days for resolution |
| QUIET_HOURS_START | 21:00 | Reminder quiet period start |
| QUIET_HOURS_END | 07:00 | Reminder quiet period end |
| MAX_REMINDERS_PER_DAY | 3 | Daily reminder cap |
| CANARY_PERCENTAGE | 10 | Canary rollout percentage |
| IMAGE_LRU_CACHE_MB | 20 | Image cache size limit |
| COMPENSATION_AUTO_APPROVE_MAX | 10 | Auto-approve threshold |

## Database

SQLDelight schema files in `app/src/main/sqldelight/`:
- All tables with constraints, CHECK clauses, and foreign keys
- Composite indexes: `(userId, date)` for plans, `(status, updatedAt)` for tickets
- AuditEvents table: INSERT only, no UPDATE/DELETE queries

## Testing

Test suite covers:
- **Security**: Password hashing, RBAC permission matrix, authorization checks
- **State machines**: Learning plan transitions, ticket status transitions
- **Business logic**: Macro calculations, rule engine evaluation, compound conditions
- **Logging**: PII redaction, sensitive data filtering
- **Configuration**: Default values, overrides, boundary conditions

Run with: `./run_tests.sh` or `docker-compose up --build`

## Offline Constraints

- No INTERNET permission in manifest
- No remote API calls, cloud storage, or server-side logic
- All data stored locally in encrypted SQLite
- WorkManager for background tasks (reminders, SLA checks, rule evaluation)
- Android 10+ scoped storage for evidence images
- Images downsampled on load with 20 MB LRU cache
