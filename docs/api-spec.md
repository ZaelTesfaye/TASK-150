# NutriOps API Specification

## Overview

NutriOps exposes data operations through a Repository-based API. All operations are performed locally on-device with no network calls. The API is organized by resource type (User, Profile, MealPlan, etc.) with clear request/response contracts.

**Base Behavior:**

- All operations are **asynchronous** (suspend functions returning `Result<T>`)
- All mutations are **transactional** with immutable audit logging
- All responses use a consistent **Result<T>** wrapper: `Result.success(data)` or `Result.failure(exception)`
- All mutations require **actor context** (userId + role for RBAC and audit)
- **Date Format:** ISO 8601 (YYYY-MM-DD)
- **DateTime Format:** ISO 8601 local (YYYY-MM-DDTHH:mm:ss)

## Error Response Format

All failed operations return a `Result.failure(exception)`. The exception message follows this format:

```json
{
  "error": "ErrorType",
  "message": "Human-readable description",
  "entityId": "entity-id (if applicable)"
}
```

**Common HTTP-equivalent Status Codes:**

- `200 OK` — Operation succeeded, data returned
- `400 Bad Request` — Validation failed (invalid dates, out-of-range values, etc.)
- `401 Unauthorized` — Actor lacks required role/permission
- `404 Not Found` — Entity does not exist
- `409 Conflict` — Entity already exists or state transition invalid
- `500 Internal Server Error` — Unhandled exception

## Authentication & Authorization

All endpoints accept an **implicit actor context** determined at call site:

```kotlin
// Example from UseCase
repository.createUser(
    username = "agent.smith@example.com",
    password = "hashed_password",
    role = Role.AGENT,
    actorId = currentUserId,      // Who is making this request?
    actorRole = currentUserRole    // What role do they have?
)
```

**RBAC Rules:**

| Operation                           | Required Role           | Notes                                     |
| ----------------------------------- | ----------------------- | ----------------------------------------- |
| User creation                       | ADMINISTRATOR           | Only admins can create users              |
| User deactivation                   | ADMINISTRATOR           | Only admins can deactivate                |
| All profile operations              | ADMINISTRATOR, END_USER | END_USER can only manage their own        |
| Config/rules creation               | ADMINISTRATOR           | Only admins manage system config          |
| Ticket creation                     | ADMINISTRATOR, END_USER | END_USER creates own tickets              |
| Ticket assignment                   | ADMINISTRATOR, AGENT    | Agents assigned by admin or ticket router |
| Ticket approval (compensation >$10) | ADMINISTRATOR, AGENT    | <=$10 is auto-approved                    |
| PII reveal (ticket evidence)        | ADMINISTRATOR, AGENT    | Logged to audit trail                     |
| Rule evaluation                     | ADMINISTRATOR           | Background workers run as admin context   |

---

## User Management

### Create User

Creates a new user with the specified role.

**Operation:** `UserRepository.createUser(...)`

**Request:**

```kotlin
suspend fun createUser(
    username: String,           // Unique username (e.g. "john.doe@example.com")
    password: String,           // Plaintext password (will be hashed with PBKDF2)
    role: Role,                 // ADMINISTRATOR | AGENT | END_USER
    actorId: String,            // ID of user making the request
    actorRole: Role             // Role of user making the request
): Result<String>               // Returns user ID on success
```

**Example Request:**

```kotlin
userRepository.createUser(
    username = "agent.smith@company.com",
    password = "ComplexP@ss123!",
    role = Role.AGENT,
    actorId = "admin-001",
    actorRole = Role.ADMINISTRATOR
)
```

**Response on Success:**

```kotlin
Result.success("user-uuid-12345")
```

**Response on Failure:**

```kotlin
Result.failure(IllegalArgumentException("Username already exists"))
Result.failure(Exception("Unauthorized: Only ADMINISTRATOR can create users"))
```

**Status Codes:**

- `200` — User created successfully
- `400` — Invalid username format or password too weak
- `409` — Username already exists
- `401` — Actor lacks ADMINISTRATOR role

**Audit Trail:**

```json
{
  "entityType": "User",
  "entityId": "user-uuid-12345",
  "action": "CREATE",
  "actorId": "admin-001",
  "actorRole": "ADMINISTRATOR",
  "details": { "username": "agent.smith@company.com", "role": "AGENT" },
  "timestamp": "2026-04-11T14:30:00"
}
```

---

### Get User by ID

Retrieves a user record by ID.

**Operation:** `UserRepository.getUserById(...)`

**Request:**

```kotlin
suspend fun getUserById(id: String): User?
```

**Response:**

```kotlin
User(
    id = "user-uuid-12345",
    username = "agent.smith@company.com",
    passwordHash = "hashed_value",
    role = "AGENT",
    isActive = true,
    isLocked = false,
    failedLoginAttempts = 0,
    lockoutUntil = null,
    createdAt = "2026-04-11T10:00:00",
    updatedAt = "2026-04-11T10:00:00"
)
```

**Status Codes:**

- `200` — User found
- `404` — User not found (returns null)

---

### Get User by Username

Retrieves a user by username (used for login).

**Operation:** `UserRepository.getUserByUsername(...)`

**Request:**

```kotlin
suspend fun getUserByUsername(username: String): User?
```

**Response:**

```kotlin
User(
    id = "user-uuid-12345",
    username = "agent.smith@company.com",
    ...
)
```

**Status Codes:**

