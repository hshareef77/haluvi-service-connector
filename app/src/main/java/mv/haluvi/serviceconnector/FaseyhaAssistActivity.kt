package mv.haluvi.serviceconnector

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.text.DateFormat
import java.util.Date

/**
 * Native Faseyha payment screen. A hidden Faseyha page (the signed-in session from
 * the website screen) is driven step by step: open the service, choose the operator,
 * enter the details, wait for Faseyha's validation, then show a native review.
 * After one confirmation the app presses Faseyha's button once, handles Faseyha's
 * own confirmation only if it shows the same details, reads the result, and checks
 * the Wallet entry and receipt. Nothing is ever retried automatically.
 */
class FaseyhaAssistActivity : Activity() {
    private enum class Stage { IDLE, PREPARING, REVIEW, SUBMITTED, VERIFYING, DONE }

    private data class PackageChoice(val text: String, val title: String, val price: BigDecimal?)

    private class Txn(val service: FaseyhaService, val recipient: String, var amount: BigDecimal?,
                      val packageTitle: String?, val balanceBefore: BigDecimal?) {
        var summary = ""
        var startedAt = 0L
        var ledgerId: String? = null
        var accepted = false
        var secondStageDone = false
        var processingPolls = 0
        var chain = 0
        var walletAttempts = 0
        var walletStarted = 0L
        var candidate: JSONObject? = null
        var outcome = ""
    }

    private lateinit var screen: EngineScreen
    private lateinit var form: FaseyhaEngine
    private lateinit var verifier: FaseyhaEngine
    private lateinit var balanceText: TextView
    private lateinit var status: TextView
    private lateinit var serviceButton: Button
    private lateinit var categoryButton: Button
    private lateinit var packageButton: Button
    private lateinit var number: EditText
    private lateinit var amount: EditText
    private lateinit var continueButton: Button
    private lateinit var resultActions: LinearLayout
    private lateinit var more: LinearLayout

    private val prefs by lazy { getSharedPreferences("faseyha_assist", MODE_PRIVATE) }
    private var service = FaseyhaRules.SERVICES[0]
    private var category: String? = null
    private var chosenPackage: PackageChoice? = null

    // State of the hidden Faseyha page; cleared on every page load.
    private var pageReadyFor: FaseyhaService? = null
    private var pageCategory: String? = null
    private var pagePackage: String? = null
    private var afterLoad: (() -> Unit)? = null
    /** Incremented to cancel every pending step of an earlier preparation. */
    private var generation = 0

    private var stage = Stage.IDLE
    private var txn: Txn? = null
    private var balance: BigDecimal? = null
    private var signedOut = false
    private var signInPrompted = false
    private var resumedOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        service = FaseyhaRules.SERVICES.getOrElse(prefs.getInt("service", 0)) { FaseyhaRules.SERVICES[0] }
        screen = EngineScreen(this, "Faseyha payments")
        balanceText = screen.text("Wallet balance: checking…", 18f, live = true)
        serviceButton = screen.button("") { chooseService() }
        categoryButton = screen.button("") { chooseCategory() }
        packageButton = screen.button("") { choosePackage() }
        number = EditText(this).apply { setSingleLine(true) }
        screen.panel.addView(number)
        amount = EditText(this).apply {
            setSingleLine(true); hint = "Amount (MVR)"; contentDescription = "Amount in MVR"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        screen.panel.addView(amount)
        continueButton = screen.button("Continue") { startPreparation() }
        status = screen.text("Opening Faseyha…", 17f, live = true)
        resultActions = screen.section()
        screen.button("View in Wallet", resultActions) { openWallet(verify = true) }
        screen.button("New transaction", resultActions) { newTransaction() }
        screen.button("Faseyha Wallet") { openWallet(verify = false) }
        screen.button("Open Faseyha website") { openFaseyhaWebsite() }
        lateinit var moreToggle: Button
        moreToggle = screen.button("More options") {
            more.visibility = if (more.visibility == View.GONE) View.VISIBLE else View.GONE
            moreToggle.text = if (more.visibility == View.VISIBLE) "Hide more options" else "More options"
        }
        more = screen.section()
        screen.button("Show provider page", more) {
            screen.showWeb(form, "Faseyha ${service.label} page used by the app. Check before paying here yourself.")
        }
        screen.button("Copy page report for support", more) { copyReport() }
        screen.button("Recent payments from this app", more) { showLog() }
        screen.button("Refresh wallet balance", more) { refreshBalance() }
        screen.button("Cancel preparation", more) { cancelPreparation("Preparation cancelled. Nothing was paid.") }

        verifier = FaseyhaEngine(this) { onVerifierLoaded(it) }
        form = FaseyhaEngine(this) { onFormLoaded(it) }
        screen.attach(verifier)
        screen.attach(form)
        setContentView(screen.root)
        updateSelections()
        refreshBalance()
        openService()
    }

    override fun onResume() {
        super.onResume()
        if (!resumedOnce) { resumedOnce = true; return }
        if (signedOut) {
            signedOut = false
            refreshBalance()
            if (stage == Stage.IDLE) openService()
        } else if (stage == Stage.IDLE || stage == Stage.DONE) refreshBalance()
    }

    // ---------- selections ----------

