# Haluvi Service Connector

## v2.34 silent voice previews and reliable audio saving

Confirmed on the phone with 2.33: generation, download, playback, speed, voice previews (one button to play and stop) and Choose work. Previews are now silent: no status or label changes are announced over the sample. Save audio finds the generated audio again if Android recreated the screen during the save dialog, writes it fully and reports the size; previously the saved file could be 0 bytes.

## v2.33 Thaana AI audio download and playback speed

Confirmed on the phone with 2.32: text entry, Voice Library (54 voices with descriptions and preview buttons) and generation work. Copying the result failed because Thaana AI serves audio from a separate server that the page script may not read. The app now downloads that address itself (with the signed-in session), falling back to the page copy for in-page audio. The native player adds **Speed** (0.75x to 2x). **Voice** now lists every voice as a row: the voice button (name, male/female, description) plays its preview with Android's own player and stops it when tapped again; **Choose** next to it selects the voice. Close voices is at the top. Under the text box the app shows its own character count and, after typing pauses, Thaana AI's character and credit counter. The Settings panel contains only the Voice Library, so voice choice is the native setting.

## v2.32 native Thaana AI text to speech

Thaana AI, Text to speech is native. Playback controls appear only after speech is generated: a text box, **Attach text file** (the site has no upload, so the app reads a text file into the box), **Voice: name. Change** (read from Thaana AI's Settings panel Voice Library: name, Male/Female and description; locked cloned voices are marked; each offers **Preview**, which plays Thaana AI's own sample, and **Choose this voice**; Back to voices stops a sample), **Generate speech** with progress, then **Play/Pause**, **Stop** and **Save audio** (Android save dialog). The app types into Thaana AI's editor, presses Generate Speech, copies the generated audio into the app and plays it natively. More options has Show website page, page, voice list and settings reports, Reload and sign-in. Native settings follow once the settings report is known.

## v2.31 service groups and Thaana AI (first step)

- Home lists services: **Faseyha** (Payments, Wallet, Website and sign in) and **Thaana AI** (Text to speech, Website and sign in). Faseyha screens and payment logic are unchanged.
- Thaana AI signs in on its own website screen; its session is kept separately from Faseyha. Settings can sign out of Faseyha or Thaana AI separately, or of all websites.
- Thaana AI text to speech opens the signed-in page in the background and offers **Copy page report** (text boxes, voices and options, settings and limits, buttons, file upload, audio and downloads; typed text and passwords excluded). The native controls are built from that report in the next version.
- The background browser engine is now locked to whichever site it serves (faseyha.net or thaana.ai).

## v2.30 correct recipient from Faseyha tax invoices

Based on a real Faseyha tax invoice: the recipient is read from the item row (the line under the service name, for example "Dhiraagu Reload / 7979123"), never from "Bill to", which is the Faseyha account holder. Version 2.29 could show the account holder's number as the recipient; receipts saved by earlier versions are discarded and read again.

## v2.29 recharge receipts and receipt text copy

Recharge and Raastas receipts were never read (the Wallet showed "Recipient not identified"), so those payments were confirmed by the exact balance drop instead. Receipts are now recognized by the Wallet reference itself and accept more total labels. **View in Wallet, transaction, Copy receipt text** copies a receipt's visible text so its exact layout can be checked.

## v2.28 Wallet list updates as receipts are read

Confirmed on the phone with 2.27: Dhiraagu Reload, Ooredoo Raastas, Dhiraagu Bill Pay and Ooredoo Bill Pay complete end to end. The Wallet screen now shows each entry's recipient and amount as soon as its receipt is read, instead of after the whole batch, and picks up receipts already read by the payment screen when you return to it.

## v2.27 reference added after late Wallet listing

Recharge and Raastas: Faseyha's "Confirm recharge" dialog keeps its buttons in a separate bar. The app read only that bar, found no number or amount, and stopped with "Faseyha's confirmation shows different details" (nothing was paid). It now reads the whole dialog, checks operator, number and "Total to pay" there (never the page behind it), and presses "Confirm & recharge" once.

Faseyha lists bill payments in Wallet only after it confirms them, which can take several minutes. When success is already proven by Faseyha's acceptance and the exact balance drop, the app keeps checking Wallet every 30 seconds for up to 5 minutes and then adds the reference to the result and the payment log. The wallet balance line no longer changes to "not available" after a payment.

