package com.nebula.assistant.agent

import android.content.Context
import com.nebula.assistant.data.InputRequest
import com.nebula.assistant.data.NebulaPrefs
import com.nebula.assistant.data.Source
import com.nebula.assistant.data.StepStatus
import com.nebula.assistant.data.Store
import com.nebula.assistant.data.TaskStep
import com.nebula.assistant.device.NebulaAccessibility
import com.nebula.assistant.device.PhoneActions
import com.nebula.assistant.docs.DocJson
import com.nebula.assistant.docs.DocType
import com.nebula.assistant.docs.DocumentStore
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.util.Currency
import java.util.Locale

/** What a tool call produced: text for the AI, a verified step for the user, and control signals. */
data class ToolOutcome(
    val content: String,
    val step: TaskStep? = null,
    val pause: InputRequest? = null,
    val finish: Pair<String, String>? = null,
    val fileIds: List<String> = emptyList(),
    val sources: List<Source> = emptyList(),
    val plan: List<String>? = null,
)

object ToolDefs {
    private fun fn(name: String, desc: String, props: JSONObject, required: List<String> = emptyList()) = JSONObject()
        .put("type", "function").put("function", JSONObject().put("name", name).put("description", desc)
            .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(required))))
    private fun s(desc: String) = JSONObject().put("type", "string").put("description", desc)
    private fun i(desc: String) = JSONObject().put("type", "integer").put("description", desc)
    private fun b(desc: String) = JSONObject().put("type", "boolean").put("description", desc)
    private fun arr(desc: String) = JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", desc)
    private fun obj(desc: String) = JSONObject().put("type", "object").put("description", desc)

    /** Tools that change something (or read the screen): using one turns the request into a tracked task. */
    val ACTION = setOf("plan", "read_screen", "tap", "type_text", "scroll", "navigate", "open_app", "open_url", "send_sms",
        "send_whatsapp", "call", "set_timer", "set_alarm", "flashlight", "create_document", "update_document", "ask_user")

    val all: JSONArray = JSONArray().apply {
        put(fn("plan", "Show the user your step-by-step plan for a multi-step task. Call first for tasks with 2+ steps.",
            JSONObject().put("steps", arr("Short step titles, e.g. 'Find Mom in contacts'")), listOf("steps")))
        put(fn("web_search", "Search the web for current facts (news, weather, prices, people, places).",
            JSONObject().put("query", s("Search query")), listOf("query")))
        put(fn("read_screen", "Read the elements on the current screen as a numbered list. Use the numbers with tap/type_text.", JSONObject()))
        put(fn("tap", "Tap an element by its [number] from read_screen, or by its visible text/label.",
            JSONObject().put("target", s("e.g. '12' or 'Settings'")), listOf("target")))
        put(fn("type_text", "Type into a text field (by [number] or label; omit to use the focused field).",
            JSONObject().put("text", s("Text to type")).put("target", s("Field number or label")).put("submit", b("Press enter/send after typing")), listOf("text")))
        put(fn("scroll", "Scroll the screen or a list.", JSONObject().put("direction", s("down|up")).put("target", s("Optional list number")), listOf("direction")))
        put(fn("navigate", "System navigation.", JSONObject().put("action", s("back|home|recents|notifications|quick_settings|screenshot")), listOf("action")))
        put(fn("open_app", "Open an installed app by name.", JSONObject().put("name", s("App name")), listOf("name")))
        put(fn("open_url", "Open a web address or deep link.", JSONObject().put("url", s("URL")), listOf("url")))
        put(fn("wait", "Wait a moment for the screen to load.", JSONObject().put("seconds", i("1-5")), listOf("seconds")))
        put(fn("find_contact", "Look up a contact's phone numbers.", JSONObject().put("name", s("Contact name")), listOf("name")))
        put(fn("send_sms", "Send an SMS. The user is asked to approve first; the result is confirmed by the network.",
            JSONObject().put("phone", s("Number")).put("message", s("Message text")).put("contact_name", s("Who it's for")), listOf("phone", "message")))
        put(fn("send_whatsapp", "Send a WhatsApp message (needs screen control). User approves first; verified on screen.",
            JSONObject().put("phone", s("Number")).put("message", s("Message text")).put("contact_name", s("Who it's for")), listOf("phone", "message")))
        put(fn("call", "Phone call. User approves first.", JSONObject().put("phone", s("Number")).put("contact_name", s("Who")), listOf("phone")))
        put(fn("set_timer", "Start a countdown timer.", JSONObject().put("seconds", i("Duration in seconds")).put("label", s("Label")), listOf("seconds")))
        put(fn("set_alarm", "Set an alarm.", JSONObject().put("hour", i("0-23")).put("minute", i("0-59")).put("label", s("Label")), listOf("hour", "minute")))
        put(fn("flashlight", "Turn the flashlight on or off.", JSONObject().put("on", b("true = on")), listOf("on")))
        put(fn("create_document", "Create a real file (docx, xlsx, pptx, pdf, csv, txt, md, html) from a spec. ${DocJson.SCHEMA_HINT}",
            JSONObject().put("spec", obj("The document spec")), listOf("spec")))
        put(fn("get_document", "Get the full spec of a file Nebula made, before editing it.", JSONObject().put("file_id", s("File id")), listOf("file_id")))
        put(fn("update_document", "Replace a file's content with an edited spec (same schema as create_document).",
            JSONObject().put("file_id", s("File id")).put("spec", obj("Complete edited spec")), listOf("file_id", "spec")))
        put(fn("ask_user", "Ask the user a question with tappable options when you need information or a choice. Pauses the task.",
            JSONObject().put("question", s("Question")).put("options", arr("2-6 options")).put("multiple", b("Allow several choices")), listOf("question")))
        put(fn("finish_task", "End the task and report the real result. Only say 'completed' if your tools confirmed success.",
            JSONObject().put("outcome", s("completed|failed|cancelled")).put("summary", s("What actually happened, in one or two sentences")), listOf("outcome", "summary")))
    }
}

