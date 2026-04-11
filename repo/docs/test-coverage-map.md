# Test Coverage Map

## High-Risk Test Areas

### 1. Security Tests (`security/`)

| Test Class | Tests | Coverage Area |
|-----------|-------|--------------|
| `PasswordHasherTest` | 7 | Hash generation, verification, salt uniqueness, malformed input, empty password |
| `RbacManagerTest` | 18 | All role permissions (Admin/Agent/EndUser), object-level authorization, BOLA/IDOR |

### 2. State Machine Tests (`domain/model/`)

| Test Class | Tests | Coverage Area |
|-----------|-------|--------------|
| `LearningPlanStatusTest` | 12 | All valid transitions, all invalid transitions, terminal states, self-transition prevention |
| `TicketStatusTest` | 9 | Full transition matrix, terminal CLOSED state, invalid transition rejection |

### 3. Business Logic Tests (`domain/usecase/`)

| Test Class | Tests | Coverage Area |
|-----------|-------|--------------|
| `ManageProfileUseCaseTest` | 6 | Macro calculation for all age/goal combos, minimum calorie floor, macro sum validation |
| `EvaluateRuleUseCaseTest` | 9 | Simple conditions, AND/OR compound, nested A AND (B OR C), JSON parsing, malformed input |

### 4. Configuration Tests (`config/`)

| Test Class | Tests | Coverage Area |
|-----------|-------|--------------|
| `AppConfigTest` | 14 | All default values, test overrides, SLA/quiet hours/compensation thresholds, TLS toggle |

### 5. Logging Tests (`logging/`)

| Test Class | Tests | Coverage Area |
|-----------|-------|--------------|
| `AppLoggerTest` | 7 | Password redaction, token redaction, SSN redaction, compensation redaction, hash redaction, non-sensitive preservation, redaction disable |

## Coverage Summary

| Category | Test Count | Risk Level |
|----------|-----------|------------|
| Security (auth, RBAC, authorization) | 25 | Critical |
| State transitions (learning plans, tickets) | 21 | High |
| Business logic (macros, rules engine) | 15 | High |
| Configuration & policies | 14 | Medium |
| Logging & redaction | 7 | Medium |
| **Total** | **82** | - |

## Coverage by Requirement Risk

| Risk Level | Requirements Covered | Test Evidence |
|------------|---------------------|---------------|
| Critical | Auth, RBAC, audit immutability, transactional writes | PasswordHasherTest, RbacManagerTest, schema review |
| High | Status transitions, compensation thresholds, SLA config, meal tolerances | LearningPlanStatusTest, TicketStatusTest, AppConfigTest |
| Medium | Macro calculations, rule evaluation, template resolution | ManageProfileUseCaseTest, EvaluateRuleUseCaseTest |
| Low | UI rendering, navigation, theme | Compose preview / manual verification |

## Failure Path Coverage

| Scenario | Test |
|----------|------|
| Wrong password | `PasswordHasherTest.verify wrong password returns false` |
| Malformed hash | `PasswordHasherTest.verify with malformed hash returns false` |
| Unauthorized access | `RbacManagerTest.end user cannot manage config` |
| BOLA/IDOR | `RbacManagerTest.user cannot access other users data` |
| Invalid transition | `LearningPlanStatusTest.COMPLETED cannot transition to IN_PROGRESS` |
| Below minimum calories | `ManageProfileUseCaseTest.calories never go below 1200` |
| Malformed rule JSON | `EvaluateRuleUseCaseTest.parseCondition handles malformed JSON gracefully` |
| Missing metric | `EvaluateRuleUseCaseTest.missing metric returns false` |
| PII in logs | `AppLoggerTest.redact removes password/token/SSN from log messages` |
