# Haluvi Service Connector

Native Android Kotlin application, designed for Android TalkBack and Jieshuo+.

## Included
- Accessible installed-app selector (launchable apps) without manually typing package names.
- Add, open, and remove local service profiles.
- Add HTTPS websites, use an embedded WebView with cookies and DOM storage, and clear cookies.
- Open already-installed apps using Android-supported launch intents.
- Optional accessibility inspection: only counts exposed controls (no captured text or passwords).
- No paid backend, no root access, no credential harvesting.

## Important limitations
Android isolates other installed apps' private authentication/session data. Selecting an app does not permit a hidden transaction or extracting wallet login tokens. Full in-app recharge requires a provider-supported API, SDK, or explicit external action. Screen-based automation cannot run invisibly while the target app is locked or not available.

## Build
GitHub Actions > Build and publish Android APK > Run workflow. The debug artifact is installable and debug-signed. To create a tagged GitHub release, create and push a v-prefixed tag (for example v1.0.0) and allow the workflow to finish. A permanent production signing configuration must be set up before production deployment.
