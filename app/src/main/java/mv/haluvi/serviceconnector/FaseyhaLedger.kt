package mv.haluvi.serviceconnector

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Local log of payments this app submitted on Faseyha and their checked outcome.
 * Used to warn before an accidental repeat. Faseyha Wallet remains the authority.
 * No cookies, passwords or page contents are stored.
 */
object FaseyhaLedger {
    private const val PREFS = "faseyha_manual_ledger"
    private const val KEY = "entries"
    private const val MAX_ENTRIES = 200

    const val SUBMITTED = "Submitted"
    const val SUCCESSFUL = "Successful"
    const val PROCESSING = "Processing"
    const val FAILED = "Failed"
    const val REVIEW = "Needs review"
    const val NOT_SENT = "Not confirmed"

    @Synchronized fun list(context: Context): JSONArray {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]")
        return try { JSONArray(raw ?: "[]") } catch (_: Exception) { JSONArray() }
    }

    private fun save(context: Context, rows: JSONArray) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, rows.toString()).apply()

    @Synchronized fun create(context: Context, service: String, recipient: String, amount: String): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val output = JSONArray().put(JSONObject()
            .put("id", id).put("service", service).put("target", recipient).put("amount", amount)
            .put("status", SUBMITTED).put("created", now).put("updated", now).put("note", "").put("reference", ""))
        val rows = list(context)
        for (i in 0 until minOf(rows.length(), MAX_ENTRIES - 1)) output.put(rows.getJSONObject(i))
        save(context, output)
        return id
    }

    @Synchronized fun update(context: Context, id: String, status: String, note: String, reference: String = "") {
        val rows = list(context)
        for (i in 0 until rows.length()) {
            val item = rows.getJSONObject(i)
            if (item.optString("id") != id) continue
            item.put("status", status).put("updated", System.currentTimeMillis()).put("note", note.take(500))
            if (reference.isNotBlank()) item.put("reference", reference)
            save(context, rows)
            return
        }
    }

    /** A recent payment to the same recipient that was not shown to have failed. */
    @Synchronized fun recentSimilar(context: Context, service: String, recipient: String, withinMs: Long = 30 * 60_000L): JSONObject? {
        val rows = list(context)
        val since = System.currentTimeMillis() - withinMs
        val wanted = FaseyhaRules.normalizeRecipient(recipient)
        for (i in 0 until rows.length()) {
            val item = rows.getJSONObject(i)
            if (item.optLong("created") < since) break
            if (item.optString("service") == service &&
                FaseyhaRules.normalizeRecipient(item.optString("target")) == wanted &&
                item.optString("status") !in setOf(FAILED, NOT_SENT)) return item
        }
        return null
    }

    @Synchronized fun delete(context: Context, id: String) {
        val rows = list(context)
        val next = JSONArray()
        for (i in 0 until rows.length()) rows.getJSONObject(i).let { if (it.optString("id") != id) next.put(it) }
        save(context, next)
    }

    @Synchronized fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}

/**
 * Receipt details read from Faseyha, keyed by receipt reference. A record is stored
 * only when the receipt page's own reference equals the Wallet row's reference.
 */
object FaseyhaReceipts {
    private const val PREFS = "verified_faseyha_receipts"
    private const val KEY = "items"
    private const val MAX = 500
    private val referencePattern = Regex("[A-Za-z0-9]{8,45}")

    @Synchronized fun all(context: Context): MutableMap<String, JSONObject> {
        val result = linkedMapOf<String, JSONObject>()
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "{}") ?: "{}"
        try {
            val obj = JSONObject(raw)
            for (key in obj.keys()) {
                val data = obj.optJSONObject(key) ?: continue
                // Receipts read before format 2 could show the account holder as the recipient; read them again.
                if (data.optInt("v") >= 2 && key.matches(referencePattern) && key.equals(data.optString("reference"), true)) result[key] = data
            }
        } catch (_: Exception) {}
        return result
    }

    @Synchronized fun put(context: Context, reference: String, data: JSONObject) {
        if (!reference.matches(referencePattern) || !reference.equals(data.optString("reference"), true)) return
        val items = all(context)
        items.remove(reference)
        items[reference] = data
        val obj = JSONObject()
        items.entries.toList().takeLast(MAX).forEach { (ref, value) -> obj.put(ref, value) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, obj.toString()).apply()
    }
}