## v2.26 reliable payment confirmation

- Just before paying, the app notes which transactions the Wallet already lists. After paying, the new entry is the one that was not there before, so confirmation no longer depends on the phone clock matching Faseyha's clock.
- Receipt pages are read only after they finish loading (the app waits for the receipt number), so recipient and amount appear for new Wallet entries.
- When Faseyha accepts the payment and the wallet balance falls by exactly the paid amount, the payment is reported as successful even if the Wallet list is late.

## v2.25 confirmed operator selection

The app now confirms that Faseyha really selected the operator tab it tapped. A tap made before the page was ready could be ignored, leaving Faseyha's default (Ooredoo) selected for a Dhiraagu Reload. If Faseyha changes the operator after the number is entered, the app selects the requested operator again and re-enters the details. Only if Faseyha still insists does the app stop, explain, and optionally offer the other operator's service after you check the number.

If Faseyha rebuilds its form after an operator change or account lookup, the app enters the details again instead of stopping with "did not keep the number or amount". Remaining failures now say exactly what Faseyha shows (form cleared, a different amount, or a disabled button, which may mean the amount is below Faseyha's minimum).

## v2.24 native screens, Ooredoo Bill Pay fixes and simpler flow

**Screens.** Home opens **Faseyha payments**, **Faseyha Wallet** and **Faseyha website and sign in** directly. Payments and Wallet are fully native; the Faseyha page they drive runs hidden behind the native screen and is excluded from TalkBack. Sign-in and manual checking use the separate website screen, which shares the same saved session. A signed-out session is detected and the app offers the website screen; on return it reloads automatically. **More options, Show provider page** reveals the hidden page when needed.

**Payment flow.** Choose the service, enter the number and amount (or choose the package), press **Continue**. The app opens the service, selects the operator, enters the details, waits for Faseyha's validation and shows one native review with the wallet balance. After **Confirm** it presses Faseyha's button once. It presses Faseyha's own confirmation only if that dialog shows the same recipient, operator and amount. Then it reads the result and checks the Wallet entry and receipt. The result reports Successful, Processing, Failed or Needs review, with the reference and the balance change. **View in Wallet** and **New transaction** follow. A warning appears before paying the same recipient again within 30 minutes.

**Ooredoo Bill Pay and reliability fixes.**
- Operator tabs that show only a logo are recognized through their image description. Labels such as "Ooredoo / Supernet" or "Ooredoo Postpaid" also work, and the selected tab is read from standard selected/active markers.
- The app keeps trying for up to eight seconds while Faseyha renders the operator tabs, forms and package cards, instead of failing on the first check.
- Bill forms that first show only an account field use one clearly non-payment lookup button (Check, View bill and similar). If Faseyha shows a fixed bill amount, the app offers to use it.
- Results, confirmations and Wallet matching accept the Supernet, Postpaid and Raastas names Faseyha uses for Ooredoo. Wallet rows named Postpaid, Supernet or Broadband count as bill payments. Recipients are matched with or without +960 and spacing.
- Faseyha confirmation dialogs labelled Confirm, Confirm payment or Yes, pay are handled. Totals labelled Total, Total amount or Amount to pay are checked, not only "Total to pay".
- Package payments use the order-summary **total** as the authorised amount. A package lost after a page reload is selected again automatically.
- Safety bug fixed: opening a service page no longer re-enables a repeat payment after a submission.

**Simplified code.** One shared script library replaces three receipt parsers, two Wallet scanners and duplicated operator checks. Unused functions, manual "user-reported" statuses, the old field inspectors and the second embedded browser are removed. Matching rules live in `FaseyhaRules.kt` with JVM unit tests. The app payment log (More options, Recent payments from this app) is recorded automatically.

**Removed:** controlling other installed apps and the optional Android accessibility service. They only counted controls, needed a sensitive permission and are not needed for Faseyha. Saved websites remain under Other saved websites. Recharge Manager will later use this app as its Faseyha channel.

**Still needs phone testing.** These changes were tested against simulated Faseyha pages, not the live site. **Copy page report for support** copies page structure without field values, to help diagnose any page that still stops.

## v2.3 combined fixes: package flow and Wallet receipt details

- Package Recharge **Continue** now fills the seven-digit recipient in the website and checks the actual provider order summary before showing a native review. It no longer prompts the user to choose the operator/package a second time. It **never** presses the Activate Package/Pay button.
- The Package Category control appears for **Dhiraagu** only, matching the observed Faseyha operator UI; Ooredoo uses the direct Package selector. Changing operator/category clears the prior selected package.
- Selected package buttons use concise package names and provider-displayed prices where available.
- Wallet transaction rows no longer say "Wholesale Wholesale". An inspected receipt's matching account/recipient, amount and receipt payment status are displayed in the native details; receipt review offers return to Wallet. Receipt references must match before merging data, and readable line breaks replace literal backslash-n.
- The Wallet list is retrieved from the provider's authenticated page. Most recipient IDs are **not available in the history list** and are obtained only by opening matching receipts. Do not equate Paid in Full with delivered recharge. All automated payment submission remains disabled.
- These website-dependent paths require device verification. Successful APK builds do not establish real transaction correctness.

## v2.2 combined native workflow and wallet improvements
- Main forms use service-specific labels: Recharge and Package Recharge use **Mobile number (7 digits)**; Bill Pay uses **Account or service number**.
- Package Recharge has immediately visible **Operator**, **Package category**, **Package**, and recipient controls. Package categories are fetched from the current Faseyha HTML and package cards are selected with one tap; the connector checks for a provider order summary and never submits payment.
- The main action is **Continue — prepare and review, do not pay**. Advanced inspection controls are grouped away from the primary workflow.
- Native Wallet rows now attempt to read service, amount, status and date from complete transaction cards, with a strict no-invention fallback. Reference appears in details. A selected receipt can be inspected on the original provider page; parsed data is only joined if its receipt reference matches the chosen history row when present.
- Wallet defaults to **Recharges**, with **Payments** for deposits. Wholesale and customer records remain distinct. Load More automatically retrieves additional pages with a bounded limit; diagnostic navigation is under Advanced.
- Read-only HTML inspection is *not* an official API; exact recipient/amount extraction and automatic package selection must be validated on device. No unattended payment or HaluviPay fallback is enabled.

## v2.1 accessibility and provider-card parsing corrections
- Bill Pay now displays a native operator selector (Dhiraagu, Ooredoo, Medianet). Actual operator activation is attempted only against a unique non-purchase control on the provider Bills page; inspect website if selection fails.
- Package Recharge shows a separate native **Package Category** selector, populated from the active operator's Faseyha page. It updates selected-state text only after matching a unique category control.
- Recharges/Packages automatically attempt operator selection after choosing in the native selector; manual retry remains in advanced controls.
- Wallet transaction extraction now searches for the smallest ancestor containing reference, monetary amount and provider status; native row labels present service, amount, provider status and date first. Reference appears in transaction details. If a page lacks sufficient data, entries are withheld rather than inventing a status.
- Transaction history and package controls are DOM-dependent and still require authenticated phone testing. Payments are never submitted automatically. No HaluviPay fallback has been enabled.

## v2.0 accessible consolidated test release
- Accessible native **Service** and **Operator** selection buttons display the currently selected values and open radio-choice dialogs that identify the selected choice to TalkBack/Jieshuo+. No unexplained unlabeled dropdowns.
- Choosing a service opens its saved-session page; choosing an operator attempts safe one-match application automatically when the correct Faseyha page is loaded. An advanced manual retry control remains.
- Native **Faseyha Wallet and Transactions** opens the known `/wallet` URL. The Recharge category is selected by default. Payments is a separate wallet-deposit filter.
- Live read-only transaction list, capped retrieval, Load More, separate wholesale classification, and provider receipt links are available as best-effort DOM inspection. Third-party website changes may prevent some fields from being recognized. Treat provider "Successful" as a provider record, not guaranteed recipient delivery.
- **GitHub Release publication** is explicitly marked Latest and verified against the release API and attached APK asset, so Developer Hub's release list can find the same tag. The previous unrelated release sorting issue may still require fixing Developer Hub's list sorting.
- Transactions are *never* sent automatically. Checkout, package activation and final authorization remain manual. No private wallet API integration or unattended HaluviPay fallback exists.
- Retains persistent browser session, existing package categories, package selection and order-summary reading, local preparation records and permanent signing key identity.

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
