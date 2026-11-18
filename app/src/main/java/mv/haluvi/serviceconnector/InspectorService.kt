package mv.haluvi.serviceconnector

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class InspectorService : AccessibilityService() {
    companion object {
        @Volatile var requestedPackage: String? = null
        @Volatile var lastSummary: String = "No inspection has been performed."
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val id = ComponentName(context, InspectorService::class.java).flattenToString()
            return enabled.split(':').any { it.equals(id, ignoreCase = true) }
        }
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val target = requestedPackage ?: return
        if (event?.packageName?.toString() != target) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        val root = rootInActiveWindow ?: return
        val counts = mutableMapOf<String, Int>()
        var visited = 0
        val queue: java.util.ArrayDeque<AccessibilityNodeInfo> = java.util.ArrayDeque()
        queue.add(root)
        while (queue.isNotEmpty() && visited < 300) {
            val node = queue.removeFirst(); visited++
            if (node.isClickable) counts["Clickable controls"] = (counts["Clickable controls"] ?: 0) + 1
            if (node.isEditable) counts["Editable fields"] = (counts["Editable fields"] ?: 0) + 1
            if (node.isScrollable) counts["Scrollable regions"] = (counts["Scrollable regions"] ?: 0) + 1
            if (node.isPassword) counts["Protected password fields"] = (counts["Protected password fields"] ?: 0) + 1
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        lastSummary = buildString {
            append("Inspected package: $target\nAccessible nodes inspected: $visited\n")
            for ((label, count) in counts) append("$label: $count\n")
            append("No screen text, passwords, or sensitive field values are recorded. Results describe only the current accessible window.")
        }
    }
    override fun onInterrupt() {}
}
