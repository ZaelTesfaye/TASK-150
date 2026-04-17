# Developer-Only Local Setup (NOT CANONICAL, NOT FOR EVALUATION)

> **⚠️ For contributors only — not required for evaluation.**
> This document describes a non-canonical, non-Docker setup path. It is
> **not** linked from the main README, it is **not** how CI validates
> correctness, and it is **not** part of the evaluation flow. The canonical
> and only supported test path is the Docker-contained flow documented in
> [`README.md`](README.md). A green result here does not guarantee
> correctness under the canonical path. Always validate with
> `./run_tests.sh` (Docker) before sending a pull request.

## Prerequisites (local, for IDE-based development)

| Tool           | Version                                      |
|----------------|----------------------------------------------|
| JDK            | 17                                           |
| Android SDK    | API 34 + `build-tools;34.0.0` + platform-tools |
| Gradle         | Bundled via `./gradlew`                       |
| Android Studio | Hedgehog (2023.1.1) or newer, optional        |

## Running the test suite locally

```bash
./run_tests_local.sh
```

This runs `./gradlew :app:testDebugUnitTest` on your host JVM. It is
faster than the Docker path for quick feedback loops in the IDE, but it
relies entirely on your local toolchain being correctly configured.

## Building a debug APK locally

```bash
./gradlew assembleDebug
# APK produced at: app/build/outputs/apk/debug/app-debug.apk
```

## Opening the project in Android Studio / IntelliJ

1. `File → Open…` and select the repo root.
2. Let Gradle sync. The SDK location is taken from `local.properties`
   (auto-generated on first sync).
3. Run the `app` configuration against an AVD launched from the IDE's
   Device Manager.

## What this path does *not* cover

- CI parity. The Docker image in `Dockerfile` is the authoritative
  build/test environment.
- Reproducibility across machines. Host toolchain drift can produce
  green-here / red-in-CI states that are painful to debug.
- Hermetic builds. Local Gradle caches are influenced by your host
  `~/.gradle`.

For any of the above, use `./run_tests.sh` (Docker).

## Physical device deployment

Device deployment is treated separately — see the **Physical Device
Deployment** section in `README.md`. That step legitimately requires a
host `adb` binary because Android Debug Bridge cannot be piped through
the Docker build image without privileged device access, and device
deployment is explicitly scoped as a non-CI operator step.