- `200` — User found
- `404` — User not found (returns null)

---

### Get All Users

Retrieves all users in the system.

**Operation:** `UserRepository.getAllUsers()`

**Request:**

```kotlin
suspend fun getAllUsers(): List<User>
```

**Response:**

```kotlin
listOf(
    User(id = "user-1", username = "admin@company.com", role = "ADMINISTRATOR", ...),
    User(id = "user-2", username = "agent@company.com", role = "AGENT", ...),
    User(id = "user-3", username = "enduser@example.com", role = "END_USER", ...)
)
```

**Status Codes:**

- `200` — List returned (may be empty)

---

### Get Users by Role

Retrieves all users with a specific role.

**Operation:** `UserRepository.getUsersByRole(...)`

**Request:**

```kotlin
suspend fun getUsersByRole(role: Role): List<User>
```

**Example:**

```kotlin
userRepository.getUsersByRole(Role.AGENT)
```

**Response:**

```kotlin
listOf(
    User(id = "user-2", username = "agent1@company.com", role = "AGENT", ...),
    User(id = "user-3", username = "agent2@company.com", role = "AGENT", ...)
)
```

---

### Deactivate User

Marks a user as inactive; prevents login without deleting the record.

**Operation:** `UserRepository.deactivateUser(...)`

**Request:**

```kotlin
suspend fun deactivateUser(
    userId: String,             // ID of user to deactivate
    actorId: String,            // ID of user making the request
    actorRole: Role             // Role of user making the request
): Result<Unit>
```

**Example Request:**

```kotlin
userRepository.deactivateUser(
    userId = "user-uuid-12345",
    actorId = "admin-001",
    actorRole = Role.ADMINISTRATOR
)
```

**Status Codes:**

- `200` — User deactivated
- `404` — User not found
- `401` — Actor lacks ADMINISTRATOR role

**Audit Trail:**

```json
{
  "entityType": "User",
  "entityId": "user-uuid-12345",
  "action": "UPDATE",
  "details": { "isActive": false },
  "timestamp": "2026-04-11T14:31:00"
}
```

---

## Profile Management

### Create Profile

Creates a nutrition profile for an end user.

**Operation:** `ProfileRepository.createProfile(...)`

**Request:**

```kotlin
suspend fun createProfile(
    userId: String,              // User ID for this profile
    ageRange: String,            // "18-25", "26-35", "36-45", "46-55", "56-65", "65+"
    dietaryPattern: String,      // "STANDARD", "VEGETARIAN", "VEGAN", "KETO", "PALEO", "MEDITERRANEAN", "LOW_SODIUM", "GLUTEN_FREE"
    allergies: String,           // Comma-separated allergies (e.g., "peanuts, shellfish")
    goal: String,                // "Lose 0.5 lb/week", "Maintain weight", "Gain 1 lb/week", etc.
    preferredMealTimes: String,  // Comma-separated: "BREAKFAST,LUNCH,DINNER"
    dailyCalorieBudget: Long,    // e.g., 2000
    proteinTargetGrams: Long,    // e.g., 150
    carbTargetGrams: Long,       // e.g., 250
    fatTargetGrams: Long,        // e.g., 70
    actorId: String,
    actorRole: Role
): Result<String>
```

**Example Request:**

```kotlin
profileRepository.createProfile(
    userId = "enduser-uuid-456",
    ageRange = "26-35",
    dietaryPattern = "VEGETARIAN",
    allergies = "shellfish, tree nuts",
    goal = "Lose 1 lb/week",
    preferredMealTimes = "BREAKFAST,LUNCH,DINNER",
    dailyCalorieBudget = 1800,
    proteinTargetGrams = 110,
    carbTargetGrams = 225,
    fatTargetGrams = 60,
    actorId = "enduser-uuid-456",
    actorRole = Role.END_USER
)
```

**Response on Success:**

```kotlin
Result.success("profile-uuid-789")
```

**Status Codes:**

- `200` — Profile created
- `400` — Invalid age range, dietary pattern, or meal times
- `409` — Profile already exists for user

---

### Get Profile by User ID

Retrieves the profile for a user.

**Operation:** `ProfileRepository.getProfileByUserId(...)`

**Request:**

```kotlin
suspend fun getProfileByUserId(userId: String): Profile?
```

**Response:**

```kotlin
Profile(
    id = "profile-uuid-789",
    userId = "enduser-uuid-456",
    ageRange = "26-35",
    dietaryPattern = "VEGETARIAN",
    allergies = "shellfish, tree nuts",
    goal = "Lose 1 lb/week",
    preferredMealTimes = "BREAKFAST,LUNCH,DINNER",
    dailyCalorieBudget = 1800,
    proteinTargetGrams = 110,
    carbTargetGrams = 225,
    fatTargetGrams = 60,
    createdAt = "2026-04-11T10:00:00",
    updatedAt = "2026-04-11T10:00:00"
)
```

---

### Update Profile

Updates the nutrition profile for a user.

**Operation:** `ProfileRepository.updateProfile(...)`

**Request:**

```kotlin
suspend fun updateProfile(
    profileId: String,
    ageRange: String,
    dietaryPattern: String,
    allergies: String,
    goal: String,
    preferredMealTimes: String,
    dailyCalorieBudget: Long,
    proteinTargetGrams: Long,
    carbTargetGrams: Long,
    fatTargetGrams: Long,
    actorId: String,
    actorRole: Role
): Result<Unit>
```

