#!/bin/bash
# NutriOps Test Execution Script — CANONICAL (Docker-contained).
#
# This is the only supported CI test path. It runs the entire unit +
# integration suite inside the project Docker image. No local JDK or
# Android SDK installation is required.
#
# If you need a non-Docker local path for IDE development, use
# ./run_tests_local.sh — but be aware that it is NOT the canonical path and
# is not how CI validates correctness.
set -e

echo "============================================"
echo "  NutriOps Test Suite  (Docker-contained)"
echo "============================================"
echo ""

if ! command -v docker &> /dev/null; then
    echo "ERROR: docker is not installed. Install Docker Desktop (or the Docker"
    echo "       engine on Linux) and re-run. This is the canonical test path"
    echo "       and cannot be bypassed."
    exit 1
fi

echo "[1/2] Running unit tests inside Docker..."
if command -v docker-compose &> /dev/null; then
    docker-compose up --build --abort-on-container-exit
else
    docker compose up --build --abort-on-container-exit
fi

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
    echo "  Test reports not found at $REPORT_DIR."
    echo "  The Docker run may have failed before producing XML reports;"
    echo "  see the docker-compose output above for details."
    exit 1
fi