    private fun updateSelections() {
        serviceButton.text = "Service: ${service.label}. Change"
        serviceButton.contentDescription = "Service, selected ${service.label}. Double tap to change."
        val isPackage = service.kind == Kind.PACKAGE
        categoryButton.visibility = if (isPackage && service.operator == "Dhiraagu") View.VISIBLE else View.GONE
        categoryButton.text = "Package category: ${category ?: "All"}. Change"
        packageButton.visibility = if (isPackage) View.VISIBLE else View.GONE
        packageButton.text = chosenPackage?.let { p ->
            "Package: ${p.title}" + (p.price?.let { ", MVR " + FaseyhaRules.format(it) } ?: "") + ". Change"
        } ?: "Package: not selected. Choose package"
        amount.visibility = if (isPackage) View.GONE else View.VISIBLE
        val bill = service.kind == Kind.BILL
        number.hint = if (bill) "Account or service number" else "Mobile number (7 digits)"
        number.contentDescription = number.hint
        number.inputType = if (bill) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS else InputType.TYPE_CLASS_PHONE
    }

    private fun chooseService() {
        if (stage == Stage.SUBMITTED || stage == Stage.VERIFYING) {
            notice("Wait until the last payment has been checked."); return
        }
        val labels = FaseyhaRules.SERVICES.map { it.label }.toTypedArray()
        val current = FaseyhaRules.SERVICES.indexOf(service)
        AlertDialog.Builder(this).setTitle("Choose service")
            .setSingleChoiceItems(labels, current) { dialog, index ->
                dialog.dismiss()
                if (index == current) return@setSingleChoiceItems
                whenPreviousSettled {
                    service = FaseyhaRules.SERVICES[index]
                    prefs.edit().putInt("service", index).apply()
                    category = null
                    chosenPackage = null
                    resetTransaction()
                    updateSelections()
                    openService()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun chooseCategory() {
        if (stage == Stage.DONE) { whenPreviousSettled { resetTransaction(); chooseCategory() }; return }
        if (stage != Stage.IDLE) { notice("Finish or cancel the current payment first."); return }
        say("Reading Faseyha package categories…")
        val token = generation
        ensurePage {
            form.eval(FaseyhaSite.CATEGORIES) { r ->
                if (token != generation) return@eval
                val list = r?.optJSONArray("categories")
                if (list == null || list.length() == 0) {
                    say("Faseyha did not show package categories. All packages can still be chosen.")
                    return@eval
                }
                val names = Array(list.length()) { list.getString(it) }
                AlertDialog.Builder(this).setTitle("Package category")
                    .setItems(names) { _, i ->
                        applyCategory(names[i], token) { ok ->
                            if (ok) {
                                category = names[i]
                                chosenPackage = null
                                updateSelections()
                                say("Category ${names[i]} selected. Now choose a package.")
                            } else say("Faseyha did not accept the ${names[i]} category.")
                        }
                    }.setNegativeButton("Cancel", null).show()
                say("Choose a package category.")
            }
        }
    }

    private fun choosePackage() {
        if (stage == Stage.DONE) { whenPreviousSettled { resetTransaction(); choosePackage() }; return }
        if (stage != Stage.IDLE) { notice("Finish or cancel the current payment first."); return }
        say("Reading Faseyha packages…")
        val token = generation
        ensurePage {
            val cat = category
            if (cat != null && pageCategory != cat) applyCategory(cat, token) { ok ->
                if (ok) listPackages(token, 0) else say("Faseyha did not accept the $cat category.")
            } else listPackages(token, 0)
        }
    }

    private fun listPackages(token: Int, attempt: Int) {
        form.eval(FaseyhaSite.PACKAGES) { r ->
            if (token != generation) return@eval
            val items = r?.optJSONArray("items") ?: JSONArray()
            if (items.length() == 0) {
                if (attempt < 6) form.after(600) { listPackages(token, attempt + 1) }
                else say("Faseyha did not show any packages for ${service.operator}.")
                return@eval
            }
            val choices = (0 until items.length()).map { i ->
                val o = items.getJSONObject(i)
                PackageChoice(o.optString("text"), o.optString("title"), FaseyhaRules.money(o.optString("price")))
            }
            val names = choices.map { c -> c.title + (c.price?.let { ", MVR ${FaseyhaRules.format(it)} including GST" } ?: "") }
            AlertDialog.Builder(this).setTitle("Choose ${service.operator} package")
                .setItems(names.toTypedArray()) { _, i ->
                    val choice = choices[i]
                    say("Selecting ${choice.title} on Faseyha…")
                    selectPackage(choice, token, 0) { ok, reason ->
                        if (ok) {
                            chosenPackage = choice
                            updateSelections()
                            say("Package selected: ${choice.title}. Enter the mobile number and press Continue.")
                        } else say(reason)
                    }
                }.setNegativeButton("Cancel", null).show()
            say("Choose a package.")
        }
    }

    // ---------- hidden Faseyha page ----------

    private fun openService() {
        pageReadyFor = null
        say("Opening Faseyha ${service.label}…")
        ensurePage { if (stage == Stage.IDLE) say("Ready. Enter the details and press Continue.") }
    }

    /** Runs [then] once the hidden page shows this service with its operator selected. */
    private fun ensurePage(then: () -> Unit) {
        if (form.path == service.kind.path && pageReadyFor == service) { then(); return }
        afterLoad = then
        val token = generation
        form.open(service.kind.path)
        form.after(15_000) {
            if (token == generation && afterLoad === then) {
                afterLoad = null
                stepFailed("Faseyha did not open ${service.label}. Check the connection or sign in again.")
            }
        }
    }

    private fun onFormLoaded(url: String) {
        pageReadyFor = null
        pageCategory = null
        pagePackage = null
        val t = txn
        if (stage == Stage.SUBMITTED && t != null) {
            // Faseyha moved to a result page after the button press: keep reading the result.
            form.after(700) { pollResult(t, ++t.chain, 0) }
            return
        }
        val next = afterLoad ?: return
        val token = generation
        form.eval(FaseyhaSite.SIGNED_OUT) { r ->
            if (token != generation || afterLoad !== next) return@eval
            val path = FaseyhaSite.pathOf(url)
            if (r?.optBoolean("signedOut") == true || path == null) { afterLoad = null; handleSignedOut(); return@eval }
            if (path != service.kind.path) {
                // An intermediate page; the timeout in ensurePage reports a stuck redirect.
                return@eval
            }
            afterLoad = null
            applyOperator(token, 0, next)
        }
    }

    private fun applyOperator(token: Int, attempt: Int, then: () -> Unit) {
        form.eval(FaseyhaSite.applyOperator(service.operator)) { r ->
            if (token != generation) return@eval
            if (r?.optBoolean("ok") == true) {
                pageReadyFor = service
                form.after(if (r.optBoolean("clicked")) 900 else 150) { if (token == generation) then() }
            } else if (attempt < 16) {
                form.after(500) { applyOperator(token, attempt + 1, then) }
            } else stepFailed(r?.optString("reason")?.ifBlank { null } ?: "Faseyha did not show the ${service.operator} option.")
        }
    }

    private fun applyCategory(name: String, token: Int, attempt: Int = 0, done: (Boolean) -> Unit) {
        form.eval(FaseyhaSite.applyCategory(name)) { r ->
            if (token != generation) return@eval
            if (r?.optBoolean("ok") == true) { pageCategory = name; form.after(700) { if (token == generation) done(true) } }
            else if (attempt < 6) form.after(500) { applyCategory(name, token, attempt + 1, done) }
            else done(false)
        }
    }

    private fun selectPackage(choice: PackageChoice, token: Int, attempt: Int, done: (Boolean, String) -> Unit) {
        form.eval(FaseyhaSite.selectPackage(choice.text)) { r ->
            if (token != generation) return@eval
            if (r?.optBoolean("ok") != true) {
                if (attempt < 6) form.after(500) { selectPackage(choice, token, attempt + 1, done) }
                else done(false, (r?.optString("reason")?.ifBlank { null } ?: "Faseyha did not accept the package.") + " Nothing was paid.")
                return@eval
            }
            fun check(n: Int) {
                form.eval(FaseyhaSite.packageSummary("")) { s ->
                    if (token != generation) return@eval
                    if (s != null && s.optBoolean("hasPackage") && s.optBoolean("hasTotal")) { pagePackage = choice.text; done(true, "") }
                    else if (n < 6) form.after(400) { check(n + 1) }
                    else done(false, "Faseyha did not show an order summary for this package. Nothing was paid.")
                }
            }
            form.after(400) { check(0) }
        }
    }

    // ---------- preparation ----------

    private fun startPreparation() {
        when (stage) {
            Stage.SUBMITTED, Stage.VERIFYING -> { say("The last payment is still being checked. Please wait."); return }
            Stage.PREPARING, Stage.REVIEW -> return
            else -> Unit
        }
        val kind = service.kind
        val recipient = FaseyhaRules.cleanRecipient(kind, number.text.toString())
        if (recipient == null) {
            number.error = if (kind == Kind.BILL) "Enter the account or service number, letters and digits only"
                else "Enter a seven-digit Maldives mobile number"
            number.requestFocus(); return
        }
        number.setText(recipient)
        var money: BigDecimal? = null
        if (kind == Kind.PACKAGE) {
            if (chosenPackage == null) { notice("Choose a package first."); return }
        } else {
            money = FaseyhaRules.validAmount(amount.text.toString())
            if (money == null) {
                amount.error = "Enter a positive amount with at most two decimal places"
                amount.requestFocus(); return
            }
        }
        txn = Txn(service, recipient, money, chosenPackage?.title, balance)
        stage = Stage.PREPARING
        generation++
        val token = generation
        resultActions.visibility = View.GONE
        continueButton.isEnabled = false
        say("Preparing ${service.label} for $recipient…")
        if (kind != Kind.PACKAGE) pageReadyFor = null // start from a fresh form
        ensurePage {
            if (token != generation) return@ensurePage
            if (kind == Kind.PACKAGE) preparePackage(token) else fillForm(token, 0, false)
        }
    }

    private fun preparePackage(token: Int) {
        val choice = chosenPackage ?: run { stepFailed("Choose a package first."); return }
        val cat = category
        if (cat != null && pageCategory != cat) {
            applyCategory(cat, token) { ok -> if (ok) preparePackage(token) else stepFailed("Faseyha did not accept the $cat category.") }
            return
        }
        if (pagePackage != choice.text) {
            say("Selecting the package on Faseyha…")
            selectPackage(choice, token, 0) { ok, reason -> if (ok) preparePackage(token) else stepFailed(reason) }
            return
        }
        fillPackageRecipient(token, 0)
    }

    private fun fillPackageRecipient(token: Int, attempt: Int) {
        val t = txn ?: return
        form.eval(FaseyhaSite.fillPackageRecipient(t.recipient)) { r ->
            if (token != generation) return@eval
            when {
                r?.optBoolean("ok") == true -> form.after(500) { readPackageSummary(token, 0) }
                attempt < 8 && (r?.optInt("count", 0) ?: 0) == 0 -> form.after(600) { fillPackageRecipient(token, attempt + 1) }
                else -> stepFailed(r?.optString("reason")?.ifBlank { null } ?: "The mobile number could not be entered on Faseyha.")
            }
        }
    }

    private fun readPackageSummary(token: Int, attempt: Int) {
        val t = txn ?: return
        form.eval(FaseyhaSite.packageSummary(t.recipient)) { r ->
            if (token != generation) return@eval
            if (r != null && r.optBoolean("recipient") && r.optBoolean("hasTotal") && r.optBoolean("hasPackage")) {
                t.summary = r.optString("summary")
                val total = FaseyhaRules.labelledTotal(t.summary)
                if (total == null) stepFailed("Faseyha's order summary does not show a total, so the package was not paid.")
                else { t.amount = total; showReview(token) }
            } else if (attempt < 8) form.after(600) { readPackageSummary(token, attempt + 1) }
            else stepFailed("Faseyha's order summary does not show this package for ${t.recipient}.")
        }
    }

    private fun fillForm(token: Int, attempt: Int, lookedUp: Boolean) {
        val t = txn ?: return
        form.eval(FaseyhaSite.fill(t.recipient, plain(t.amount), service.kind, lookedUp)) { r ->
            if (token != generation) return@eval
            when (r?.optString("stage")) {
                "filled" -> {
                    say("Details entered. Waiting for Faseyha to check them…")
                    form.after(600) { waitReady(token, 0) }
                }
                "lookup" -> {
                    say("Looking up the account on Faseyha…")
                    form.after(1500) { fillForm(token, attempt + 1, true) }
                }
                "provider-amount" -> offerProviderAmount(t, r.optString("providerAmount"))
                "mismatch", "error" -> stepFailed(r.optString("reason"))
                else -> if (attempt < 15) form.after(650) { fillForm(token, attempt + 1, lookedUp) }
                    else stepFailed(r?.optString("reason")?.ifBlank { null } ?: "The Faseyha form did not appear.")
            }
        }
    }

    private fun offerProviderAmount(t: Txn, shown: String) {
        val due = FaseyhaRules.money(shown)
        stage = Stage.IDLE
        continueButton.isEnabled = true
        if (due == null || due <= BigDecimal.ZERO) { stepFailed("Faseyha shows a fixed amount that could not be read."); return }
        say("Faseyha shows MVR ${FaseyhaRules.format(due)} for this account. Nothing was paid.")
        AlertDialog.Builder(this).setTitle("Amount shown by Faseyha")
            .setMessage("Faseyha shows MVR ${FaseyhaRules.format(due)} for ${t.recipient}. You entered MVR ${FaseyhaRules.format(t.amount)}.")
            .setPositiveButton("Use MVR ${FaseyhaRules.format(due)}") { _, _ -> amount.setText(plain(due)); startPreparation() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun waitReady(token: Int, attempt: Int) {
        val t = txn ?: return
        form.eval(FaseyhaSite.ready(t.recipient, plain(t.amount), service.operator, service.kind.action)) { d ->
            if (token != generation) return@eval
            val summary = d?.optString("summary").orEmpty()
            val valuesKept = d != null && d.optBoolean("numberMatch") && d.optBoolean("amountMatch")
            val ready = d != null && (service.kind == Kind.BILL || summary.isNotBlank()) && d.optInt("enabled") == 1 &&
                valuesKept && d.optBoolean("operatorMatch")
            // Faseyha chooses the operator from the number it is given. When it settles
            // on a different operator, offer the matching service instead of failing.
            val detected = d?.optJSONObject("operator")?.optString("active").orEmpty()
            when {
                ready -> { t.summary = summary; showReview(token) }
                detected.isNotBlank() && !detected.equals(service.operator, true) && valuesKept && attempt >= 2 ->
                    operatorDetected(t, detected)
                attempt < 19 -> {
                    if (attempt == 4) say("Waiting for Faseyha to check the account and enable payment…")
                    // Faseyha can rebuild its form after an operator change or an account
                    // lookup, losing typed values. Enter them again rather than failing.
                    val refill = (!valuesKept && attempt in setOf(2, 6, 10, 14)) ||
                        (d != null && d.optInt("enabled") == 0 && attempt in setOf(8, 15))
                    if (refill) form.eval(FaseyhaSite.fill(t.recipient, plain(t.amount), service.kind, true)) {
                        form.after(650) { waitReady(token, attempt + 1) }
                    } else form.after(650) { waitReady(token, attempt + 1) }
                }
                else -> stepFailed(notReadyReason(d, t))
            }
        }
    }

    private fun operatorDetected(t: Txn, detected: String) {
        val match = FaseyhaRules.SERVICES.firstOrNull { it.kind == t.service.kind && it.operator.equals(detected, true) }
        val name = match?.operator ?: detected.replaceFirstChar { it.uppercase() }
        val what = if (t.service.kind == Kind.BILL) "account ${t.recipient}" else "${t.recipient} as a $name number"
        generation++
        stage = Stage.IDLE
        txn = null
        continueButton.isEnabled = true
        if (match == null) {
            say("Faseyha selects $name for $what, which this app does not support for ${t.service.label}. Nothing was paid.")
            notice(status.text.toString(), "Different operator")
            return
        }
        val message = if (t.service.kind == Kind.BILL) "Faseyha selects $name for $what, not ${t.service.operator}."
            else "Faseyha identifies $what, not ${t.service.operator}."
        say("$message Nothing was paid.")
        AlertDialog.Builder(this).setTitle("Different operator")
            .setMessage("$message\n\nUse ${match.label} instead?" +
                if (match.kind == Kind.PACKAGE) "\nYou will need to choose the package again." else "")
            .setPositiveButton("Use ${match.label}") { _, _ ->
                val keepNumber = t.recipient
                val keepAmount = amount.text.toString()
                service = match
                prefs.edit().putInt("service", FaseyhaRules.SERVICES.indexOf(match)).apply()
                category = null
                chosenPackage = null
                resetTransaction()
                updateSelections()
                number.setText(keepNumber)
                amount.setText(keepAmount)
                if (match.kind == Kind.PACKAGE) openService() else startPreparation()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun notReadyReason(d: JSONObject?, t: Txn): String {
        if (d == null) return "Faseyha's page could not be read."
        val invalid = d.optJSONArray("invalid")
        val missing = if (invalid != null) (0 until invalid.length()).joinToString(", ") { invalid.optString(it) } else ""
        val seen = d.optJSONArray("amountSeen")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        return when {
            !d.optBoolean("numberMatch") && d.optBoolean("numberEmpty") -> "Faseyha cleared the form after the details were entered."
            !d.optBoolean("numberMatch") -> "Faseyha did not keep ${t.recipient} in its form."
            !d.optBoolean("amountMatch") && seen.isNotEmpty() ->
                "Faseyha shows the amount as ${seen.joinToString(" or ")} instead of ${plain(t.amount)}."
            !d.optBoolean("amountMatch") -> "Faseyha did not keep the amount ${plain(t.amount)}."
            d.optString("summary").isBlank() && service.kind != Kind.BILL -> "Faseyha did not show an order summary for this number."
            !d.optBoolean("operatorMatch") -> "Faseyha does not show ${service.operator} as the selected operator."
            missing.isNotBlank() -> "Faseyha needs more information: $missing."
            d.optInt("found") == 0 -> "Faseyha's ${service.kind.action} button was not found."
            d.optInt("enabled") == 0 -> "Faseyha's ${service.kind.action} button stays disabled. Faseyha may not accept this number or amount; check the minimum amount."
            else -> "Faseyha shows more than one ${service.kind.action} button, so the app stopped."
        }
    }

    // ---------- review and submission ----------

    private fun showReview(token: Int) {
        val t = txn ?: return
        stage = Stage.REVIEW
        val amountText = "MVR " + FaseyhaRules.format(t.amount)
        val details = buildString {
            append("Service: ${t.service.label}\n")
            append(if (t.service.kind == Kind.BILL) "Account: " else "Mobile number: ").append(t.recipient).append('\n')
            t.packageTitle?.let { append("Package: $it\n") }
            append(if (t.service.kind == Kind.PACKAGE) "Total to pay: " else "Amount: ").append(amountText).append('\n')
            val due = t.amount
            balance?.let {
                append("Wallet balance: MVR ${FaseyhaRules.format(it)}\n")
                if (due != null && it < due) append("The wallet balance is lower than this amount; Faseyha may refuse it.\n")
            }
            if (t.summary.isNotBlank()) append("\nFaseyha order summary:\n").append(t.summary.take(500)).append('\n')
            append("\nConfirm sends this payment once.")
        }
        val confirmLabel = when (t.service.kind) {
            Kind.RECHARGE -> "Recharge $amountText"
            Kind.BILL -> "Pay $amountText"
            Kind.PACKAGE -> "Activate package"
        }
        say("Review the details and confirm.")
        AlertDialog.Builder(this).setTitle("Confirm ${t.service.label}").setMessage(details)
            .setPositiveButton(confirmLabel) { _, _ -> if (token == generation) checkRepeatThenSubmit(token) }
            .setNegativeButton("Cancel") { _, _ -> if (token == generation) cancelPreparation("Payment cancelled. Nothing was paid.") }
            .setCancelable(false).show()
    }

    private fun checkRepeatThenSubmit(token: Int) {
        val t = txn ?: return
        val similar = FaseyhaLedger.recentSimilar(this, t.service.label, t.recipient)
        if (similar == null) { submit(token); return }
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(similar.optLong("created")))
        AlertDialog.Builder(this).setTitle("Possible repeat payment")
            .setMessage("At $time this app already sent ${t.service.label} to ${t.recipient} for MVR ${similar.optString("amount")}. " +
                "Its status: ${similar.optString("status")}. Pay again?")
            .setPositiveButton("Pay again") { _, _ -> if (token == generation) submit(token) }
            .setNegativeButton("Cancel") { _, _ -> if (token == generation) cancelPreparation("Payment cancelled. Nothing was paid.") }
            .setCancelable(false).show()
    }

    private fun submit(token: Int) {
        val t = txn ?: return
        if (token != generation || stage != Stage.REVIEW || form.path != service.kind.path) {
            stepFailed("The Faseyha page changed before payment."); return
        }
        stage = Stage.SUBMITTED
        say("Sending to Faseyha…")
        form.eval(FaseyhaSite.submit(service.kind.path, t.recipient, plain(t.amount), service.operator, service.kind.action, t.summary)) { r ->
            if (t !== txn) return@eval
            when {
                r == null -> {
                    markSubmitted(t)
                    say("Faseyha's response could not be read. Checking Wallet; do not pay again.")
                    startVerification(t)
                }
                r.optBoolean("ok") -> {
                    markSubmitted(t)
                    say("Payment sent to Faseyha. Waiting for the result; do not pay again.")
                    form.after(750) { pollResult(t, ++t.chain, 0) }
                }
                else -> { stage = Stage.REVIEW; stepFailed(r.optString("reason") + ".") }
            }
        }
    }

    private fun markSubmitted(t: Txn) {
        t.startedAt = System.currentTimeMillis()
        t.ledgerId = FaseyhaLedger.create(this, t.service.label, t.recipient, FaseyhaRules.format(t.amount))
        pageReadyFor = null
    }

    private fun pollResult(t: Txn, chain: Int, attempt: Int) {
        if (t !== txn || chain != t.chain || stage != Stage.SUBMITTED) return
        if (attempt > 35 || !FaseyhaSite.trustedUrl(form.url)) {
            say("Checking Faseyha Wallet for the result; do not pay again.")
            startVerification(t); return
        }
        form.eval(FaseyhaSite.result(t.recipient, plain(t.amount), service.operator, service.kind.action)) { r ->
            if (t !== txn || chain != t.chain || stage != Stage.SUBMITTED) return@eval
            val message = r?.optString("message").orEmpty()
            when (r?.optString("classification")) {
                "Awaiting confirmation" ->
                    if (!t.secondStageDone) secondStage(t, message, r.optString("actionLabel"))
                    else form.after(900) { pollResult(t, chain, attempt + 1) }
                "Processing" -> {
                    t.processingPolls++
                    if (t.processingPolls == 1) say("Faseyha is processing the payment…")
                    if (t.processingPolls > 8) startVerification(t) else form.after(1100) { pollResult(t, chain, attempt + 1) }
                }
                "Provider accepted payment", "Provider reported success" -> {
                    t.accepted = true
                    say("Faseyha accepted ${t.service.label} for ${t.recipient}, MVR ${FaseyhaRules.format(t.amount)}. Confirming in Wallet…")
                    form.after(2000) { startVerification(t) }
                }
                "Insufficient balance" -> finish(t, FaseyhaLedger.FAILED, "Not paid: Faseyha reports insufficient wallet balance.")
                "Failed" -> finish(t, FaseyhaLedger.FAILED, "Faseyha reported a failure: ${message.take(160)}. Check Wallet before trying again.")
                else -> form.after(900) { pollResult(t, chain, attempt + 1) }
            }
        }
    }

    /** Faseyha's own confirmation dialog is pressed only when it repeats the reviewed details. */
    private fun secondStage(t: Txn, text: String, label: String) {
        if (!FaseyhaRules.confirmationMatches(text, t.recipient, service.operator, t.amount)) {
            secondStageStopped(t, "Faseyha's confirmation shows different details."); return
        }
        say("Faseyha asked for confirmation. The details match; confirming once…")
        form.eval(FaseyhaSite.confirmSecond(service.kind.path, t.recipient, plain(t.amount), service.operator, label)) { r ->
            if (t !== txn || stage != Stage.SUBMITTED) return@eval
            if (r?.optBoolean("ok") == true) {
                t.secondStageDone = true
                form.after(750) { pollResult(t, ++t.chain, 0) }
            } else secondStageStopped(t, r?.optString("reason")?.ifBlank { null } ?: "Faseyha's confirmation could not be pressed.")
        }
    }

    private fun secondStageStopped(t: Txn, reason: String) {
        finish(t, FaseyhaLedger.REVIEW, "$reason The payment was not confirmed by the app. Open the provider page to check it; do not pay twice.")
        AlertDialog.Builder(this).setTitle("Confirmation stopped").setMessage(status.text)
            .setPositiveButton("Show provider page") { _, _ -> screen.showWeb(form, "Faseyha confirmation. Check every detail before confirming here.") }
            .setNegativeButton("Close", null).show()
    }

    // ---------- Wallet verification ----------

    private fun startVerification(t: Txn) {
        if (t !== txn || stage == Stage.VERIFYING || stage == Stage.DONE) return
        stage = Stage.VERIFYING
        t.walletAttempts = 0
        t.walletStarted = System.currentTimeMillis()
        verifier.open("/wallet")
    }

    private fun onVerifierLoaded(url: String) {
        val t = txn
        val path = FaseyhaSite.pathOf(url)
        if (stage == Stage.VERIFYING && t != null) {
            when {
                path == "/wallet" -> verifier.after(1000) { verifyWallet(t, 0) }
                FaseyhaSite.isReceiptUrl(url) -> verifier.after(650) { verifyReceipt(t) }
                else -> retryWallet(t)
            }
            return
        }
        if (path == "/wallet") verifier.after(800) { readBalance() }
        else verifier.eval(FaseyhaSite.SIGNED_OUT) { r ->
            if (r?.optBoolean("signedOut") == true && stage == Stage.IDLE) handleSignedOut()
            else balanceText.text = "Wallet balance: not available"
        }
    }

    private fun refreshBalance() {
        if (stage == Stage.VERIFYING) return
        balanceText.text = "Wallet balance: checking…"
        verifier.open("/wallet")
    }

    private fun readBalance() {
        if (stage == Stage.VERIFYING) return
        verifier.eval(FaseyhaSite.WALLET_SCAN) { r ->
            if (r?.optBoolean("signedOut") == true) { handleSignedOut(); return@eval }
            balance = FaseyhaRules.money(r?.optString("balance"))
            showBalance()
        }
    }

    private fun showBalance() {
        balanceText.text = balance?.let { "Wallet balance: MVR ${FaseyhaRules.format(it)}" } ?: "Wallet balance: not shown"
    }

    private fun verifyWallet(t: Txn, pass: Int) {
        if (stage != Stage.VERIFYING || t !== txn) return
        if (pass == 0) {
            verifier.eval(FaseyhaSite.walletTab("Recharges")) { verifier.after(950) { verifyWallet(t, 1) } }
            return
        }
        say(if (t.accepted) "Faseyha accepted the payment. Checking Wallet for the final status…" else "Checking Faseyha Wallet for the payment…")
        verifier.eval(FaseyhaSite.WALLET_SCAN) { r ->
            if (stage != Stage.VERIFYING || t !== txn) return@eval
            if (r == null) { retryWallet(t); return@eval }
            FaseyhaRules.money(r.optString("balance"))?.let { balance = it; showBalance() }
            val rows = r.optJSONArray("rows") ?: JSONArray()
            val matches = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.filter {
                FaseyhaRules.walletRowMatches(t.service, it.optString("service"), it.optString("amount"),
                    it.optString("date"), t.amount, t.startedAt)
            }
            when {
                matches.size > 1 -> finish(t, FaseyhaLedger.REVIEW, "More than one recent Wallet entry matches. Check Faseyha Wallet before paying again.")
                matches.size == 1 -> {
                    t.candidate = matches[0]
                    val receipt = matches[0].optString("url")
                    if (FaseyhaSite.isReceiptUrl(receipt)) verifier.openUrl(receipt) else finishFromRow(t, matches[0], null)
                }
                else -> retryWallet(t)
            }
        }
    }

    private fun verifyReceipt(t: Txn) {
        val row = t.candidate ?: run { retryWallet(t); return }
        verifier.eval(FaseyhaSite.RECEIPT_READ) { d ->
            if (stage != Stage.VERIFYING || t !== txn) return@eval
            if (d == null) { finishFromRow(t, row, null); return@eval }
            val reference = row.optString("reference")
            if (reference.equals(d.optString("reference"), true)) FaseyhaReceipts.put(this, reference, d)
            if (FaseyhaRules.receiptConfirms(reference, d.optString("reference"), t.recipient, d.optString("account"), t.amount, d.optString("amount")))
                finishFromRow(t, row, true)
            else finishFromRow(t, row, false)
        }
    }

    private fun finishFromRow(t: Txn, row: JSONObject, receiptConfirmed: Boolean?) {
        val rowStatus = row.optString("status")
        val ref = row.optString("reference")
        val what = "${t.service.label}, ${t.recipient}, MVR ${FaseyhaRules.format(t.amount)}"
        val balanceNote = balanceChange(t)
        when {
            receiptConfirmed == false -> finish(t, FaseyhaLedger.REVIEW,
                "Wallet shows a matching entry (status $rowStatus, reference $ref), but its receipt does not confirm $what. Check it before paying again.", ref)
            rowStatus.equals("Failed", true) -> finish(t, FaseyhaLedger.FAILED,
                "Faseyha Wallet shows this payment as failed: $what. Reference $ref.$balanceNote", ref)
            FaseyhaRules.successfulStatus(rowStatus) && receiptConfirmed == true -> finish(t, FaseyhaLedger.SUCCESSFUL,
                "Payment successful: $what. Reference $ref.$balanceNote", ref)
            FaseyhaRules.successfulStatus(rowStatus) -> finish(t, FaseyhaLedger.REVIEW,
                "Wallet shows Successful for $what, reference $ref, but the receipt could not be checked.$balanceNote", ref)
            else -> finish(t, FaseyhaLedger.PROCESSING,
                "Faseyha Wallet shows $rowStatus for $what. Reference $ref. Check Wallet later; do not pay again.$balanceNote", ref)
        }
    }

    private fun balanceChange(t: Txn): String {
        val after = balance ?: return ""
        val before = t.balanceBefore
        return if (before != null && t.amount != null && FaseyhaRules.sameMoney(before - after, t.amount))
            " Wallet balance went down by MVR ${FaseyhaRules.format(t.amount)} to MVR ${FaseyhaRules.format(after)}."
        else " Wallet balance: MVR ${FaseyhaRules.format(after)}."
    }

    private fun retryWallet(t: Txn) {
        if (stage != Stage.VERIFYING || t !== txn) return
        t.walletAttempts++
        t.candidate = null
        if (t.walletAttempts >= 7 || System.currentTimeMillis() - t.walletStarted > 90_000L) {
            finish(t, if (t.accepted) FaseyhaLedger.PROCESSING else FaseyhaLedger.REVIEW,
                if (t.accepted) "Faseyha accepted ${t.service.label} for ${t.recipient}, but Wallet does not show it yet. Check Wallet later; do not pay again."
                else "The final status is not available yet. Check Faseyha Wallet before paying again.")
        } else verifier.after(6500) { if (stage == Stage.VERIFYING && t === txn) verifier.open("/wallet") }
    }

    private fun finish(t: Txn, outcome: String, message: String, reference: String = "") {
        if (t !== txn) return
        stage = Stage.DONE
        t.outcome = outcome
        t.ledgerId?.let { FaseyhaLedger.update(this, it, outcome, message, reference) }
        say(message)
        continueButton.visibility = View.GONE
        resultActions.visibility = View.VISIBLE
    }

    // ---------- resets and failures ----------

    private fun stepFailed(reason: String) {
        val wasActive = stage == Stage.PREPARING || stage == Stage.REVIEW
        if (wasActive) { stage = Stage.IDLE; generation++ }
        if (stage == Stage.IDLE) continueButton.isEnabled = true
        say("$reason Nothing was paid.")
        if (!wasActive || isFinishing) return
        AlertDialog.Builder(this).setTitle("Payment not prepared").setMessage(status.text)
            .setPositiveButton("OK", null)
            .setNeutralButton("Show provider page") { _, _ -> screen.showWeb(form, "Faseyha ${service.label} page where the app stopped.") }
            .setNegativeButton("Copy page report") { _, _ -> copyReport() }
            .show()
    }

    private fun cancelPreparation(message: String) {
        when (stage) {
            Stage.PREPARING, Stage.REVIEW -> {
                generation++
                stage = Stage.IDLE
                txn = null
                continueButton.isEnabled = true
                say(message)
            }
            Stage.SUBMITTED, Stage.VERIFYING -> say("The payment was already sent and cannot be cancelled here. Its status is still being checked.")
            else -> Unit
        }
    }

    private fun handleSignedOut() {
        signedOut = true
        afterLoad = null
        if (stage == Stage.PREPARING || stage == Stage.REVIEW) { stage = Stage.IDLE; generation++ }
        if (stage == Stage.IDLE) continueButton.isEnabled = true
        balanceText.text = "Wallet balance: signed out"
        say("You are signed out of Faseyha. Choose Open Faseyha website, sign in, then come back.")
        if (signInPrompted || isFinishing) return
        signInPrompted = true
        AlertDialog.Builder(this).setTitle("Sign in to Faseyha")
            .setMessage("Faseyha needs you to sign in. Sign in on the website screen, then return here.")
            .setPositiveButton("Open website") { _, _ -> openFaseyhaWebsite() }
            .setNegativeButton("Later", null).show()
    }

    private fun whenPreviousSettled(action: () -> Unit) {
        val t = txn
        if (stage == Stage.DONE && t != null && t.outcome != FaseyhaLedger.SUCCESSFUL && t.outcome != FaseyhaLedger.FAILED) {
            AlertDialog.Builder(this).setTitle("Last payment not confirmed")
                .setMessage("The final status of ${t.service.label} to ${t.recipient} is not confirmed. Check Wallet before paying the same number again. Continue?")
                .setPositiveButton("Continue") { _, _ -> action() }
                .setNegativeButton("Cancel", null).show()
        } else action()
    }

    private fun newTransaction() {
        if (stage == Stage.SUBMITTED || stage == Stage.VERIFYING) { say("The last payment is still being checked. Please wait."); return }
        whenPreviousSettled {
            resetTransaction()
            openService()
        }
    }

    private fun resetTransaction() {
        generation++
        txn = null
        stage = Stage.IDLE
        number.text?.clear()
        amount.text?.clear()
        resultActions.visibility = View.GONE
        continueButton.visibility = View.VISIBLE
        continueButton.isEnabled = true
        pageReadyFor = null
    }

    // ---------- other screens and tools ----------

    private fun openWallet(verify: Boolean) {
        val intent = Intent(this, FaseyhaWalletActivity::class.java)
        val t = txn
        if (verify && t != null && t.startedAt > 0) {
            intent.putExtra(FaseyhaWalletActivity.VERIFY_SERVICE, FaseyhaRules.SERVICES.indexOf(t.service))
                .putExtra(FaseyhaWalletActivity.VERIFY_RECIPIENT, t.recipient)
                .putExtra(FaseyhaWalletActivity.VERIFY_AMOUNT, FaseyhaRules.format(t.amount))
                .putExtra(FaseyhaWalletActivity.VERIFY_STARTED_AT, t.startedAt)
        }
        startActivity(intent)
    }

    private fun copyReport() {
        form.eval(FaseyhaSite.PAGE_REPORT) { r ->
            if (r == null) { notice("The Faseyha page could not be read."); return@eval }
            val text = buildString {
                append("Haluvi Service Connector page report\n")
                append("App version: ").append(appVersion()).append('\n')
                append("Service: ${service.label}; step: ${stage.name.lowercase()}\n")
                append("Status: ").append(status.text).append('\n')
                append("Path: ").append(r.optString("path")).append("; signed out: ").append(r.optBoolean("signedOut")).append('\n')
                append("Operator: ").append(r.optString("operator")).append('\n')
                for (key in listOf("headings", "buttons", "choices", "inputs", "dialogs")) {
                    val list = r.optJSONArray(key) ?: continue
                    append('\n').append(key.replaceFirstChar { it.uppercase() }).append(":\n")
                    for (i in 0 until list.length()) append("- ").append(list.optString(i)).append('\n')
                }
                if (r.optString("summary").isNotBlank()) append("\nOrder summary: ").append(r.optString("summary")).append('\n')
                append("\nField values are not included.")
            }
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Faseyha page report", text))
            Toast.makeText(this, "Page report copied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showLog() {
        val rows = FaseyhaLedger.list(this)
        if (rows.length() == 0) { notice("No payments have been sent from this app yet."); return }
        val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val labels = Array(rows.length()) { i ->
            val r = rows.getJSONObject(i)
            "${r.optString("service")}, ${r.optString("target")}, MVR ${r.optString("amount")}, ${r.optString("status")}, " +
                format.format(Date(r.optLong("created")))
        }
        AlertDialog.Builder(this).setTitle("Recent payments from this app")
            .setItems(labels) { _, i ->
                val r = rows.getJSONObject(i)
                AlertDialog.Builder(this).setTitle(r.optString("service"))
                    .setMessage(labels[i] + (r.optString("reference").let { if (it.isBlank()) "" else "\nReference: $it" }) +
                        (r.optString("note").let { if (it.isBlank()) "" else "\n\n$it" }) +
                        "\n\nFaseyha Wallet is the final record.")
                    .setPositiveButton("Close", null)
                    .setNegativeButton("Delete from log") { _, _ -> FaseyhaLedger.delete(this, r.optString("id")) }
                    .show()
            }
            .setNeutralButton("Clear log") { _, _ ->
                AlertDialog.Builder(this).setTitle("Clear the app payment log?")
                    .setMessage("This deletes only this app's log, not Faseyha history.")
                    .setPositiveButton("Clear") { _, _ -> FaseyhaLedger.clear(this) }
                    .setNegativeButton("Cancel", null).show()
            }
            .setNegativeButton("Close", null).show()
    }

    private fun say(text: String) { status.text = text }

    private fun plain(value: BigDecimal?) = value?.stripTrailingZeros()?.toPlainString() ?: ""

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            screen.isWebShown -> screen.showNative()
            stage == Stage.SUBMITTED || stage == Stage.VERIFYING -> AlertDialog.Builder(this)
                .setTitle("Payment still being checked")
                .setMessage("Leaving stops the automatic check. The payment itself is not cancelled. Check Wallet before paying again.")
                .setPositiveButton("Leave") { _, _ -> finish() }
                .setNegativeButton("Stay", null).show()
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        if (::form.isInitialized) form.destroy()
        if (::verifier.isInitialized) verifier.destroy()
        super.onDestroy()
    }
}

fun Activity.appVersion(): String = try {
    packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
} catch (_: Exception) { "unknown" }