**Status Codes:**

- `200` — Profile updated
- `404` — Profile not found
- `400` — Invalid field values

---

## Meal Plan Management

### Create Meal Plan

Creates a weekly meal plan for a user.

**Operation:** `MealPlanRepository.createMealPlan(...)`

**Request:**

```kotlin
suspend fun createMealPlan(
    userId: String,
    weekStartDate: String,      // YYYY-MM-DD
    weekEndDate: String,        // YYYY-MM-DD (must be after weekStartDate)
    dailyCalorieBudget: Long,   // e.g., 2000
    proteinTarget: Long,        // grams
    carbTarget: Long,           // grams
    fatTarget: Long,            // grams
    actorId: String,
    actorRole: Role
): Result<String>
```

**Example Request:**

```kotlin
mealPlanRepository.createMealPlan(
    userId = "enduser-uuid-456",
    weekStartDate = "2026-04-13",
    weekEndDate = "2026-04-19",
    dailyCalorieBudget = 1800,
    proteinTarget = 110,
    carbTarget = 225,
    fatTarget = 60,
    actorId = "enduser-uuid-456",
    actorRole = Role.END_USER
)
```

**Response on Success:**

```kotlin
Result.success("mealplan-uuid-101")
```

**Status Codes:**

- `200` — Meal plan created
- `400` — Invalid date format or end date before start date

---

### Add Meal to Plan

Adds a specific meal to a day in a meal plan.

**Operation:** `MealPlanRepository.addMeal(...)`

**Request:**

```kotlin
suspend fun addMeal(
    mealPlanId: String,
    dayOfWeek: Long,            // 1=Monday, 7=Sunday
    mealTime: String,           // "BREAKFAST", "LUNCH", "DINNER", etc.
    name: String,               // e.g., "Quinoa Buddha Bowl"
    description: String,        // e.g., "Mixed greens, roasted vegetables, tahini dressing"
    calories: Long,
    proteinGrams: Double,
    carbGrams: Double,
    fatGrams: Double,
    reasons: String             // Comma-separated explanation (e.g., "high in fiber, meets protein target")
): Result<String>
```

**Example Request:**

```kotlin
mealPlanRepository.addMeal(
    mealPlanId = "mealplan-uuid-101",
    dayOfWeek = 1,  // Monday
    mealTime = "BREAKFAST",
    name = "Overnight Oats with Berries",
    description = "Oats, Greek yogurt, blueberries, honey",
    calories = 350,
    proteinGrams = 15.0,
    carbGrams = 45.0,
    fatGrams = 8.0,
    reasons = "Good source of fiber,contains probiotics,low glycemic index"
)
```

**Response on Success:**

```kotlin
Result.success("meal-uuid-102")
```

**Status Codes:**

- `200` — Meal added
- `404` — Meal plan not found
- `400` — Invalid day of week or meal time

---

### Swap Meal

Swaps a meal within tolerance (±10% calories, ±5g protein).

**Operation:** `MealPlanRepository.swapMeal(...)`

**Request:**

```kotlin
suspend fun swapMeal(
    mealId: String,             // Original meal to replace
    newName: String,
    newDescription: String,
    newCalories: Long,
    newProteinGrams: Double,
    newCarbGrams: Double,
    newFatGrams: Double,
    newReasons: String,
    actorId: String,
    actorRole: Role
): Result<Unit>
```

**Validation:**

