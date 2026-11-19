# Haluvi Service Connector

## v1.9 package groups and payment-result investigation
- Package browser reads up to 160 visible candidate cards rather than truncating the list at 30. The number shown in Faseyha's "N packages" heading is reported separately; only currently rendered cards can be inspected.
- A category option reads provider-defined filters such as All, Special Offer, Mini Data Plans, Weekly, Monthly, and Data when exposed. A chosen category is applied only if exactly one matching filter control exists, then the package list can be refreshed.
- Package card selection still requires separate confirmation, never triggers Activate package or Pay, and remains unverified until the user's actual site summary is checked.
- Find transaction history scans **existing same-site page links only**, with explicit user confirmation to navigate. If none are found, open the provider menu and retry.
- Inspect current transaction result reads visible submitted/processing/confirmed/failed messages **without** treating these as verified fulfillment or automatically changing local records.
- Payment authorization remains manual. Faseyha history, final recipient delivery, and idempotent provider confirmation have not been connected to HaluviPay.

## Version 1.8: integrated operator and package device tests
- Native Ooredoo/Dhiraagu selection for Recharge and Package Recharge. Apply operator clicks only one exact non-payment operator control; otherwise the user selects in the website.
- Browse live Faseyha packages using visible card labels. Select an exact uniquely identified package card with a separate confirmation; never press Activate Package or Pay. If detection fails, use the provider website.
- Read the current provider order summary for recipient, operator, package, price, tax and total; it is not provider payment-result verification.
- Retains proven Bill Pay form filling, package recipient-only filling, Recharge amount preparation, and local user-reported history.
- No automatic purchase, unverified refund, or HaluviPay fallback. Payment and package activation must be approved by the user on Faseyha's website. Confirm final processing externally before repeating.

## v1.7 operator and package UI integration (device test required)
- Native Ooredoo/Dhiraagu selector for Recharge and Package Recharge; `Apply operator on website — no payment` requests an action only if one exact, visible non-purchase operator control is found, otherwise stops.
- `Inspect packages and displayed totals` reads candidate package labels from the current provider DOM without activating them or hardcoding package prices.
- `Read provider order summary` reads currently displayed provider summary text for manual review; this is not final transaction verification.
- Existing package-recipient filling, Bill Pay, Recharge form preparation, and local observation history are retained.
- Package activation remains manual on the Faseyha site. Native package-card selection has not been verified or enabled. These provider-specific actions are subject to website changes and must be tested before use.
- Do **not** connect the current DOM-based connector to unattended HaluviPay fulfillment or treat local statuses as verified delivery.

## v1.4 local transaction preparation review
- The primary Faseyha assistant now shows only **Prepare selected service form**, **Transaction records and review**, and a toggle for advanced browser controls.
- A successful same-page field-value check creates a local **Prepared** record with a random ID, service, number/account, amount, and timestamp; no website token, cookies, passwords, or raw page HTML are saved.
- You may explicitly record observations as **User reports submitted**, **Needs review**, **User reports completed**, or **User reports failed**, with an optional local note or receipt reference.
- These are **user-reported records only**, not provider-authenticated statuses. They must never trigger automatic Recharge Manager/HaluviPay fulfillment.
- You can delete a record or clear all local records; this does not change any real transaction.
- Submitting the payment is still manual on the Faseyha website. No automated payment initiation, website outcome scraping, wallet API, or guaranteed verification is claimed. Recharge and Package forms still require live device validation.

## v1.3 assisted form preparation
- Choose Recharge, Package Recharge, or Bill Pay; enter target and MVR amount in accessible native fields.
- Select **Prepare selected service form — do not pay**. If the browser is on another page, the app opens the selected direct service URL. After it loads, select Prepare again.
- A matching Faseyha form displays a review dialog, then the app attempts to fill exactly one target field and one amount field.
- It checks that those web inputs equal the requested values immediately after insertion. This is **form-fill verification**, not bank/payment verification.
- The provider website stays visible for manual review and manual transaction authorization.
- If fields are absent, ambiguous, provider-modified or a special package selection is required, the app stops and reports a failure. No unattended payment submission, wallet result extraction, or automatic Recharge Manager fallback is included.
- Existing saved website sessions are retained across a normal APK upgrade.

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
