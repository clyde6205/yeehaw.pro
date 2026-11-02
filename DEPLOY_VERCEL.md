# Vercel deployment — Yehaw browser website

The Vercel project serves the Yehaw Android browser product website. The Android application itself is built separately by GitHub Actions from the `app/` Gradle module.

## Current site build

- Repository: `clyde6205/yehaw.app`
- Browser development branch: `feat/yehaw-full-browser`
- Build command: `npm run build`
- Output directory: `public`
- Node.js: 24.x

The build script copies the website assets into `public/`. The root page is the Yehaw Android browser product site. The former Filipino directory website is retired.

## Deployment workflow

1. Push reviewed changes to the browser development branch to create a preview.
2. Open the preview and verify the page, links, responsive layout and browser messaging.
3. Run the Android build and emulator checks separately.
4. Merge the reviewed browser branch into the Vercel production branch only after the required checks pass.
5. Verify the production domain after deployment.

Do not promote an untested preview or claim that the Android application is globally launch-ready based only on a successful website deployment.

## Android release

APK/AAB generation is handled by the Android GitHub Actions workflow, not by Vercel. A public release requires a protected signing key, release build validation, distribution channel, privacy disclosures and a support/incident process. Signing secrets must never be committed to Git.