/** Runs tool calls against the real device. */
class ToolExecutor(private val context: Context, private val prefs: NebulaPrefs, private val groq: GroqClient) {

    private val phone = PhoneActions(context)
    private val docs = DocumentStore(context)

    private fun step(label: String, ok: Boolean, verified: Boolean, detail: String) =
        TaskStep(label = label, status = if (ok) StepStatus.DONE else StepStatus.FAILED, detail = detail, verified = ok && verified)

    private fun result(ok: Boolean, verified: Boolean, message: String, extra: JSONObject? = null): String =
        (extra ?: JSONObject()).put("ok", ok).put("verified", ok && verified).put("result", message).toString()

    private val sensitiveTap = Regex("\\b(pay|buy|purchase|order|checkout|check out|transfer|send money|delete|remove|uninstall|erase|reset|subscribe|confirm payment|place order)\\b", RegexOption.IGNORE_CASE)

    suspend fun run(name: String, args: JSONObject, callId: String, taskId: String?): ToolOutcome {
        return try { exec(name, args, callId, taskId) } catch (e: Exception) {
            ToolOutcome(result(false, false, "Tool error: ${e.message}"), step("$name failed", false, false, e.message ?: "error"))
        }
    }

    private suspend fun exec(name: String, a: JSONObject, callId: String, taskId: String?): ToolOutcome = when (name) {
        "plan" -> {
            val steps = (0 until (a.optJSONArray("steps")?.length() ?: 0)).map { a.getJSONArray("steps").optString(it) }.filter { it.isNotBlank() }
            ToolOutcome(result(true, true, "Plan shown to the user."), plan = steps)
        }
        "web_search" -> {
            val q = a.optString("query")
            val (text, sources) = groq.webSearch(q)
            ToolOutcome(text.take(3500).ifBlank { "No results." }, sources = sources)
        }
        "read_screen" -> {
            val ax = NebulaAccessibility.instance ?: return needsAccess("read the screen")
            val snap = ax.snapshot()
            ToolOutcome(snap.describe(110).take(4500), step("Read the screen", true, true, "${snap.elements.size} elements in ${NebulaAccessibility.appLabelCache[snap.pkg] ?: snap.pkg}"))
        }
        "tap" -> {
            val ax = NebulaAccessibility.instance ?: return needsAccess("tap on the screen")
            val target = a.optString("target")
            val el = ax.find(target)
            val label = el?.text?.ifBlank { el.role } ?: target
            if (sensitiveTap.containsMatchIn(label) && a.optBoolean("_approved").not()) {
                return ToolOutcome("Waiting for the user to approve tapping “$label”.", pause = InputRequest("confirm",
                    "Tap “$label”? This could buy, pay, send or delete something.", listOf("Tap it", "Cancel"),
                    pendingAction = JSONObject().put("tool", "tap").put("args", JSONObject(a.toString()).put("_approved", true)), toolCallId = callId),
                    step = TaskStep(label = "Approval needed to tap “$label”", status = StepStatus.WAITING))
            }
            val r = ax.tap(target)
            ToolOutcome(result(r.ok, r.verified, r.message), step("Tap “${label.take(40)}”", r.ok, r.verified, r.message))
        }
        "type_text" -> {
            val ax = NebulaAccessibility.instance ?: return needsAccess("type on the screen")
            val r = ax.type(a.optString("target").takeIf { it.isNotBlank() }, a.optString("text"), a.optBoolean("submit"))
            ToolOutcome(result(r.ok, r.verified, r.message), step("Type “${a.optString("text").take(30)}”", r.ok, r.verified, r.message))
        }
        "scroll" -> {
            val ax = NebulaAccessibility.instance ?: return needsAccess("scroll")
            val r = ax.scroll(a.optString("direction", "down"), a.optString("target").takeIf { it.isNotBlank() })
            ToolOutcome(result(r.ok, r.verified, r.message), step("Scroll ${a.optString("direction", "down")}", r.ok, r.verified, r.message))
        }
        "navigate" -> {
            val ax = NebulaAccessibility.instance ?: return needsAccess("navigate")
            val r = ax.global(a.optString("action"))
            ToolOutcome(result(r.ok, r.verified, r.message), step("Go ${a.optString("action")}", r.ok, r.verified, r.message))
        }
        "open_app" -> {
            val r = phone.openApp(a.optString("name"))
            ToolOutcome(result(r.ok, r.verified, r.message), step("Open ${a.optString("name")}", r.ok, r.verified, r.message))
        }
        "open_url" -> {
            val r = phone.openUrl(a.optString("url"))
            // Android accepted the link; a browser/app is opening it.
            ToolOutcome(result(r.ok, r.ok, r.message), step("Open link", r.ok, r.ok, r.message))
        }
        "wait" -> { delay(a.optInt("seconds", 2).coerceIn(1, 5) * 1000L); ToolOutcome(result(true, true, "Waited.")) }
        "find_contact" -> {
            val (list, need) = phone.findContacts(a.optString("name"))
            if (need != null) return ToolOutcome(result(false, false, "Contacts permission is off. Ask the user to allow it in Nebula > Settings > Permissions."),
                step("Look up ${a.optString("name")}", false, false, "Contacts permission needed"))
            val arr = JSONArray(list.take(8).map { JSONObject().put("name", it.name).put("number", it.number).put("type", it.label) })
            ToolOutcome(JSONObject().put("ok", true).put("matches", arr).put("count", list.size).toString(),
                step("Find “${a.optString("name")}” in contacts", list.isNotEmpty(), list.isNotEmpty(),
                    if (list.isEmpty()) "No contact found" else list.first().let { "${it.name} · ${it.number}" }))
        }
        "send_sms", "send_whatsapp", "call" -> sensitiveSend(name, a, callId)
        "set_timer" -> phone.setTimer(a.optInt("seconds"), a.optString("label")).let {
            ToolOutcome(result(it.ok, it.verified, it.message), step("Set timer", it.ok, it.verified, it.message)) }
        "set_alarm" -> phone.setAlarm(a.optInt("hour"), a.optInt("minute"), a.optString("label")).let {
            ToolOutcome(result(it.ok, it.verified, it.message), step("Set alarm", it.ok, it.verified, it.message)) }
        "flashlight" -> phone.torch(a.optBoolean("on", true)).let {
            ToolOutcome(result(it.ok, it.verified, it.message), step(if (a.optBoolean("on", true)) "Flashlight on" else "Flashlight off", it.ok, it.verified, it.message)) }
        "create_document" -> {
            val specJ = a.optJSONObject("spec") ?: runCatching { JSONObject(a.optString("spec")) }.getOrNull()
                ?: return ToolOutcome(result(false, false, "Missing document spec."))
            val spec = DocJson.parse(specJ, currency = currencySymbol())
            val r = docs.create(spec, taskId)
            ToolOutcome(result(r.ok, r.ok, r.message, JSONObject().put("file_id", r.file?.id ?: JSONObject.NULL)),
                step("Create ${spec.type.label}", r.ok, r.ok, r.message), fileIds = listOfNotNull(r.file?.id))
        }
        "get_document" -> {
            val id = a.optString("file_id")
            val spec = docs.spec(id) ?: return ToolOutcome(result(false, false, "No editable spec for that file id."))
            ToolOutcome(spec.toString().take(7000))
        }
        "update_document" -> {
            val id = a.optString("file_id")
            val rec = Store.file(id) ?: return ToolOutcome(result(false, false, "Unknown file id."))
            val specJ = a.optJSONObject("spec") ?: return ToolOutcome(result(false, false, "Missing spec."))
            val spec = DocJson.parse(specJ, DocType.parse(rec.ext), currencySymbol())
            val r = docs.update(id, spec)
            ToolOutcome(result(r.ok, r.ok, r.message, JSONObject().put("file_id", id)), step("Update ${rec.fileName}", r.ok, r.ok, r.message), fileIds = listOf(id))
        }
        "ask_user" -> {
            val opts = (0 until (a.optJSONArray("options")?.length() ?: 0)).map { a.getJSONArray("options").optString(it) }.filter { it.isNotBlank() }
            ToolOutcome("Waiting for the user's answer.", pause = InputRequest(if (opts.isEmpty()) "text" else "choice", a.optString("question"),
                opts, a.optBoolean("multiple"), toolCallId = callId), step = TaskStep(label = "Waiting for your answer", status = StepStatus.WAITING))
        }
        "finish_task" -> ToolOutcome("Finished.", finish = a.optString("outcome", "completed") to a.optString("summary"))
        else -> ToolOutcome(result(false, false, "Unknown tool $name."))
    }

