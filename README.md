# Haluvi Service Connector

Native Android Kotlin app for TalkBack / Jieshuo+.

## v1.1 Faseyha website assistant (test)
- Save the HTTPS Faseyha website (https://faseyha.net) in My services.
- Open its service page and choose **Faseyha assisted recharge and bill form**.
- The assistant shares permitted WebView cookies with the existing in-app website session.
- Native fields for service, number/account and MVR amount.
- Navigate manually to the appropriate web form. **Inspect current website form** lists visible field descriptions without reading their values.
- **Fill visible form fields — do not pay** attempts to match exactly one number field and one amount field on a trusted Faseyha HTTPS page, and fills them after an explicit confirmation.
- It refuses ambiguous forms and never presses Pay, submits a transaction, reads passwords, obtains credentials, or verifies payment status.
- Visually/with TalkBack review both fields in the actual provider page and submit manually there only after checking. Some custom web components may not support form filling. This is a best-effort test connector; not a verified provider integration.

**Important:** the Recharge, Package Recharge and Bill Pay selector identifies your intended transaction and appears in the review dialog. It does **not** navigate the provider's proprietary website automatically. You must navigate in the browser to the appropriate form; fields may differ by service. No live Faseyha site transaction has been tested in this repository.

## Other features
- Select and save installed app profiles using the native app selector.
- Open installed apps using Android-supported launch intents.
- Add HTTPS websites and save compatible website sessions using embedded WebView.
- Delete cookies, clear service profiles.
- Optional AccessibilityService: counts visible accessible nodes without recording field values.

## Limitations and safe operation
Android applications cannot read other apps' private authentication tokens. The Faseyha website assistant is *not* a payment API, official integration or a background wallet engine. Do not connect it to automatic customer fulfillment or let it submit unattended transactions. Do not treat a filled form, screen text, or a browser navigation as verified payment success. No integration with Recharge Manager/HaluviPay is included.

## Building and signing
The GitHub Actions **Haluvi Service Connector Permanent Signed Release** workflow assembles a signed release APK using this repository's existing GitHub Actions secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_PASSWORD`, and `ANDROID_KEY_ALIAS`. The release certificate is pinned and verified. Never commit credentials, signing keys or cookies. Release builds are triggered by changes to the app source, Gradle version, or release workflow, or can be triggered manually.
