# Building YeeHaw Browser (reproducible)

Everything below works on a clean machine with no GitHub Actions involved. CI is a convenience, not a dependency.

## Requirements
- JDK 17 (CI uses Temurin 17)
- Android SDK with platform `android-36` and build-tools (accept licences)
- Gradle 8.11.1 (the repo ships a wrapper pinned to it: `./gradlew`)
- Internet access to Google Maven, Maven Central and `services.gradle.org` on the first build

## Commands
```bash
./gradlew testDebugUnitTest        # JVM unit tests (policy, retry, config signature, search routing)
./gradlew lintDebug                # Android lint
./gradlew assembleDebug            # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease          # unsigned unless the signing env below is set
./gradlew connectedDebugAndroidTest   # needs a running emulator or device (19+ instrumented tests)
```

## Release signing
`app/build.gradle.kts` signs the release build only when the production keystore is supplied through the environment
(see `signingConfigs`). Without it the release APK is unsigned and must not be distributed. The keystore is **not** in
the repo and must be backed up by the owner offline.

## Backend (`console/backend`)
```bash
cd console/backend && npm ci && npm test      # needs a Postgres reachable through DATABASE_URL
```
Production runs on Vercel (`yeehaw-api`) against Neon Postgres; neither depends on GitHub Actions. See
`docs/PRODUCTION_DEPLOYMENT.md`.

## Known build-environment note
CI occasionally fails with Maven Central HTTP 429 (rate limit) during dependency download. That is infrastructure, not
a code failure: re-run the job. A local build with a warm Gradle cache is not affected.
