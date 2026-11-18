package mv.haluvi.serviceconnector

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Private local preparation/observation ledger.
 *
 * Entries are never evidence of provider payment success. No WebView cookies,
 * passwords, screenshots, balance values or provider network responses are retained.
 */
object FaseyhaLedger {
    private const val PREFS = "faseyha_manual_ledger"
    private const val KEY = "entries"
    private const val MAX_ENTRIES = 100
    private val states = setOf("Prepared", "User reports submitted", "Needs review", "User reports completed", "User reports failed")
    @Synchronized fun list(context: Context): JSONArray {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]")
        return try { JSONArray(raw ?: "[]") } catch (_: Exception) { JSONArray() }
    }
    @Synchronized fun create(context: Context, service: String, target: String, amount: String): String {
        val id = UUID.randomUUID().toString()
        val rows = list(context)
        val current = System.currentTimeMillis()
        val entry = JSONObject()
            .put("id", id).put("service", service).put("target", target)
            .put("amount", amount).put("status", "Prepared")
            .put("created", current).put("updated", current)
            .put("note", "Browser form values matched; no payment submitted by this app.")
        val output = JSONArray().put(entry)
        for (i in 0 until minOf(rows.length(), MAX_ENTRIES - 1)) output.put(rows.getJSONObject(i))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, output.toString()).apply()
        return id
    }
    @Synchronized fun update(context: Context, id: String, status: String, note: String): Boolean {
        if (status !in states || note.length > 500) return false
        val rows = list(context)
        var found = false
        for (i in 0 until rows.length()) {
            val item = rows.getJSONObject(i)
            if (item.optString("id") == id) {
                item.put("status", status).put("updated", System.currentTimeMillis())
                item.put("note", note)
                found = true
                break
            }
        }
        if (found) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, rows.toString()).apply()
        return found
    }
    @Synchronized fun delete(context: Context, id: String) {
        val rows = list(context)
        val next = JSONArray()
        for (i in 0 until rows.length()) {
            val item = rows.getJSONObject(i)
            if (item.optString("id") != id) next.put(item)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, next.toString()).apply()
    }
    @Synchronized fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
