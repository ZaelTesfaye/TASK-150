# Verification Boundaries

## What Can Be Verified Statically

| Claim | Verification Method |
|-------|-------------------|
| RBAC permission matrix is correct | `RbacManagerTest.kt` - 18 tests covering all role/permission combinations |
| Password hashing is secure | `PasswordHasherTest.kt` - PBKDF2 with salt, constant-time comparison |
| State transitions follow defined matrix | `LearningPlanStatusTest.kt`, `TicketStatusTest.kt` - exhaustive transition tests |
| Audit trail is append-only | `AuditEvents.sq` - only INSERT query defined, no UPDATE/DELETE |
| Log redaction works | `AppLoggerTest.kt` - pattern-based redaction for passwords, tokens, SSNs |
| Configuration defaults are correct | `AppConfigTest.kt` - all default values verified |
| Rule evaluation logic is correct | `EvaluateRuleUseCaseTest.kt` - compound conditions, nested logic, edge cases |
| Macro calculations are deterministic | `ManageProfileUseCaseTest.kt` - all age/goal combinations |
| Database schema has required indexes | SQLDelight .sq files - CREATE INDEX statements |
| No network permissions | `AndroidManifest.xml` - no INTERNET permission |
| Critical writes are transactional | Repository code - `database.transaction { }` + `logWithTransaction()` |
| Off-main-thread execution | Repository code - `withContext(Dispatchers.IO/Default)` |

## What Requires Runtime Verification

| Claim | Why Static Isn't Sufficient | Manual Verification Steps |
|-------|---------------------------|--------------------------|
| Query performance < 50ms @ 1M rows | Depends on device hardware and actual data volume | Seed database, run indexed queries, measure time |
| 60 fps list scrolling | Depends on device GPU and rendering pipeline | Profile with Android Studio on target device |
| SQLCipher encryption actually encrypts | SQLCipher is a proven library, but DB file inspection confirms | Open .db file in hex editor, verify it's not plaintext |
| WorkManager scheduling reliability | OS-level scheduling is non-deterministic | Monitor worker execution logs over 24 hours |
| Image downsampling prevents OOM | Depends on actual image sizes and device RAM | Load large images, monitor heap usage |
| Android Keystore key generation | Hardware-specific security module | Verify on target device class |
| Scoped storage compliance | OS enforcement varies by manufacturer | Test on multiple Android 10+ devices |
| Business hour SLA calculation accuracy | Edge cases around DST, holidays | Manual test with known date inputs |
| Quiet hours across timezone changes | Timezone handling is complex | Manual test across timezone boundaries |

## Assumptions

1. **SQLCipher library correctness**: We trust the SQLCipher library (v4.5.4) to correctly encrypt the database. This is an industry-standard library with extensive third-party audits.

2. **Android Keystore security**: We trust the Android Keystore implementation on the target device class to securely store encryption keys.

3. **PBKDF2 implementation**: We trust the JCE `PBKDF2WithHmacSHA256` implementation. Our tests verify the API contract (salt uniqueness, verification correctness).

4. **Hilt DI correctness**: We trust Dagger/Hilt to correctly scope and inject dependencies as annotated.

5. **Compose rendering**: We trust Jetpack Compose to correctly render the declared UI. Visual verification is recommended but not statically provable.

## Known Limitations

1. **No integration tests with real database**: Unit tests verify logic in isolation. Full integration tests with SQLDelight/SQLCipher require an Android test runner.

2. **No end-to-end UI tests**: Compose UI tests require `androidTest` runtime. The static test suite covers business logic, not UI rendering.

3. **No load/stress testing**: Performance claims are based on index design and architectural patterns, not measured benchmarks.

4. **Template variable injection**: Template resolution uses simple string replacement. No sanitization is applied since this is an offline app with no web rendering.

5. **Business calendar**: SLA calculations use a simple weekday calendar. Public holidays are not accounted for.
