# NutriOps System Design

## Architecture Overview

NutriOps is a fully offline Android application designed for field teams and consumers providing nutrition planning and service operations without any network dependency. The system is built as a single-device application with complete autonomy, using encrypted local storage and a comprehensive role-based access control (RBAC) model.

### System Purpose

The NutriOps application provides three distinct user experiences:

1. **Administrator Portal** — Operations center for configuration, user management, rules engine, audit trail review, and metrics
2. **Agent Portal** — Ticket management for service exceptions (delays, disputes, lost items) with SLA tracking and compensation workflows
3. **End User Portal** — Self-service meal planning, learning plan management, and support ticket creation

All operations occur entirely on-device with no external network calls required.

## Layered Architecture

The system follows a clean layered architecture with clear separation of concerns:

```
┌─────────────────────────────────────────┐
│               UI Layer                   │
│  (Jetpack Compose - Screen per Role)    │
├─────────────────────────────────────────┤
│           Domain/UseCase Layer           │
│  (Business Logic, Rules, State Mgmt)    │
├─────────────────────────────────────────┤
│           Repository Layer               │
│  (Data Access, Transactional Writes)    │
├─────────────────────────────────────────┤
│           Database Layer                 │
│  (SQLDelight + SQLCipher - Encrypted)   │
└─────────────────────────────────────────┘
```

### Layer Responsibilities

#### UI Layer (`app/ui/`)

- **Role:** Presentation and user interaction
- **Technology:** Jetpack Compose with Material Design 3
- **Structure:** Screens organized by user role (auth, admin, agent, enduser)
- **Dependencies:** UseCases from Domain layer
- **Navigation:** Centralized navigation host managing all screen transitions
- **State Management:** ViewModel + Flow for reactive UI updates

#### Domain/UseCase Layer (`app/domain/`)

- **Role:** Business logic and domain rules enforcement
- **Contains:**
  - **Model** — Domain entities, enums, status machines (LearningPlanStatus, TicketStatus, etc.)
  - **UseCase** — Orchestrators of business operations (rules evaluation, SLA tracking, compensation calculations)
- **Key Invariants:**
  - Enforces state machine transitions (e.g., learning plans cannot transition COMPLETED → IN_PROGRESS)
  - Implements business rules (e.g., 4-hour SLA for agent first response, 3-day resolution)
  - Calculates compensation according to rules ($10 auto-approve limit)
- **No Direct Database Access** — All data access flows through repositories

#### Repository Layer (`app/data/repository/`)

- **Role:** Data persistence and transaction coordination
- **Key Repositories:**
  - `UserRepository` — User creation, authentication, role management
  - `ProfileRepository` — End user dietary profiles and nutritional targets
  - `MealPlanRepository` — Weekly meal plans with nutritional composition
  - `LearningPlanRepository` — Educational content lifecycle
  - `TicketRepository` — Exception/after-sales support tickets
  - `RuleRepository` — Business rules with versioning
  - `MessageRepository` — In-app messaging and reminders
  - `ConfigRepository` — Homepage modules, ad slots, campaigns, coupons, black/whitelists
  - `OrderRepository` — Order and charging session management
  - `RolloutRepository` — Version rollout with canary percentage assignment
- **Transactional Writes:** All repository mutations coordinate with AuditManager within a database transaction
- **Audit Logging:** Every write operation is recorded with actor, timestamp, entity changes
- **Error Handling:** All operations return Result<T> with meaningful error messages

#### Database Layer (`app/data/local/`)

- **Technology:** SQLDelight (type-safe SQL with compile-time verification)
- **Encryption:** SQLCipher integration for all-at-rest encryption
- **Schema Management:** Database factory and initialization
- **Concurrency:** Dispatchers.IO for async database access
- **Key Tables:**
  - users, profiles, mealplans, meals, learningplans
  - tickets, evidence, compensation_approvals
  - rules, rule_versions, metrics_snapshots
  - messages, message_templates, reminders
  - configs, config_versions, homepage_modules, adslots, campaigns, coupons
  - orders, charging_sessions, blacklist_whitelist, purchase_limits
  - audit_trail (immutable append-only)

## Directory Structure