    /** Messages and calls always ask first (unless the user turned that off for messages). */
    private suspend fun sensitiveSend(name: String, a: JSONObject, callId: String): ToolOutcome {
        val who = a.optString("contact_name").ifBlank { a.optString("phone") }
        val number = a.optString("phone")
        val message = a.optString("message")
        val approved = a.optBoolean("_approved") || (name != "call" && !prefs.confirmMessages)
        if (!approved) {
            val (q, yes) = when (name) {
                "send_sms" -> "Send this SMS to $who ($number)?\n“$message”" to "Send"
                "send_whatsapp" -> "Send this WhatsApp to $who?\n“$message”" to "Send"
                else -> "Call $who ($number)?" to "Call"
            }
            return ToolOutcome("Waiting for the user to approve.", pause = InputRequest("confirm", q, listOf(yes, "Cancel"),
                pendingAction = JSONObject().put("tool", name).put("args", JSONObject(a.toString()).put("_approved", true)), toolCallId = callId),
                step = TaskStep(label = "Waiting for your approval", status = StepStatus.WAITING))
        }
        val r = when (name) {
            "send_sms" -> phone.sendSms(number, message)
            "send_whatsapp" -> phone.sendWhatsApp(number, message)
            else -> phone.call(number)
        }
        val label = when (name) { "send_sms" -> "Send SMS to $who"; "send_whatsapp" -> "Send WhatsApp to $who"; else -> "Call $who" }
        return ToolOutcome(result(r.ok, r.verified, r.message + (r.needs?.let { " (needs: $it permission)" } ?: "")), step(label, r.ok, r.verified, r.message))
    }

    private fun needsAccess(what: String) = ToolOutcome(
        result(false, false, "Screen control is off, so I can't $what. Ask the user to turn on Nebula in Settings > Accessibility (Nebula > Settings > Permissions shows how)."),
        TaskStep(label = "Screen control needed", status = StepStatus.FAILED, detail = "Turn on Nebula's Accessibility service to let it $what."))

    private fun currencySymbol(): String = runCatching { Currency.getInstance(Locale.getDefault()).symbol }.getOrDefault("$").let {
        if (it.length > 3) it.take(3) else it
    }
}