- Original meal calories: {C}
- New meal calories: {C'} where |C' - C| / C ≤ 0.10 (10%)
- Original meal protein: {P}
- New meal protein: {P'} where |P' - P| ≤ 5.0 grams

**Status Codes:**

- `200` — Meal swapped
- `400` — Swap violates tolerance bounds
- `404` — Meal not found

---

## Learning Plan Management

### Create Learning Plan

Creates a learning plan for a user.

**Operation:** `LearningPlanRepository.createLearningPlan(...)`

**Request:**

```kotlin
suspend fun createLearningPlan(
    userId: String,
    title: String,              // e.g., "Intermittent Fasting Basics"
    description: String,        // e.g., "Learn the science and practice of IF"
    startDate: String,          // YYYY-MM-DD
    endDate: String,            // YYYY-MM-DD
    frequencyPerWeek: Long,     // 1-7 (days per week to engage)
    actorId: String,
    actorRole: Role
): Result<String>
```

**Example Request:**

```kotlin
learningPlanRepository.createLearningPlan(
    userId = "enduser-uuid-456",
    title = "7-Day Vegetarian Meal Prep",
    description = "Learn to prepare vegetarian meals in advance",
    startDate = "2026-04-13",
    endDate = "2026-05-11",
    frequencyPerWeek = 3,
    actorId = "enduser-uuid-456",
    actorRole = Role.END_USER
)
```

**Response on Success:**

```kotlin
Result.success("learningplan-uuid-201")
```

**Status Codes:**

- `200` — Learning plan created
- `400` — Invalid frequency (not 1-7) or invalid date format

---

### Transition Learning Plan Status

Moves a learning plan to a new status (respects state machine).

**Operation:** `LearningPlanRepository.transitionStatus(...)`

**State Machine:**

```
NOT_STARTED → IN_PROGRESS
IN_PROGRESS → PAUSED | COMPLETED
PAUSED → IN_PROGRESS | COMPLETED
COMPLETED → ARCHIVED
ARCHIVED → (terminal)
```

**Request:**

```kotlin
suspend fun transitionStatus(
    planId: String,
    newStatus: LearningPlanStatus,  // See enum values above
    actorId: String,
    actorRole: Role
): Result<Unit>
```

**Example Request:**

```kotlin
learningPlanRepository.transitionStatus(
    planId = "learningplan-uuid-201",
    newStatus = LearningPlanStatus.IN_PROGRESS,
    actorId = "enduser-uuid-456",
    actorRole = Role.END_USER
)
```

**Status Codes:**

- `200` — Status transitioned
- `404` — Learning plan not found
- `409` — Invalid state transition (e.g., COMPLETED → IN_PROGRESS)

---

### Duplicate Learning Plan

Creates a copy of a completed learning plan so it can be edited (completed plans are read-only).

**Operation:** `LearningPlanRepository.duplicateLearningPlan(...)`

**Request:**

```kotlin
suspend fun duplicateLearningPlan(
    sourcePlanId: String,       // Must be in COMPLETED or ARCHIVED status
    newStartDate: String,       // YYYY-MM-DD for new plan
    newEndDate: String,
    actorId: String,
    actorRole: Role
): Result<String>
```

**Response on Success:**

```kotlin
Result.success("learningplan-uuid-202")  // New plan ID
```

**Status Codes:**

- `200` — Plan duplicated
- `404` — Source plan not found
- `400` — Source plan not in COMPLETED/ARCHIVED status

---

## Ticket Management

### Create Ticket

Creates a support ticket for a service exception.

**Operation:** `TicketRepository.createTicket(...)`

**Request:**

```kotlin
suspend fun createTicket(
    userId: String,             // End user creating the ticket
    ticketType: String,         // "DELAY" | "DISPUTE" | "LOST_ITEM"
    description: String,        // Detailed description of the issue
    orderId: String?,           // Associated order ID (if applicable)
    actorId: String,
    actorRole: Role
): Result<String>
```

**Example Request:**

```kotlin
ticketRepository.createTicket(
    userId = "enduser-uuid-456",
    ticketType = "LOST_ITEM",
    description = "Order WR-2026-04-001 never arrived. Last tracking showed delivery to my area on 2026-04-10.",
    orderId = "order-uuid-555",
    actorId = "enduser-uuid-456",
    actorRole = Role.END_USER
)
```

**Response on Success:**

```kotlin
Result.success("ticket-uuid-301")
```

**Initial Status:** `OPEN`

**Status Codes:**

- `200` — Ticket created
- `400` — Invalid ticket type

**Audit Trail:**

```json
{
  "entityType": "Ticket",
  "entityId": "ticket-uuid-301",
  "action": "CREATE",
  "details": { "type": "LOST_ITEM", "userId": "enduser-uuid-456" },
  "timestamp": "2026-04-11T15:45:00"
}
```

---

### Assign Ticket

Assigns a ticket to an agent (admin or system only).

**Operation:** `TicketRepository.assignTicket(...)`

**Request:**

```kotlin
suspend fun assignTicket(
    ticketId: String,
    agentId: String,            // ID of agent to assign
    actorId: String,
    actorRole: Role
): Result<Unit>
```

**Status Codes:**

- `200` — Ticket assigned
- `404` — Ticket or agent not found
- `409` — Ticket not in OPEN status

**SLA Activation:** Upon assignment, 4-hour "first response" timer starts.

---

### Transition Ticket Status

Moves ticket through workflow states.

**State Machine:**

```
OPEN → ASSIGNED
ASSIGNED → IN_PROGRESS | ESCALATED
IN_PROGRESS → AWAITING_EVIDENCE | RESOLVED | ESCALATED
AWAITING_EVIDENCE → IN_PROGRESS
RESOLVED → CLOSED
ESCALATED → IN_PROGRESS
CLOSED → (terminal)
```

**Operation:** `TicketRepository.transitionTicketStatus(...)`

**Request:**

```kotlin
suspend fun transitionTicketStatus(
    ticketId: String,
    newStatus: String,          // See state machine above
    reasonForTransition: String?,
    actorId: String,
    actorRole: Role
): Result<Unit>
```

**Example Request:**

```kotlin
ticketRepository.transitionTicketStatus(
    ticketId = "ticket-uuid-301",
    newStatus = "IN_PROGRESS",
    reasonForTransition = "Contacting logistics team to investigate shipment.",
    actorId = "agent-uuid-777",
    actorRole = Role.AGENT
)
```

**Status Codes:**

- `200` — Status transitioned
- `404` — Ticket not found
- `409` — Invalid state transition
- `401` — Agent lacks permission

---

### Add Evidence

Attaches evidence (image or text) to a ticket.

**Operation:** `TicketRepository.addEvidence(...)`

**Request:**

```kotlin
suspend fun addEvidence(
    ticketId: String,
    evidenceType: String,       // "IMAGE" | "TEXT"
    content: String,            // Base64 encoded image OR plaintext
    description: String,        // e.g., "Order packaging photo"
    actorId: String,
    actorRole: Role
): Result<String>
```

**Constraints:**

- Images: PNG, JPEG only; max 1920x1920px; 85% JPEG quality
- Text: Plaintext only (no HTML, markdown, or scripts)
- Both: Malicious content detection enabled

**Status Codes:**

- `200` — Evidence added
- `400` — Invalid evidence type or oversized image
- `404` — Ticket not found

---

### Suggest Compensation

Agent calculates and suggests compensation for a ticket.

**Operation:** `TicketRepository.suggestCompensation(...)`

**Request:**

```kotlin
suspend fun suggestCompensation(
    ticketId: String,
    suggestedAmount: Double,    // $3.00 - $20.00
    reason: String,             // e.g., "Lost item replacement cost"
    actorId: String,
    actorRole: Role
): Result<Unit>
```

**Validation:**

- Amount must be within COMPENSATION_MIN ($3.00) and COMPENSATION_MAX ($20.00)

**Status Codes:**

- `200` — Compensation suggested
- `400` — Amount out of bounds
- `404` — Ticket not found

**Audit Trail:**

```json
{
  "entityType": "Ticket",
  "entityId": "ticket-uuid-301",
  "action": "UPDATE",
  "details": {
    "compensation_suggested": 15.0,
    "reason": "Lost item replacement"
  },
  "timestamp": "2026-04-11T16:00:00"
}
```

---

### Approve Compensation

Agent or admin approves compensation. **Automatic for ≤$10; requires approval for >$10.**

**Operation:** `TicketRepository.approveCompensation(...)`

**Request:**

```kotlin
suspend fun approveCompensation(
    ticketId: String,
    approvedAmount: Double,
    approverNotes: String?,
    actorId: String,
    actorRole: Role
): Result<Unit>
```

**Auto-Approval Logic:**

- If amount ≤ COMPENSATION_AUTO_APPROVE_MAX ($10.00):
  - No agent action required; system approves immediately
  - Compensation status → APPROVED
- If amount > $10.00:
  - Requires AGENT or ADMINISTRATOR action
  - RBAC check enforced

**Status Codes:**

- `200` — Compensation approved
- `400` — Amount out of valid range
- `404` — Ticket not found
- `401` — Amount >$10 but actor lacks AGENT/ADMIN role

**Audit Trail:**

```json
{
  "entityType": "Ticket",
  "entityId": "ticket-uuid-301",
  "action": "UPDATE",
  "details": { "compensation_approved": 15.0, "approver": "agent-uuid-777" },
  "timestamp": "2026-04-11T16:05:00"
}
```

---

### Get Ticket by ID

Retrieves a ticket record.

**Operation:** `TicketRepository.getTicketById(...)`

**Request:**

```kotlin
suspend fun getTicketById(id: String): Ticket?
```

**Response:**

```kotlin
Ticket(
    id = "ticket-uuid-301",
    userId = "enduser-uuid-456",
    type = "LOST_ITEM",
    description = "Order never arrived...",
    status = "IN_PROGRESS",
    createdAt = "2026-04-11T15:45:00",
    assignedAgentId = "agent-uuid-777",
    assignedAt = "2026-04-11T15:50:00",
    slaFirstResponseDeadline = "2026-04-11T19:50:00",  // 4 hours from assignment
    slaResolutionDeadline = "2026-04-14T15:50:00",      // 3 days from assignment
    compensationStatus = "SUGGESTED",
    suggestedCompensationAmount = 15.00,
    approvedCompensationAmount = null,
    ...
)
```

---

### Get Tickets by Agents (Reading)

Retrieves all tickets assigned to an agent.

**Operation:** `TicketRepository.getTicketsByAgentId(...)`

**Request:**

```kotlin
suspend fun getTicketsByAgentId(agentId: String): List<Ticket>
```

---

### Get Open Tickets

Retrieves all tickets not yet closed.

**Operation:** `TicketRepository.getOpenTickets()`

**Request:**

```kotlin
suspend fun getOpenTickets(): List<Ticket>
```

---

### Pause SLA

Temporarily suspends SLA timers for a ticket (e.g., waiting for customer response).

**Operation:** `TicketRepository.pauseSla(...)`

**Request:**

```kotlin
suspend fun pauseSla(
    ticketId: String,
    actorId: String,
    actorRole: Role
): Result<Unit>
```

**Behavior:** Both `slaFirstResponseDeadline` and `slaResolutionDeadline` are frozen; no time elapses until resume.

**Status Codes:**

- `200` — SLA paused
- `404` — Ticket not found

---

## Rule Management

### Create Rule

Creates a business rule for metrics evaluation.

**Operation:** `RuleRepository.createRule(...)`

**Request:**

```kotlin
suspend fun createRule(
    name: String,                           // e.g., "High Adherence"
    description: String,
    ruleType: String,                      // "ADHERENCE" | "EXCEPTION" | "OPERATIONAL_KPI"
    conditionsJson: String,                // JSON format: {"metric":"adherence_rate","operator":">=","value":0.8}
    hysteresisEnter: Double,               // Entry threshold (e.g., 0.80)
    hysteresisExit: Double,                // Exit threshold (e.g., 0.90) — prevents flapping
    minimumDurationMinutes: Long,          // Min time in state before trigger
    effectiveWindowStart: String?,         // ISO time (e.g., "09:00") or null for always
    effectiveWindowEnd: String?,           // ISO time (e.g., "17:00") or null for always
    actorId: String,
    actorRole: Role
): Result<String>
```

**Example Request:**

```kotlin
ruleRepository.createRule(
    name = "High Adherence Acknowledgment",
    description = "User maintains >80% meal plan adherence for 1+ week",
    ruleType = "ADHERENCE",
    conditionsJson = """{"metric":"adherence_rate","operator":">=","value":0.80}""",
    hysteresisEnter = 0.80,
    hysteresisExit = 0.90,
    minimumDurationMinutes = 10080,  // 1 week
    effectiveWindowStart = "09:00",
    effectiveWindowEnd = "17:00",
    actorId = "admin-001",
    actorRole = Role.ADMINISTRATOR
)
```

**Hysteresis Explanation:**

- When metric ≥ 0.80 (hysteresisEnter) AND in state for ≥ 10080 min: rule triggers
- When metric ≥ 0.90 (hysteresisExit): rule is considered "active" (prevents re-triggering)
- When metric < 0.80 again: rule deactivates

**Response on Success:**

```kotlin
Result.success("rule-uuid-401")
```

**Status Codes:**

- `200` — Rule created
- `400` — Invalid JSON conditions or hysteresis values (enter < exit)
- `401` — Actor lacks ADMINISTRATOR role

---

### Update Rule

Updates an existing rule and creates a new version in the audit trail.

**Operation:** `RuleRepository.updateRule(...)`

**Request:** Same parameters as Create, plus `ruleId` to identify the rule.

**Version Management:**

- Every update increments the rule's `currentVersion`
- All previous versions are retained in `rule_versions` table
- Audit trail shows: `{"version":1}` → `{"version":2}`

**Status Codes:**

- `200` — Rule updated
- `404` — Rule not found
- `400` — Invalid parameters

---

## Message & Reminder Management

### Create Message Template

Creates a template for in-app messages or reminders.

**Operation:** `MessageRepository.createTemplate(...)`

**Request:**

```kotlin
suspend fun createTemplate(
    name: String,                // e.g., "Weekly Plan Reminder"
    titleTemplate: String,       // e.g., "Your meal plan for next week"
    bodyTemplate: String,        // e.g., "Check out your personalized plan for {{week}}"
    variablesJson: String,       // JSON: ["week", "mealCount"]
    triggerEvent: String         // "PLAN_CREATED", "WEEKLY_CHECK_IN", etc.
): Result<String>
```

**Response on Success:**

```kotlin
Result.success("template-uuid-501")
```

---

### Send Message

Sends an immediate message to a user.

**Operation:** `MessageRepository.sendMessage(...)`

**Request:**

```kotlin
suspend fun sendMessage(
    userId: String,
    templateId: String?,         // Optional; if provided, title/body from template
    title: String,
    body: String,
    messageType: String,         // "NOTIFICATION" | "REMINDER" | "ALERT" | "TODO"
    triggerEvent: String         // Categorizes the message
): Result<String>
```

**Response on Success:**

```kotlin
Result.success("message-uuid-601")
```

**Status Codes:**

- `200` — Message sent
- `404` — User or template not found

---

### Get Messages by User

Retrieves all messages for a user.

**Operation:** `MessageRepository.getMessagesByUserId(...)`

**Request:**

```kotlin
suspend fun getMessagesByUserId(userId: String): List<Message>
```

---

### Get Unread Messages

Retrieves only unread messages for a user.

**Operation:** `MessageRepository.getUnreadMessages(...)`

**Request:**

```kotlin
suspend fun getUnreadMessages(userId: String): List<Message>
```

---

### Mark Message as Read

Marks a single message as read.

**Operation:** `MessageRepository.markAsRead(...)`

**Request:**

```kotlin
suspend fun markAsRead(messageId: String)
```

---

### Mark All Messages as Read

Marks all messages for a user as read.

**Operation:** `MessageRepository.markAllAsRead(...)`

**Request:**

```kotlin
suspend fun markAllAsRead(userId: String)
```

---

### Schedule Reminder

Schedules a reminder for a future time, respecting quiet hours and daily caps.

**Quiet Hours Configuration:**

- QUIET_HOURS_START = "21:00" (9 PM)
- QUIET_HOURS_END = "07:00" (7 AM)
- MAX_REMINDERS_PER_DAY = 3

**Operation:** `MessageRepository.scheduleReminder(...)`

**Request:**

```kotlin
suspend fun scheduleReminder(
    userId: String,
    messageId: String?,         // Optional message to attach
    title: String,
    scheduledAt: String         // ISO datetime (e.g., "2026-04-15T14:30:00")
): Result<String>
```

**Behavior:**

1. If scheduledAt is within quiet hours: status = SKIPPED_QUIET_HOURS, reminder NOT sent
2. If user already received 3 reminders today: status = SKIPPED_CAP_REACHED, reminder NOT sent
3. Otherwise: status = PENDING, reminder queued for delivery

**Response on Success:**

```kotlin
Result.success("reminder-uuid-701")
```

**Status Codes:**

- `200` — Reminder scheduled
- `400` — Invalid datetime format

---

## Configuration Management

### Create Config

Creates or updates a system configuration value.

**Operation:** `ConfigRepository.createConfig(...)`

**Request:**

```kotlin
suspend fun createConfig(
    key: String,                // Unique config key (e.g., "SLA_FIRST_RESPONSE_HOURS")
    value: String,              // Value (e.g., "4")
    description: String,        // Human description
    actorId: String,
    actorRole: Role
): Result<String>
```

**Example Request:**

```kotlin
configRepository.createConfig(
    key = "COMPENSATION_AUTO_APPROVE_MAX",
    value = "10.00",
    description = "Compensation amounts <=this are auto-approved",
    actorId = "admin-001",
    actorRole = Role.ADMINISTRATOR
)
```

**Standard Configuration Keys:**

- MAX_LOGIN_ATTEMPTS (default 5)
- LOCKOUT_DURATION_MINUTES (default 30)
- SLA_FIRST_RESPONSE_HOURS (default 4)
- SLA_RESOLUTION_DAYS (default 3)
- QUIET_HOURS_START (default "21:00")
- QUIET_HOURS_END (default "07:00")
- MAX_REMINDERS_PER_DAY (default 3)
- CANARY_PERCENTAGE (default 10)
- COMPENSATION_AUTO_APPROVE_MAX (default 10.0)
- LOG_LEVEL (default "DEBUG")
- LOG_REDACTION_ENABLED (default true)

**Status Codes:**

- `200` — Config created/updated
- `400` — Invalid value for key
- `401` — Actor lacks ADMINISTRATOR role

---

### Get Config by Key

Retrieves a configuration value.

**Operation:** `ConfigRepository.getConfigByKey(...)`

**Request:**

```kotlin
suspend fun getConfigByKey(key: String): Config?
```

**Response:**

```kotlin
Config(
    id = "config-uuid-801",
    key = "COMPENSATION_AUTO_APPROVE_MAX",
    value = "10.00",
    description = "...",
    createdAt = "2026-04-11T10:00:00",
    updatedAt = "2026-04-11T14:30:00"
)
```

---

### Create Homepage Module

Adds a module to the admin homepage dashboard.

**Operation:** `ConfigRepository.createHomepageModule(...)`

**Request:**

```kotlin
suspend fun createHomepageModule(
    name: String,               // e.g., "Active Tickets"
    description: String,
    moduleType: String,         // e.g., "METRICS" | "QUICK_ACTIONS" | "ALERTS"
    configJson: String,         // Module-specific config in JSON
    displayOrder: Long,
    actorId: String,
    actorRole: Role
): Result<String>
```

---

### Create Ad Slot

Defines an advertising slot in the end user UI.

**Operation:** `ConfigRepository.createAdSlot(...)`

**Request:**

```kotlin
suspend fun createAdSlot(
    pageName: String,           // e.g., "meal_plan_detail"
    slotPosition: String,       // e.g., "BELOW_CONTENT"
    width: Long,
    height: Long,
    actorId: String,
    actorRole: Role
): Result<String>
```

---

### Create Campaign

Creates an ad campaign to display in ad slots.

**Operation:** `ConfigRepository.createCampaign(...)`

**Request:**

```kotlin
suspend fun createCampaign(
    name: String,
    description: String,
    campaignType: String,       // e.g., "PROMOTION" | "EDUCATIONAL"
    targetAudience: String,     // e.g., "VEGETARIAN_USERS"
    startDate: String,          // YYYY-MM-DD
    endDate: String,
    creativesJson: String,      // JSON array of ad creatives
    actorId: String,
    actorRole: Role
): Result<String>
```

---

### Create Coupon

Creates a promotional coupon.

**Operation:** `ConfigRepository.createCoupon(...)`

**Request:**

```kotlin
suspend fun createCoupon(
    code: String,               // e.g., "VEGETARIAN20"
    discount: Double,           // e.g., 0.20 for 20%
    maxUses: Long,              // e.g., 1000
    maxUsesPerUser: Long,       // e.g., 2 (default from config: DEFAULT_MAX_COUPONS_PER_USER)
    expiryDate: String,         // YYYY-MM-DD
    actorId: String,
    actorRole: Role
): Result<String>
```

---

### Validate Coupon Usage

Checks if a coupon can be used by a user.

**Operation:** `ConfigRepository.validateCouponUsage(...)`

**Request:**

```kotlin
suspend fun validateCouponUsage(
    couponId: String,
    userId: String
): Result<Boolean>
```

**Validation Logic:**

1. Coupon exists and not expired
2. Coupon usage < maxUses
3. User's usage of coupon < maxUsesPerUser

**Response on Success:**

```kotlin
Result.success(true)   // Coupon can be used
```

**Status Codes:**

- `200` — Coupon valid
- `409` — Coupon expired or usage limit reached

---

### Record Coupon Usage

Records that a user has used a coupon.

**Operation:** `ConfigRepository.recordCouponUsage(...)`

**Request:**

```kotlin
suspend fun recordCouponUsage(
    couponId: String,
    userId: String
)
```

---

### Blacklist/Whitelist Management

#### Add to List

Adds an entity (email, phone, product ID) to a blacklist or whitelist.

**Operation:** `ConfigRepository.addToList(...)`

**Request:**

```kotlin
suspend fun addToList(
    listType: String,           // "BLACKLIST" | "WHITELIST"
    entityType: String,         // "EMAIL" | "PHONE" | "PRODUCT_ID" | "ORDER_ID"
    entityValue: String,        // e.g., "john@spam.example.com"
    reason: String?,
    actorId: String,
    actorRole: Role
): Result<Unit>
```

---

#### Is Blacklisted

Checks if an entity is blacklisted.

**Operation:** `ConfigRepository.isBlacklisted(...)`

**Request:**

```kotlin
suspend fun isBlacklisted(
    entityType: String,
    entityValue: String
): Boolean
```

---

#### Is Whitelisted

Checks if an entity is whitelisted.

**Operation:** `ConfigRepository.isWhitelisted(...)`

**Request:**

```kotlin
suspend fun isWhitelisted(
    entityType: String,
    entityValue: String
): Boolean
```

---

### Purchase Limits

#### Set Purchase Limit

Defines a spending limit by entity type (e.g., per user, per IP).

**Operation:** `ConfigRepository.setPurchaseLimit(...)`

**Request:**

```kotlin
suspend fun setPurchaseLimit(
    entityType: String,         // "USER_ID" | "EMAIL" | "IP_ADDRESS"
    entityValue: String,        // e.g., user UUID or IP
    limitAmount: Double,        // e.g., 500.00
    periodDays: Long,           // e.g., 30 for monthly limit
    actorId: String,
    actorRole: Role
): Result<Unit>
```

---

## Order Management

### Create Order

Creates an order record.

**Operation:** `OrderRepository.createOrder(...)`

**Request:**

```kotlin
suspend fun createOrder(
    userId: String,
    totalAmount: Double,
    currencyCode: String,       // e.g., "USD"
    orderItems: String,         // JSON array of items
    shippingAddress: String,    // JSON object
    actorId: String,
    actorRole: Role
): Result<String>
```

**Response on Success:**

```kotlin
Result.success("order-uuid-901")
```

---

### Update Order Status

Updates the status of an order.

**Operation:** `OrderRepository.updateOrderStatus(...)`

**Request:**

```kotlin
suspend fun updateOrderStatus(
    orderId: String,
    newStatus: String,          // "PENDING" | "CONFIRMED" | "PROCESSING" | "COMPLETED" | "CANCELLED"
    reason: String?,
    actorId: String,
    actorRole: Role
): Result<Unit>
```

---

### Create Charging Session

Initiates a payment charging session.

**Operation:** `OrderRepository.createChargingSession(...)`

**Request:**

```kotlin
suspend fun createChargingSession(
    orderId: String,
    amount: Double,
    paymentMethod: String,      // e.g., "CREDIT_CARD"
    paymentDetails: String,     // JSON (typically encrypted in real system)
    actorId: String,
    actorRole: Role
): Result<String>
```

**Response on Success:**

```kotlin
Result.success("session-uuid-1001")
```

**Charging Session Lifecycle:**

```
INITIATED → AUTHORIZED → CAPTURED
        ↘ FAILED
CAPTURED ↘ REFUNDED
```

---

### Get Orders by User

Retrieves all orders for a user.

**Operation:** `OrderRepository.getOrdersByUserId(...)`

**Request:**

```kotlin
suspend fun getOrdersByUserId(userId: String): List<Order>
```

---

## Rollout Management

### Create Rollout

Creates a versioned rollout with deterministic canary assignment.

**Operation:** `RolloutRepository.createRollout(...)`

**Request:**

```kotlin
suspend fun createRollout(
    configId: String,           // Configuration to roll out
    version: Long,              // Version number
    canaryPercentage: Int,      // 0-100 (e.g., 10 for 10%)
    description: String,
    actorId: String,
    actorRole: Role
): Result<String>
```

**Canary Assignment:**

- Deterministic hash of (userId, configId) % 100
- If hash < canaryPercentage: user gets canary version
- Otherwise: user gets current stable version
- Allows precise A/B testing without random seeds

---

## Audit Trail

All write operations (CREATE, UPDATE DELETE) are logged to an immutable audit trail. The audit entry includes:

```kotlin
data class AuditEntry(
    id: String,                 // UUID
    entityType: String,         // "User", "Ticket", "Rule", etc.
    entityId: String,           // ID of the entity being modified
    action: String,             // "CREATE", "UPDATE", "DELETE"
    actorId: String,            // ID of user making the change
    actorRole: String,          // Role of user
    previousState: String?,     // JSON before change (null for CREATE)
    newState: String?,          // JSON after change (null for DELETE)
    details: String?,           // Additional JSON metadata
    timestamp: LocalDateTime
)
```

**Query Audit Trail:**

```kotlin
suspend fun getAuditTrail(
    startDate: String,
    endDate: String,
    entityType: String?,        // Optional filter
    actorId: String?            // Optional filter
): List<AuditEntry>
```

---

## Error Codes Summary

| HTTP Code | Meaning             | Example                                           |
| --------- | ------------------- | ------------------------------------------------- |
| 200       | Success             | User created, ticket updated, message sent        |
| 400       | Validation failed   | Invalid date format, out-of-range value           |
| 401       | Unauthorized (RBAC) | End user attempting to create rule                |
| 404       | Not found           | User, ticket, or rule does not exist              |
| 409       | Conflict            | Username already exists, invalid state transition |
| 500       | Internal error      | Unhandled exception (rare, logged to audit)       |

---

## Rate Limiting & Throttling

No rate limiting is enforced on-device. All operations are synchronous/coroutine-based. High-frequency operations should use debouncing/throttling in the UI layer.

---

## Versioning

API versioning is not applicable for an on-device app. Schema migrations are handled by SQLDelight.

---

## Best Practices

1. **Always check Result<T>** — Never assume success; handle failures
2. **Use actor context** — Pass actorId and actorRole for audit trail
3. **Respect state machines** — Learning plan and ticket transitions follow rules
4. **Audit trail is authoritative** — For disputes, consult the audit trail first
5. **Quiet hours for reminders** — Schedule reminders outside 9 PM - 7 AM
6. **Transactional safety** — Repository mutations are atomic with audit entries
7. **PII protection** — Never log passwords, payment details, or health data
