package com.nebula.assistant.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.nebula.assistant.notch.NotchController
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Nebula's hands and eyes. Reads what's on screen, taps, types, scrolls and navigates — and
 * every action is verified by looking at the screen again. Also hosts the Nebula Notch overlay.
 */
class NebulaAccessibility : AccessibilityService() {

    @Volatile var foregroundPackage: String = ""
        private set
    @Volatile private var lastEventAt = 0L
    var notch: NotchController? = null
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        notch = NotchController(this).also { it.start() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        lastEventAt = System.currentTimeMillis()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { pkg ->
                if (pkg != packageName && pkg != "com.android.systemui") foregroundPackage = pkg
            }
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            notch?.onVolumeKey()
        }
        return false // never swallow keys
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        notch?.stop(); notch = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ================================================================ screen reading

    /** A visible, meaningful element with a stable number for this snapshot. */
    data class Element(
        val id: Int, val text: String, val role: String, val clickable: Boolean, val editable: Boolean,
        val scrollable: Boolean, val checked: Boolean?, val bounds: Rect, val node: AccessibilityNodeInfo,
    )

    data class Snapshot(val pkg: String, val elements: List<Element>, val signature: Int) {
        fun describe(limit: Int = 140): String {
            val sb = StringBuilder("App: ${appLabelCache[pkg] ?: pkg}\n")
            elements.take(limit).forEach { e ->
                sb.append('[').append(e.id).append("] ").append(e.role)
                if (e.text.isNotBlank()) sb.append(" \"").append(e.text.take(120)).append('"')
                val flags = listOfNotNull(if (e.clickable) "tap" else null, if (e.editable) "type" else null,
                    if (e.scrollable) "scroll" else null, e.checked?.let { if (it) "on" else "off" })
                if (flags.isNotEmpty()) sb.append(" (").append(flags.joinToString(",")).append(')')
                sb.append('\n')
            }
            if (elements.size > limit) sb.append("…and ${elements.size - limit} more elements (scroll to see others)\n")
            if (elements.isEmpty()) sb.append("(No readable elements. The app may draw its own content, or the screen is locked.)\n")
            return sb.toString()
        }
    }

    @Volatile private var last: Snapshot? = null