```
app/src/main/java/com/nutriops/app/
├── config/                    # AppConfig.kt — Single source of truth for all configuration
├── logging/                   # AppLogger.kt — Structured logging with PII redaction
├── security/                  # Auth, encryption, RBAC
│   ├── AuthManager.kt         # Authentication flow, login/logout, session
│   ├── RbacManager.kt         # Role-based access control
│   ├── PasswordHasher.kt      # Password hashing and verification
│   ├── EncryptionManager.kt   # Encryption/decryption utilities
│   └── DatabaseKeyManager.kt  # Master key derivation for SQLCipher
├── audit/                     # AuditManager.kt — Immutable audit trail with transactional writes
├── data/
│   ├── local/                 # NutriOpsDatabase — SQLDelight database factory
│   └── repository/            # 9 repositories for all data operations
│       ├── UserRepository.kt
│       ├── ProfileRepository.kt
│       ├── MealPlanRepository.kt
│       ├── LearningPlanRepository.kt
│       ├── TicketRepository.kt
│       ├── RuleRepository.kt
│       ├── MessageRepository.kt
│       ├── ConfigRepository.kt
│       ├── OrderRepository.kt
│       └── RolloutRepository.kt
├── domain/
│   ├── model/                 # Enums.kt — All domain types and status machines
│   └── usecase/               # Business logic orchestrators
│       ├── auth/              # Login, bootstrap, authentication flows
│       ├── profile/           # Profile management
│       ├── learningplan/       # Learning plan lifecycle
│       ├── mealplan/           # Meal plan generation and swaps
│       ├── ticket/            # Ticket SLA, compensation, evidence
│       ├── rules/             # Rules evaluation, hysteresis, metrics
│       ├── messaging/         # Message scheduling, quiet hours, daily caps
│       └── config/            # Configuration versioning, rollout
├── di/                        # Hilt dependency injection modules
├── worker/                    # WorkManager background tasks
├── ui/                        # Jetpack Compose screens
│   ├── auth/                  # Login, first-run bootstrap
│   ├── admin/                 # Dashboard, config center, rules engine, audit view
│   ├── agent/                 # Ticket management, SLA tracking
│   ├── enduser/               # Profiles, meal plans, learning plans, support
│   ├── common/                # Shared composables and components
│   ├── navigation/            # Navigation routing
│   └── theme/                 # Material Design 3 theming
├── MainActivity.kt            # Single entry point, Hilt-powered
└── NutriOpsApplication.kt     # Application class, initialization hooks
```

## Key Design Decisions

### 1. Offline-First Architecture

**Decision:** No network dependency; all operations occur on-device  
**Rationale:**

- Field teams in remote locations need guaranteed availability
- End users have unpredictable connectivity
- Data sensitivity requires local encryption without transmitting PII
- Eliminates network latency from critical operations

**Implementation:**

- SQLDelight embedded database with SQLCipher encryption
- All state is local; no sync or replication
- Configuration versioning allows rolling out updates locally (canary rollout pattern)

### 2. SQLDelight + SQLCipher for Database

**Decision:** Type-safe SQL with compile-time verification + AES-256 encryption  
**Rationale:**

- SQLDelight catches SQL errors at compile time (no runtime surprises)
- SQLCipher provides transparent encryption of entire database file
- Kotlin-first API reduces boilerplate
- Room (JPA-style) would sacrifice control over transactions and audit coordination

**Implementation:**

- DatabaseKeyManager derives master encryption key from device-secure storage
- Every database access is async via Dispatchers.IO
- Transactions coordinate user mutations with audit logging

### 3. Role-Based Access Control (RBAC)

**Decision:** Three roles with enforced permissions and audit trails  
**Rationale:**

- Operations teams, agents, and consumers have distinct needs
- Audit trail proves who did what and when
- PII reveal actions are logged for compliance

**Roles:**

- **ADMINISTRATOR** — Full system access, user management, rules creation, config versioning
- **AGENT** — Ticket management, SLA tracking, compensation approval (>$10), PII reveal logging
- **END_USER** — Self-service profiles, meal plans, tickets, messages

**Implementation:**

- RbacManager checks permissions before operations
- AuditManager logs actor (userId + role) with every write
- PII access ("reveal" operations) is tracked separately

### 4. Immutable Audit Trail

**Decision:** Append-only audit log, no DELETEs allowed  
**Rationale:**

- Compliance requirement: prove integrity of operations
- Prevents tampering or historical data loss
- Supports forensics and dispute resolution

**Implementation:**

- AuditManager wraps repository mutations in transactions
- Audit entry includes: entityType, entityId, action (CREATE/UPDATE/DELETE), actor, timestamp, previous state, new state, details
- Every AuditManager call coordinates with the same database transaction as the data mutation

