# Security Evidence Document

## Authentication

| Control | Implementation | File | Evidence |
|---------|---------------|------|----------|
| Password hashing | PBKDF2WithHmacSHA256, 120k iterations, 256-bit key, random 32-byte salt | `security/PasswordHasher.kt` | `PasswordHasherTest.kt` - 7 tests |
| First-run bootstrap | Admin account created on first launch, no default credentials | `security/AuthManager.kt:bootstrapAdmin()` | UI flow in `BootstrapScreen.kt` |
| Login lockout | 5 failed attempts → 30 min lockout, attempts reset on success | `security/AuthManager.kt:login()` | Config in `AppConfig.kt` |
| Session management | In-memory StateFlow, no persistent tokens | `AuthManager.kt:_currentSession` | No token storage |

## Authorization

| Control | Implementation | File | Evidence |
|---------|---------------|------|----------|
| Route-level RBAC | Permission matrix per role, checked before every use case | `security/RbacManager.kt` | `RbacManagerTest.kt` - 18 tests |
| Function-level auth | `RbacManager.checkPermission()` at use case entry | All `usecase/*.kt` files | First line of each use case method |
| Object-level auth (BOLA/IDOR) | `RbacManager.checkObjectOwnership()` - users only access own data | `RbacManager.kt:checkObjectOwnership()` | `RbacManagerTest.kt` - 4 ownership tests |
| Admin protections | Only ADMINISTRATOR role can manage config, rules, users, rollouts | `RbacManager.kt` permission matrix | Tests verify non-admin denial |

## Data Protection

| Control | Implementation | File | Evidence |
|---------|---------------|------|----------|
| Database encryption | SQLCipher with passphrase for full database encryption | `data/local/DatabaseFactory.kt` | SQLCipher dependency in build.gradle |
| Field encryption | AES-256-GCM via Android Keystore for sensitive fields | `security/EncryptionManager.kt` | Keystore-backed key generation |
| PII masking | UI masks user IDs by default, agent-only reveal with audit log | `ui/agent/AgentScreens.kt:togglePiiReveal()` | Audit event on reveal |
| Log redaction | Automatic pattern-based redaction of passwords, tokens, SSNs | `logging/AppLogger.kt:redact()` | `AppLoggerTest.kt` - 7 redaction tests |

## Audit Trail

| Control | Implementation | File | Evidence |
|---------|---------------|------|----------|
| Immutability | AuditEvents table: INSERT only, no UPDATE/DELETE queries defined | `AuditEvents.sq` | SQL file review - no UPDATE/DELETE |
| Actor completeness | Every audit event records actorId and actorRole | `audit/AuditManager.kt:log()` | Required parameters |
| Timestamp traceability | ISO-8601 timestamp on every event | `AuditManager.kt` | LocalDateTime formatting |
| Transaction coupling | Critical writes use `logWithTransaction()` - atomic audit + data | `AuditManager.kt:logWithTransaction()` | `database.transaction { }` wrapper |

## User Isolation

| Control | Implementation | File | Evidence |
|---------|---------------|------|----------|
| End User data isolation | Object ownership check before data access | All repositories | `checkObjectOwnership()` calls |
| Agent cross-user access | Agents can view any user's tickets for support | `RbacManager.kt` | AGENT role bypasses ownership |
| Admin full access | Administrators bypass ownership for management | `RbacManager.kt` | ADMIN role bypasses ownership |

## Offline Security

| Control | Implementation | File | Evidence |
|---------|---------------|------|----------|
| No network access | No INTERNET permission declared | `AndroidManifest.xml` | Manifest review |
| No external APIs | No HTTP clients, no remote URLs in codebase | All source files | No OkHttp/Retrofit dependencies |
| Local-only data | All data in encrypted local SQLite | `DatabaseFactory.kt` | Single DB file |
| Scoped storage | Android 10+ scoped storage for evidence images | `AndroidManifest.xml` | `READ_MEDIA_IMAGES` permission |