    fun snapshot(): Snapshot {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        runCatching {
            windows?.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION || it.isActive }
                ?.sortedByDescending { it.isActive }
                ?.mapNotNull { it.root }
                ?.let { roots += it }
        }
        if (roots.isEmpty()) rootInActiveWindow?.let { roots += it }
        val out = mutableListOf<Element>()
        val screen = Rect().also { r -> resources.displayMetrics.let { r.set(0, 0, it.widthPixels, it.heightPixels) } }
        var pkg = ""
        for (root in roots) {
            if (pkg.isEmpty()) pkg = root.packageName?.toString().orEmpty()
            if (root.packageName?.toString() == packageName) continue // skip Nebula's own overlay windows
            walk(root, out, screen, 0)
        }
        val sig = out.joinToString("|") { "${it.role}:${it.text}:${it.checked}" }.hashCode()
        return Snapshot(pkg.ifEmpty { foregroundPackage }, out, sig).also { last = it }
    }

    private fun walk(n: AccessibilityNodeInfo, out: MutableList<Element>, screen: Rect, depth: Int) {
        if (depth > 40 || out.size > 400) return
        if (!n.isVisibleToUser) return
        val b = Rect().also { n.getBoundsInScreen(it) }
        if (!Rect.intersects(b, screen) || b.width() <= 1 || b.height() <= 1) return
        val text = listOfNotNull(n.text?.toString(), n.contentDescription?.toString(),
            if (n.isEditable) n.hintText?.toString()?.let { "hint: $it" } else null)
            .map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }.distinct().joinToString(" · ")
        val interesting = text.isNotEmpty() || n.isClickable || n.isEditable || n.isScrollable || n.isCheckable
        if (interesting) {
            out += Element(out.size + 1, text, role(n), n.isClickable || n.isLongClickable, n.isEditable, n.isScrollable,
                if (n.isCheckable) n.isChecked else null, b, n)
        }
        for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, out, screen, depth + 1) }
    }

    private fun role(n: AccessibilityNodeInfo): String {
        val cls = n.className?.toString().orEmpty().substringAfterLast('.')
        return when {
            n.isEditable -> "text field"
            cls.contains("Switch", true) -> "switch"
            cls.contains("CheckBox", true) -> "checkbox"
            cls.contains("Button", true) -> "button"
            cls.contains("Image", true) && n.isClickable -> "icon button"
            cls.contains("Image", true) -> "image"
            n.isScrollable -> "list"
            n.isClickable -> "item"
            else -> "text"
        }
    }

    /** Element by number from the last snapshot, or by matching words (e.g. "settings", "search"). */
    fun find(target: String): Element? {
        val snap = last ?: snapshot()
        target.trim().removePrefix("#").removePrefix("[").removeSuffix("]").toIntOrNull()?.let { id ->
            return snap.elements.firstOrNull { it.id == id }
        }
        val q = target.lowercase().trim().removePrefix("the ").removeSuffix(" button").removeSuffix(" field")
        if (q.isEmpty()) return null
        fun score(e: Element): Int {
            val t = e.text.lowercase()
            val id = e.node.viewIdResourceName?.substringAfter('/')?.lowercase().orEmpty()
            var s = when {
                t == q -> 100
                t.startsWith(q) -> 80
                Regex("\\b${Regex.escape(q)}\\b").containsMatchIn(t) -> 70
                t.contains(q) -> 50
                id == q || id.replace('_', ' ') == q -> 65
                id.contains(q.replace(' ', '_')) -> 40
                q.split(' ').all { w -> t.contains(w) } -> 35
                else -> 0
            }
            if (s > 0 && (e.clickable || e.editable)) s += 10
            return s
        }
        return snap.elements.map { it to score(it) }.filter { it.second > 0 }.maxByOrNull { it.second }?.first
    }

    // ================================================================ actions (all verified)

    data class ActionResult(val ok: Boolean, val verified: Boolean, val message: String)

    suspend fun tap(target: String): ActionResult {
        val before = snapshot()
        val e = find(target) ?: return ActionResult(false, false, "I couldn't find “$target” on screen.")
        var node: AccessibilityNodeInfo? = e.node
        var clicked = false
        while (node != null && !clicked) {
            if (node.isClickable) clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!clicked) node = node.parent
        }
        if (!clicked) clicked = gestureTap(e.bounds.exactCenterX(), e.bounds.exactCenterY())
        if (!clicked) return ActionResult(false, false, "Tapping “${e.text.ifBlank { e.role }}” didn't work.")
        val changed = waitForChange(before.signature, 2500)
        return ActionResult(true, changed,
            if (changed) "Tapped “${e.text.ifBlank { e.role }}”; the screen changed." else "Tapped “${e.text.ifBlank { e.role }}”, but the screen didn't visibly change.")
    }

    suspend fun type(target: String?, text: String, submit: Boolean): ActionResult {
        snapshot()
        val e = (target?.takeIf { it.isNotBlank() }?.let { find(it) })
            ?: last?.elements?.firstOrNull { it.editable && it.node.isFocused }
            ?: last?.elements?.firstOrNull { it.editable }
            ?: return ActionResult(false, false, "There's no text field on screen to type into.")
        if (!e.node.isFocused) e.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        val ok = e.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        delay(250)
        e.node.refresh()
        val now = e.node.text?.toString().orEmpty()
        val verified = ok && (now.contains(text.take(20)) || now == text)
        if (!ok) return ActionResult(false, false, "That field didn't accept text.")
        if (submit) {
            val entered = android.os.Build.VERSION.SDK_INT >= 30 &&
                e.node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            if (!entered) {
                // Fall back to a nearby send/search button.
                find("send")?.node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: find("search")?.node?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            delay(400)
        }
        return ActionResult(true, verified, if (verified) "Typed “${text.take(60)}”." else "Typed, but couldn't confirm the text appeared.")
    }

    suspend fun scroll(direction: String, target: String?): ActionResult {
        val before = snapshot()
        val forward = direction.lowercase() in setOf("down", "forward", "right", "next", "more")
        val node = target?.let { find(it)?.node }
            ?: before.elements.filter { it.scrollable }.maxByOrNull { it.bounds.width() * it.bounds.height() }?.node
        var done = node?.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) ?: false
        if (!done) {
            val dm = resources.displayMetrics
            val x = dm.widthPixels / 2f
            val (y1, y2) = if (forward) dm.heightPixels * 0.72f to dm.heightPixels * 0.28f else dm.heightPixels * 0.28f to dm.heightPixels * 0.72f
            done = swipe(x, y1, x, y2)
        }
        val changed = waitForChange(before.signature, 1800)
        return when {
            !done -> ActionResult(false, false, "I couldn't scroll here.")
            changed -> ActionResult(true, true, "Scrolled $direction.")
            else -> ActionResult(true, false, "Scrolled $direction, but nothing new appeared — probably the end of the list.")
        }
    }

    suspend fun global(action: String): ActionResult {
        val before = snapshot()
        val code = when (action) {
            "back" -> GLOBAL_ACTION_BACK; "home" -> GLOBAL_ACTION_HOME; "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS; "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "lock" -> GLOBAL_ACTION_LOCK_SCREEN; "screenshot" -> GLOBAL_ACTION_TAKE_SCREENSHOT
            else -> return ActionResult(false, false, "Unknown navigation “$action”.")
        }
        val ok = performGlobalAction(code)
        val changed = waitForChange(before.signature, 2000)
        return ActionResult(ok, ok && (changed || action in setOf("screenshot", "lock")),
            if (ok) "Done: $action." else "Android refused “$action”.")
    }

    /** True once the screen content differs from [signature]. */
    suspend fun waitForChange(signature: Int, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            delay(180)
            if (snapshot().signature != signature) return true
        }
        return false
    }

    /** Waits until an element matching [target] shows up (e.g. after opening an app). */
    suspend fun waitFor(target: String, timeoutMs: Long): Element? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            snapshot()
            find(target)?.let { return it }
            delay(250)
        }
        return null
    }

    suspend fun waitForPackage(pkg: String, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (foregroundPackage == pkg || rootInActiveWindow?.packageName?.toString() == pkg) return true
            delay(200)
        }
        return false
    }

    private suspend fun gestureTap(x: Float, y: Float): Boolean = gesture(Path().apply { moveTo(x, y) }, 60)
    private suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float): Boolean =
        gesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, 320)

    private suspend fun gesture(path: Path, ms: Long): Boolean = withTimeoutOrNull(3000) {
        suspendCancellableCoroutine { cont ->
            val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, ms)).build()
            val ok = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                override fun onCancelled(d: GestureDescription?) { if (cont.isActive) cont.resume(false) }
            }, null)
            if (!ok && cont.isActive) cont.resume(false)
        }
    } ?: false

    companion object {
        @Volatile var instance: NebulaAccessibility? = null
            private set
        val appLabelCache = mutableMapOf<String, String>()

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = "${context.packageName}/${NebulaAccessibility::class.java.name}"
            val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
            return splitter.any { it.equals(me, true) || it.equals("${context.packageName}/.device.NebulaAccessibility", true) }
        }
    }
}