### 5. Structured Logging with PII Redaction

**Decision:** AppLogger strips personally identifiable information from logs  
**Rationale:**

- Reduces compliance risk
- Safe to export logs for debugging without exposing customer data
- Redaction happens at log-write time (not post-hoc filtering)

**Implementation:**

- AppLogger identifies sensitive fields (phone, email, health data) and masks them
- Log level (DEBUG/INFO/WARN/ERROR) is configurable via AppConfig
- All logs are structured (key-value pairs) for machine parsing

### 6. Configuration as Single Source of Truth

**Decision:** All app settings stored in AppConfig object, never in system properties  
**Rationale:**

- Eliminates hidden dependencies on environment variables
- Makes configuration testable and mockable
- Simplifies onboarding and deployment

**Implementation:**

- AppConfig reads values from local database on startup
- Every setting (SLA hours, quiet hours start time, compensation limits, etc.) is centralized
- Configuration changes go through ConfigRepository with versioning

### 7. State Machines for Complex Workflows

**Decision:** LearningPlanStatus and TicketStatus enforce allowed transitions  
**Rationale:**

- Type-safe state management
- Prevents invalid state combinations
- Clear documentation of workflow via enum

**Example — Learning Plan Lifecycle:**

```
NOT_STARTED → IN_PROGRESS → { PAUSED | COMPLETED }
PAUSED → { IN_PROGRESS | COMPLETED }
COMPLETED → ARCHIVED
ARCHIVED → (terminal)
```

**Example — Ticket Workflow:**

```
OPEN → ASSIGNED → { IN_PROGRESS | ESCALATED }
IN_PROGRESS → { AWAITING_EVIDENCE | RESOLVED | ESCALATED }
RESOLVED → CLOSED
(plus escalation/de-escalation loops)
```

### 8. Hilt Dependency Injection

**Decision:** Compile-time DI with Hilt for Android  
**Rationale:**

- Constructor injection reduces boilerplate vs. manual wiring
- Scope management (@Singleton, @ViewModelScoped) is explicit
- Simplifies testing with mocked dependencies

**Implementation:**

- Di modules define bindings (e.g., database factory, repositories)
- Activities/ViewModels annotated with @AndroidEntryPoint
- Repositories are singletons to maintain consistent cache/state

### 9. Jetpack Compose for UI

**Decision:** Declarative UI toolkit over legacy layouts  
**Rationale:**

- Reactive data binding reduces boilerplate
- Composability enables reuse across roles
- Material Design 3 reduces custom styling effort

**Implementation:**

- One activity (MainActivity) hosts navigation
- Screen hierarchy: Navigation Route → Screen Composable → Components
- ViewModel per screen provides state and event handling
- Flow-based reactive updates

### 10. Transaction Coordination Between Data and Audit

**Decision:** AuditManager wraps all repository mutations in the same database transaction  
**Rationale:**

- Ensures audit entry and data mutation always stay in sync
- Prevents orphaned audit records or unaudited changes
- Rollback of mutation also rolls back its audit entry

**Implementation:**

```
auditManager.logWithTransaction(
    entityType = "User", entityId = userId,
    action = AuditAction.CREATE, actor data...
) {
    // This block runs inside a transaction
    queries.insertUser(...)
    // If block succeeds, both user and audit entry commit
    // If block throws, both are rolled back
}
```

## Technology Choices

| Component                | Technology              | Justification                                                |
| ------------------------ | ----------------------- | ------------------------------------------------------------ |
| **UI Framework**         | Jetpack Compose         | Declarative, reactive, Material Design 3 integration         |
| **Database**             | SQLDelight + SQLCipher  | Type-safe SQL, transparent AES-256 encryption, offline-first |
| **Dependency Injection** | Hilt                    | Android-native, compile-time safety, scope management        |
| **Async Runtime**        | Kotlin Coroutines       | Structured concurrency, Flow for reactive streams            |
| **Background Tasks**     | WorkManager             | Handles device reboots, deferred execution, retry logic      |
| **Authentication**       | Custom (PBKDF2 hashing) | Field teams don't have internet; no OAuth possible           |
| **Min SDK**              | Android 10 (API 29)     | Broad device coverage, Jetpack Compose requires 21+          |
| **Compilation Target**   | JDK 17 + Kotlin 1.9     | Modern language features, null safety, sealed classes        |

## Data Flow

### Typical Request Flow

