#!/bin/bash
# NutriOps Test Execution Script
# Runs all unit tests and outputs a clear summary
set -e

echo "============================================"
echo "  NutriOps Test Suite"
echo "============================================"
echo ""

# Run tests via Gradle
echo "[1/2] Running unit tests..."
./gradlew :app:testDebugUnitTest --no-daemon --info 2>&1 | tail -50

# Collect results
echo ""
echo "============================================"
echo "  Test Results Summary"
echo "============================================"

# Parse test results from XML reports
REPORT_DIR="app/build/test-results/testDebugUnitTest"
if [ -d "$REPORT_DIR" ]; then
    TOTAL=0
    PASSED=0
    FAILED=0
    ERRORS=0

    for file in "$REPORT_DIR"/*.xml; do
        if [ -f "$file" ]; then
            tests=$(grep -o 'tests="[0-9]*"' "$file" | head -1 | grep -o '[0-9]*')
            failures=$(grep -o 'failures="[0-9]*"' "$file" | head -1 | grep -o '[0-9]*')
            errors=$(grep -o 'errors="[0-9]*"' "$file" | head -1 | grep -o '[0-9]*')
            TOTAL=$((TOTAL + ${tests:-0}))
            FAILED=$((FAILED + ${failures:-0}))
            ERRORS=$((ERRORS + ${errors:-0}))
        fi
    done

    PASSED=$((TOTAL - FAILED - ERRORS))

    echo "  Total:    $TOTAL"
    echo "  Passed:   $PASSED"
    echo "  Failed:   $FAILED"
    echo "  Errors:   $ERRORS"
    echo ""

    if [ $FAILED -gt 0 ] || [ $ERRORS -gt 0 ]; then
        echo "  STATUS: SOME TESTS FAILED"
        echo ""
        echo "  Failed test details:"
        for file in "$REPORT_DIR"/*.xml; do
            if [ -f "$file" ]; then
                grep -A5 '<failure' "$file" 2>/dev/null || true
            fi
        done
        exit 1
    else
        echo "  STATUS: ALL TESTS PASSED"
    fi
else
    echo "  Test reports not found at $REPORT_DIR"
    echo "  Running tests via Docker if available..."
    if command -v docker-compose &> /dev/null; then
        docker-compose up --build --abort-on-container-exit
    else
        echo "  Please run: ./gradlew :app:testDebugUnitTest"
    fi
fi

echo ""
echo "============================================"
echo "  Test Categories:"
echo "  - Security: PasswordHasher, RBAC, Auth"
echo "  - State Transitions: LearningPlan, Ticket"
echo "  - Business Logic: Macro calculation, Rules engine"
echo "  - Logging: Redaction, Config"
echo "============================================"
