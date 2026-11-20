package mv.haluvi.serviceconnector

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Native Faseyha Wallet: balance, Recharges/Payments history, receipt details and,
 * when opened from a payment, a check for that payment. A hidden, read-only
 * Faseyha page supplies the data; it never presses a payment control.
 */
class FaseyhaWalletActivity : Activity() {
    companion object {
        const val VERIFY_SERVICE = "verify_service_index"
        const val VERIFY_RECIPIENT = "verify_recipient"
        const val VERIFY_AMOUNT = "verify_amount"
        const val VERIFY_STARTED_AT = "verify_started_at"
        private const val RECEIPT_BATCH = 30
    }

    private lateinit var screen: EngineScreen
    private lateinit var page: FaseyhaEngine
    private lateinit var receipts: FaseyhaEngine
    private lateinit var balanceText: TextView
    private lateinit var status: TextView
    private lateinit var categoryButton: Button
    private lateinit var list: LinearLayout

    private var category = "Recharges"
    private var limit = 50
    private var busy = false
    private var autoLoads = 0
    private var previousCount = -1
    private val records = linkedMapOf<String, JSONObject>()
    private lateinit var receiptCache: MutableMap<String, JSONObject>
    private val receiptQueue = ArrayDeque<Pair<String, String>>()
    private var automaticReads = 0
    private var receiptBusy = false
    private var manualReceipt: String? = null
    private var signedOut = false
    private var signInPrompted = false
    private var resumedOnce = false

    private val verifyService by lazy { FaseyhaRules.SERVICES.getOrNull(intent.getIntExtra(VERIFY_SERVICE, -1)) }
    private val verifyRecipient by lazy { intent.getStringExtra(VERIFY_RECIPIENT).orEmpty() }
    private val verifyAmount by lazy { FaseyhaRules.money(intent.getStringExtra(VERIFY_AMOUNT)) }
    private val verifyStartedAt by lazy { intent.getLongExtra(VERIFY_STARTED_AT, 0L) }
    private var verificationAnnounced = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        receiptCache = FaseyhaReceipts.all(this)
        screen = EngineScreen(this, "Faseyha Wallet")
        balanceText = screen.text("Balance: loading…", 20f, live = true)
        status = screen.text("Opening Wallet…", 17f, live = true)
        categoryButton = screen.button("Category: Recharges. Change") { chooseCategory() }
        screen.button("Refresh") { refresh() }
        screen.button("Load older transactions") { loadMore() }
        lateinit var moreToggle: Button
        lateinit var more: LinearLayout
        moreToggle = screen.button("More options") {
            more.visibility = if (more.visibility == View.GONE) View.VISIBLE else View.GONE
            moreToggle.text = if (more.visibility == View.VISIBLE) "Hide more options" else "More options"
        }
        more = screen.section()
        screen.button("Show Wallet page", more) { screen.showWeb(page, "Faseyha Wallet page") }
        lateinit var limitButton: Button
        limitButton = screen.button("Maximum records: $limit", more) { chooseLimit(limitButton) }
        screen.button("Open Faseyha website", more) { openFaseyhaWebsite("/wallet") }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        screen.panel.addView(list)