```
User Action (e.g., "Create Ticket")
  ↓
UI Screen Composable (jetpack Compose)
  ↓
ViewModel (holds state, calls UseCases)
  ↓
UseCase (orchestrates business logic, calls Repository)
  ↓
Repository (coordinates transaction + audit)
  │
  ├─→ RbacManager (permission check)
  ├─→ AuditManager.logWithTransaction {
  │     Database.queries (SQLDelight)
  │     └─→ SQLCipher (encrypted local database)
  │   } (both data and audit entry commit or roll back together)
  │
  └─→ Return Result<T> to UseCase
  ↓
UseCase processes result, emits to ViewModel state
  ↓
ViewModel exposes updated state as Flow
  ↓
UI recomposes with new state
```

### Example: Creating a Ticket (End User)

1. **User** clicks "Create Support Ticket" in EndUser UI
2. **Composable** shows form for ticket details (type, description)
3. **ViewModel** waits for form submission, calls `TicketUseCase.createTicket(...)`
4. **UseCase** validates inputs, checks permissions, calls `TicketRepository.createTicket(...)`
5. **Repository**
   - Checks RBAC (user must be END_USER or ADMINISTRATOR)
   - Calls `auditManager.logWithTransaction(...)`
   - Inside transaction:
     - Inserts ticket record (ID, status=OPEN, timestamps)
     - Inserts audit entry (CREATE action, actor=userId, details)
   - Both commit atomically
6. **ViewModel** receives success, emits updated UI state
7. **Composable** shows confirmation and navigates to ticket detail

### Example: Agent Approving Compensation (>$10)

1. **Agent** opens ticket, views compensation suggestion ($15)
2. **Composable** shows "Approve" button (only visible if compensation > $10 AND agent role)
3. **ViewModel** calls `TicketUseCase.approveCompensation(ticketId, amount)`
4. **UseCase**
   - Validates amount is within bounds (COMPENSATION_MIN=$3, MAX=$20)
   - Calls `TicketRepository.approveCompensation(...)`
5. **Repository**
   - Checks RBAC (only AGENT or ADMINISTRATOR can approve)
   - Calls `auditManager.logWithTransaction(...)`
   - Inside transaction:
     - Updates compensation_approvals table (status=APPROVED, approver=agentId)
     - Updates ticket status to RESOLVED
     - Inserts audit entry with newState containing approval details
   - Both commit atomically
6. Audit trail now shows: "Agent X approved $15 compensation on ticket Y at timestamp Z"

### Example: Rule Evaluation and Metrics Capture

1. **Background worker** (WorkManager) runs rule evaluation at scheduled time
2. **UseCase** calls `RuleRepository.evaluateRules(...)`
3. **Repository**
   - Loads all active rules and user metrics
   - For each rule: applies conditions, checks hysteresis (enter/exit thresholds)
   - If rule is triggered:
     - Inserts metrics_snapshot record
     - Calls `auditManager.logWithTransaction(...)`
     - Inside transaction: records metric observation + audit entry
   - Repository holds transaction until all metrics are recorded
4. All metric snapshots and audit entries commit together
5. Admin can view rule evaluation history via audit view

## Data Consistency Guarantees

1. **Transactional Consistency** — All mutations and their corresponding audit entries commit together
2. **ACID Compliance** — SQLDelight/SQLCipher provide standard database guarantees
3. **No Network Sync** — All state is local; distributed consistency not applicable
4. **Audit Immutability** — Audit records cannot be modified or deleted after creation
5. **PII Encryption** — Sensitive data encrypted at rest via SQLCipher; no plaintext copies in memory

## Security Model

- **Authentication** — PBKDF2 password hashing; login creates session token stored locally
- **Authorization** — Role-based; RbacManager checks permissions before each operation
- **Encryption** — SQLCipher (AES-256) for entire database; encryption key derived from device secure storage
- **Audit** — Immutable append-only trail; every mutation logged with actor and timestamp
- **Evidence** — Only images and text allowed (no executable uploads); metadata validated

## Failure Modes and Recovery

| Scenario                         | Handling                                                            |
| -------------------------------- | ------------------------------------------------------------------- |
| Database corruption              | Fail-fast; user guided to reinstall                                 |
| Encryption key loss              | Database becomes inaccessible; reinstall required                   |
| Invalid state machine transition | Rejected at UseCase with error; UI disabled for invalid transitions |
| Permission violation             | Logged to audit trail; user sees "Unauthorized" message             |
| Concurrent mutations             | SQLite serializes; last-write-wins with audit trail                 |
