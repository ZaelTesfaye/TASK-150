#!/bin/bash
# WARNING: This script is for local development convenience only.
# It is NOT the canonical test path. CI and code review use run_tests.sh (Docker).
# Results from this script are not accepted as audit evidence.
# -----------------------------------------------------------------------------
# NutriOps LOCAL Test Script — NOT THE CANONICAL PATH.
# -----------------------------------------------------------------------------
# This script runs the test suite against your host-installed JDK and Android
# SDK via local Gradle. It is provided only for developers iterating quickly
# inside an IDE and aware of the implications:
#
#   * It is NOT how CI validates correctness.
#   * It is NOT Docker-contained; results depend on your local toolchain.
#   * A green run here does NOT guarantee a green canonical run.
#
# The canonical (and only supported CI) test path is ./run_tests.sh, which
# runs the same suite inside the project Docker image. Always confirm your
# change passes ./run_tests.sh before sending a PR.
# -----------------------------------------------------------------------------
set -e

echo "============================================"
echo "  NutriOps Test Suite  (LOCAL — non-canonical)"
echo "============================================"
echo ""

./gradlew :app:testDebugUnitTest --no-daemon --info 2>&1 | tail -50

echo ""
echo "============================================"
echo "  Test Results Summary"
echo "============================================"

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
        echo "  STATUS: SOME TESTS FAILED (local run)"
        echo "  Confirm with ./run_tests.sh before drawing conclusions."
        exit 1
    else
        echo "  STATUS: ALL TESTS PASSED (local run — NOT canonical)"
        echo "  Confirm with ./run_tests.sh before sending a PR."
    fi
else
    echo "  Test reports not found at $REPORT_DIR."
    echo "  Check ./gradlew output above."
    exit 1
fi