        page = FaseyhaEngine(this) { onPageLoaded(it) }
        receipts = FaseyhaEngine(this) { onReceiptLoaded(it) }
        screen.attach(receipts)
        screen.attach(page)
        screen.onNativeShown = {
            manualReceipt = null
            render()
            if (page.path != "/wallet") page.open("/wallet")
        }
        setContentView(screen.root)
        page.open("/wallet")
    }

    override fun onResume() {
        super.onResume()
        if (!resumedOnce) { resumedOnce = true; return }
        if (signedOut) { signedOut = false; refresh(); return }
        // Receipts read by the payment screen meanwhile.
        receiptCache.putAll(FaseyhaReceipts.all(this))
        if (records.isNotEmpty() && !screen.isWebShown) { render(); checkRequested() }
    }

    // ---------- page loading ----------

    private fun onPageLoaded(url: String) {
        val path = FaseyhaSite.pathOf(url)
        when {
            path == "/wallet" -> page.after(350) { selectCategory { scan() } }
            FaseyhaSite.isReceiptUrl(url) -> copyReceipt?.let { ref -> page.after(500) { copyReceiptText(ref) } }
                ?: page.after(300) { inspectManualReceipt() }
            else -> page.eval(FaseyhaSite.SIGNED_OUT) { r ->
                if (r?.optBoolean("signedOut") == true || path == null) handleSignedOut()
                else status.text = "Faseyha opened ${path} instead of Wallet."
            }
        }
    }

    private fun refresh() {
        autoLoads = 0; previousCount = -1; busy = false
        records.clear(); list.removeAllViews()
        status.text = "Refreshing Wallet…"
        page.open("/wallet")
    }

    private fun chooseCategory() {
        val options = arrayOf("Recharges", "Payments")
        AlertDialog.Builder(this).setTitle("Wallet category")
            .setSingleChoiceItems(options, options.indexOf(category)) { dialog, i ->
                dialog.dismiss()
                category = options[i]
                categoryButton.text = "Category: $category. Change"
                categoryButton.contentDescription = "Wallet category, selected $category. Double tap to change."
                autoLoads = 0; previousCount = -1
                records.clear(); list.removeAllViews()
                if (page.path == "/wallet") selectCategory { scan() } else page.open("/wallet")
            }.setNegativeButton("Cancel", null).show()
    }

    private fun chooseLimit(button: Button) {
        val values = arrayOf(20, 50, 100, 200, 500)
        AlertDialog.Builder(this).setTitle("Maximum transaction records")
            .setSingleChoiceItems(values.map { "Latest $it" }.toTypedArray(), values.indexOf(limit)) { d, i ->
                d.dismiss()
                limit = values[i]
                button.text = "Maximum records: $limit"
                status.text = "Showing up to $limit records. Use Load older transactions for more."
            }.setNegativeButton("Cancel", null).show()
    }

    private fun selectCategory(after: () -> Unit) {
        page.eval(FaseyhaSite.walletTab(category)) { r ->
            if (r?.optBoolean("ok") == true) page.after(350) { if (page.path == "/wallet") after() }
            else status.text = "Couldn't open the $category tab automatically. Use Show Wallet page to check Faseyha."
        }
    }

    private fun scan() {
        page.eval(FaseyhaSite.WALLET_SCAN) { r ->
            if (r == null) { status.text = "Faseyha's Wallet could not be read. Try Refresh."; return@eval }
            if (r.optBoolean("signedOut")) { handleSignedOut(); return@eval }
            val rows = r.optJSONArray("rows") ?: JSONArray()
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val key = row.optString("reference").ifBlank { row.optString("url") }
                if (key.isNotBlank() && (records.containsKey(key) || records.size < limit)) records[key] = row
            }
            val balance = FaseyhaRules.money(r.optString("balance"))
            balanceText.text = balance?.let { "Balance: MVR ${FaseyhaRules.format(it)}" } ?: "Balance: not shown"
            status.text = "$category: ${records.size} transactions" + if (r.optBoolean("more")) ". Older ones available." else "."
            render()
            if (category == "Recharges") enqueueMissingReceipts()
            checkRequested()
            if (r.optBoolean("more") && records.size < limit && autoLoads < 6 && records.size > previousCount) {
                previousCount = records.size
                autoLoads++
                page.after(700) { if (!busy && records.size < limit) loadMore() }
            }
        }
    }

    private fun loadMore() {
        if (page.path != "/wallet") { status.text = "Opening Wallet…"; page.open("/wallet"); return }
        if (busy) { status.text = "Already loading."; return }
        if (records.size >= limit) { status.text = "Reached the maximum of $limit records."; return }
        busy = true
        page.eval(FaseyhaSite.LOAD_MORE) { r ->
            if (r?.optBoolean("ok") != true) { busy = false; status.text = "No older transactions are available." }
            else page.after(900) { busy = false; if (page.path == "/wallet") scan() }
        }
    }

    private fun handleSignedOut() {
        signedOut = true
        balanceText.text = "Balance: signed out"
        status.text = "You are signed out of Faseyha. Choose Open Faseyha website under More options, sign in, then come back."
        if (signInPrompted || isFinishing) return
        signInPrompted = true
        AlertDialog.Builder(this).setTitle("Sign in to Faseyha")
            .setMessage("Faseyha needs you to sign in before the Wallet can be shown.")
            .setPositiveButton("Open website") { _, _ -> openFaseyhaWebsite("/wallet") }
            .setNegativeButton("Later", null).show()
    }

    // ---------- list and details ----------

    private fun amountOf(row: JSONObject): String =
        receiptCache[row.optString("reference")]?.optString("amount")?.ifBlank { null }
            ?: row.optString("amount").ifBlank { "" }

    private fun summaryOf(row: JSONObject): String {
        val account = receiptCache[row.optString("reference")]?.optString("account").orEmpty()
        val money = amountOf(row)
        return (if (category == "Payments") "Wallet deposit. " else "") + row.optString("service", "Unknown service") + ". " +
            (if (account.isNotBlank()) "$account. " else if (category == "Payments") "" else "Recipient not identified. ") +
            (if (money.isBlank()) "Amount not shown. " else "MVR $money. ") +
            row.optString("status", "Status not shown") + ". " + row.optString("date")
    }

    private fun render() {
        list.removeAllViews()
        if (records.isEmpty()) {
            list.addView(TextView(this).apply { text = "No transactions found yet."; textSize = 16f })
            return
        }
        records.values.forEach { row ->
            val summary = summaryOf(row)
            list.addView(Button(this).apply {
                minHeight = screen.dp(56)
                text = summary
                contentDescription = "$summary. Double tap for details."
                setOnClickListener { detail(row) }
            })
        }
    }

    private fun detail(row: JSONObject) {
        val ref = row.optString("reference")
        val receipt = receiptCache[ref]
        val money = amountOf(row)
        val text = "Service: " + row.optString("service") +
            "\nRecipient: " + (receipt?.optString("account")?.ifBlank { null } ?: "Not identified") +
            "\nAmount: " + (if (money.isBlank()) "Not shown" else "MVR $money") +
            "\nStatus: " + row.optString("status") +
            (receipt?.optString("status")?.takeIf { it.isNotBlank() }?.let { "\nReceipt: $it" } ?: "") +
            "\nDate: " + row.optString("date") +
            "\nReference: " + ref
        val url = row.optString("url")
        AlertDialog.Builder(this).setTitle(row.optString("service").ifBlank { "Transaction" })
            .setMessage(text)
            .apply {
                if (FaseyhaSite.isReceiptUrl(url)) setPositiveButton("View original receipt") { _, _ ->
                    manualReceipt = ref
                    page.openUrl(url)
                    screen.showWeb(page, "Faseyha receipt $ref")
                }
            }
            .apply {
                if (FaseyhaSite.isReceiptUrl(url)) setNeutralButton("Copy receipt text") { _, _ ->
                    copyReceipt = ref
                    status.text = "Reading the receipt text…"
                    page.openUrl(url)
                }
            }
            .setNegativeButton("Close", null).show()
    }

    private var copyReceipt: String? = null

    /** Copies a receipt's visible text so it can be shared for support. */
    private fun copyReceiptText(ref: String, attempt: Int = 0) {
        page.eval(FaseyhaSite.RECEIPT_TEXT) { r ->
            val text = r?.optString("text").orEmpty()
            if (!text.contains(ref, true) && attempt < 10) { page.after(700) { copyReceiptText(ref, attempt + 1) }; return@eval }
            copyReceipt = null
            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText("Faseyha receipt", "Faseyha receipt ${r?.optString("path")}\n\n$text"))
            android.widget.Toast.makeText(this, "Receipt text copied", android.widget.Toast.LENGTH_SHORT).show()
            status.text = "Receipt text copied. Paste it in a message to share it."
            page.open("/wallet")
        }
    }

    private fun inspectManualReceipt(attempt: Int = 0) {
        val ref = manualReceipt ?: return
        page.eval(FaseyhaSite.receiptRead(ref)) { d ->
            if (d?.optString("reference").isNullOrBlank() && attempt < 10) { page.after(700) { inspectManualReceipt(attempt + 1) }; return@eval }
            if (d == null || !ref.equals(d.optString("reference"), true)) return@eval
            receiptCache[ref] = d
            FaseyhaReceipts.put(this, ref, d)
        }
    }

    // ---------- automatic receipt details ----------

    private fun enqueueMissingReceipts() {
        if (receiptBusy || automaticReads >= RECEIPT_BATCH) return
        val queued = receiptQueue.map { it.first }.toSet()
        for ((ref, row) in records) {
            if (receiptCache.containsKey(ref) || ref in queued) continue
            val url = row.optString("url")
            if (!FaseyhaSite.isReceiptUrl(url)) continue
            receiptQueue.addLast(ref to url)
            if (receiptQueue.size >= RECEIPT_BATCH - automaticReads) break
        }
        startAutomaticReceipt()
    }

    private fun startAutomaticReceipt() {
        if (receiptBusy || receiptQueue.isEmpty() || automaticReads >= RECEIPT_BATCH) return
        receiptBusy = true
        automaticReads++
        receipts.openUrl(receiptQueue.first().second)
    }

    private fun onReceiptLoaded(url: String) {
        if (FaseyhaSite.isReceiptUrl(url)) receipts.after(500) { inspectAutomaticReceipt() } else finishAutomaticReceipt()
    }

    private fun inspectAutomaticReceipt(attempt: Int = 0) {
        val selected = receiptQueue.firstOrNull() ?: return
        receipts.eval(FaseyhaSite.receiptRead(selected.first)) { d ->
            // Wait for the receipt page to fill in its receipt number before reading it.
            if (d?.optString("reference").isNullOrBlank() && attempt < 10) {
                receipts.after(700) { inspectAutomaticReceipt(attempt + 1) }; return@eval
            }
            if (d != null && selected.first.equals(d.optString("reference"), true)) {
                receiptCache[selected.first] = d
                FaseyhaReceipts.put(this, selected.first, d)
                // Show each receipt's recipient and amount as soon as it is read.
                if (!screen.isWebShown) render()
                checkRequested()
            }
            finishAutomaticReceipt()
        }
    }

    private fun finishAutomaticReceipt() {
        if (receiptQueue.isNotEmpty()) receiptQueue.removeFirst()
        receiptBusy = false
        if (automaticReads < RECEIPT_BATCH && receiptQueue.isNotEmpty()) receipts.after(450) { startAutomaticReceipt() }
        else {
            if (!screen.isWebShown) render()
            checkRequested()
        }
    }

    // ---------- payment check requested by the payment screen ----------

    private fun checkRequested() {
        val service = verifyService ?: return
        if (verificationAnnounced || category != "Recharges" || verifyRecipient.isBlank()) return
        val expected = verifyAmount
        val possible = records.values.filter { row ->
            val receipt = receiptCache[row.optString("reference")] ?: return@filter false
            FaseyhaRules.walletRowMatches(service, row.optString("service"), row.optString("amount"),
                row.optString("date"), expected, verifyStartedAt) &&
                FaseyhaRules.normalizeRecipient(receipt.optString("account")) == FaseyhaRules.normalizeRecipient(verifyRecipient) &&
                FaseyhaRules.sameMoney(FaseyhaRules.money(receipt.optString("amount")) ?: FaseyhaRules.money(row.optString("amount")), expected)
        }
        when {
            possible.size == 1 -> {
                val row = possible.first()
                verificationAnnounced = true
                status.text = "Found your payment. Status: ${row.optString("status")}."
                AlertDialog.Builder(this).setTitle("Your payment in Faseyha Wallet")
                    .setMessage("Service: ${row.optString("service")}\nRecipient: $verifyRecipient\n" +
                        "Amount: MVR ${FaseyhaRules.format(expected)}\nStatus: ${row.optString("status")}\n" +
                        "Date: ${row.optString("date")}\nReference: ${row.optString("reference")}")
                    .setPositiveButton("OK", null).show()
            }
            possible.size > 1 -> {
                verificationAnnounced = true
                status.text = "More than one recent transaction matches your payment. Compare their references."
            }
            !receiptBusy && receiptQueue.isEmpty() && automaticReads > 0 ->
                status.text = "Your payment is not in the Wallet list yet. Refresh in a minute before paying again."
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (screen.isWebShown) screen.showNative() else super.onBackPressed()
    }

    override fun onDestroy() {
        receiptQueue.clear()
        if (::receipts.isInitialized) receipts.destroy()
        if (::page.isInitialized) page.destroy()
        super.onDestroy()
    }
}
