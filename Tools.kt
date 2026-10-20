@file:Suppress("DEPRECATION")

package com.example.androidmcp

import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class Param(
    val name: String,
    val type: String,
    val desc: String,
    val required: Boolean = false,
    val enum: List<String>? = null
)

class Tool(
    val name: String,
    val description: String,
    val params: List<Param>,
    val run: (JSONObject) -> JSONObject
) {
    fun toJson(): JSONObject {
        val props = JSONObject()
        val req = JSONArray()
        for (p in params) {
            val o = JSONObject().put("type", p.type).put("description", p.desc)
            if (p.enum != null) o.put("enum", JSONArray(p.enum))
            props.put(p.name, o)
            if (p.required) req.put(p.name)
        }
        return JSONObject()
            .put("name", name)
            .put("description", description)
            .put("inputSchema", JSONObject().put("type", "object").put("properties", props).put("required", req))
    }
}

object Tools {

    // ------------------------------------------------------------------ result helpers

    fun textResult(s: String): JSONObject =
        JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", s)))

    fun errorResult(s: String): JSONObject = textResult(s).put("isError", true)

    private fun imageResult(bytes: ByteArray, mime: String): JSONObject =
        JSONObject().put(
            "content", JSONArray().put(
                JSONObject().put("type", "image")
                    .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    .put("mimeType", mime)
            )
        )

    private fun wrap(r: Any?): JSONObject = when (r) {
        null, Unit -> textResult("done")
        is JSONObject -> if (r.has("content")) r else textResult(r.toString(2))
        is JSONArray -> textResult(r.toString(2))
        else -> textResult(r.toString())
    }

    // ------------------------------------------------------------------ param + access helpers

    private fun s(name: String, desc: String, req: Boolean = false, enum: List<String>? = null) =
        Param(name, "string", desc, req, enum)

    private fun i(name: String, desc: String, req: Boolean = false) = Param(name, "integer", desc, req)
    private fun n(name: String, desc: String, req: Boolean = false) = Param(name, "number", desc, req)
    private fun b(name: String, desc: String, req: Boolean = false) = Param(name, "boolean", desc, req)

    private fun tool(name: String, desc: String, vararg params: Param, run: (JSONObject) -> Any?) =
        Tool(name, desc, params.toList()) { args -> wrap(run(args)) }

    private val ctx: Context get() = App.ctx

    /** Activities are launched from the accessibility service when possible: it is exempt from background-launch limits. */
    private val launchCtx: Context get() = AgentAccessibilityService.instance ?: ctx

    private fun a11y(): AgentAccessibilityService = AgentAccessibilityService.instance
        ?: throw IllegalStateException("Accessibility service is not enabled. Open the Android MCP app and tap 'Accessibility settings', then enable Android MCP Control.")

    private fun notif(): AgentNotificationListener = AgentNotificationListener.instance
        ?: throw IllegalStateException("Notification access is not enabled. Open the Android MCP app and tap 'Notification access'.")

    private fun need(perm: String) {
        if (ctx.checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED)
            throw IllegalStateException("Permission $perm is not granted. Open the Android MCP app and tap 'Grant permissions'.")
    }

    private fun done(ok: Boolean, what: String) = if (ok) "$what: done" else "$what: failed or was cancelled"

    private fun JSONObject.f(k: String): Float = getDouble(k).toFloat()
    private fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) getString(k) else null

    private fun startActivity(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        launchCtx.startActivity(intent)
    }

    // ------------------------------------------------------------------ file helpers

    private fun root(): File =
        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) Environment.getExternalStorageDirectory()
        else (ctx.getExternalFilesDir(null) ?: ctx.filesDir)

    private fun resolve(path: String): File {
        val r = root().canonicalFile
        val f = File(r, path.trimStart('/')).canonicalFile
        if (f.path != r.path && !f.path.startsWith(r.path + File.separator))
            throw IllegalArgumentException("Path escapes the allowed root (${r.path})")
        return f
    }

    // ------------------------------------------------------------------ registry

    val all: List<Tool> by lazy { build() }

    fun find(name: String): Tool? = all.firstOrNull { it.name == name }

    private fun build(): List<Tool> = listOf(

        // ============================== screen reading
        tool("get_screen_state", "Returns the foreground app, screen size and a list of visible UI nodes (id, class, text, description, resource id, bounds, flags). Use node ids with tap_node, set_node_text, etc.",
            i("max_nodes", "Maximum nodes to return (default 400)")) { a ->
            a11y().snapshot(a.optInt("max_nodes", 400))
        },

        tool("take_screenshot", "Takes a screenshot of the current screen and returns it as an image. Blocked on secure screens (banking apps, password fields). Android 11+.",
            i("max_width", "Scale down to this width in pixels (default 720)")) { a ->
            imageResult(a11y().screenshotJpeg(a.optInt("max_width", 720)), "image/jpeg")
        },

        tool("find_nodes", "Takes a fresh snapshot and returns only nodes matching all given filters (case-insensitive substring).",
            s("text", "Text contains"), s("desc", "Content description contains"), s("res_id", "Resource id contains"),
            b("clickable_only", "Only clickable nodes")) { a ->
            a11y().find(a.str("text"), a.str("desc"), a.str("res_id"), a.optBoolean("clickable_only", false))
        },

        tool("wait", "Waits for the given number of milliseconds (max 30000).",
            i("ms", "Milliseconds", true)) { a ->
            Thread.sleep(a.getLong("ms").coerceIn(0, 30000)); "waited"
        },

        tool("wait_for_text", "Polls the screen until a node containing the text appears, or the timeout passes.",
            s("text", "Text to wait for", true), i("timeout_ms", "Timeout in ms (default 10000, max 30000)")) { a ->
            val text = a.getString("text")
            val end = System.currentTimeMillis() + a.optLong("timeout_ms", 10000).coerceIn(500, 30000)
            var found = false
            while (System.currentTimeMillis() < end) {
                if (a11y().find(text, null, null, false) != "No matching nodes.") {
                    found = true; break
                }
                Thread.sleep(500)
            }
            if (found) "found" else "timeout: '$text' did not appear"
        },

        // ============================== node actions
        tool("tap_node", "Clicks a node by id (falls back to a tap at its centre).",
            s("node_id", "Id from get_screen_state / find_nodes", true)) { a ->
            done(a11y().clickNode(a.getString("node_id")), "tap_node")
        },

        tool("long_click_node", "Long-clicks a node by id.",
            s("node_id", "Node id", true)) { a ->
            done(a11y().clickNode(a.getString("node_id"), long = true), "long_click_node")
        },

        tool("scroll_node", "Scrolls the scrollable container that holds this node.",
            s("node_id", "Node id", true), s("direction", "forward or backward", true, listOf("forward", "backward"))) { a ->
            done(a11y().scrollNode(a.getString("node_id"), a.getString("direction") == "forward"), "scroll_node")
        },

        tool("set_node_text", "Sets the text of an editable node (replaces existing text).",
            s("node_id", "Node id of a text field", true), s("text", "New text", true)) { a ->
            done(a11y().setNodeText(a.getString("node_id"), a.getString("text")), "set_node_text")
        },

        tool("type_text", "Types into the currently focused text field. mode: replace (default), append, clear, or enter (presses the IME enter/search key, Android 11+).",
            s("text", "Text to type (ignored for clear/enter)"), s("mode", "replace | append | clear | enter", false, listOf("replace", "append", "clear", "enter"))) { a ->
            done(a11y().typeIntoFocused(a.optString("text", ""), a.optString("mode", "replace")), "type_text")
        },

        // ============================== gestures
        tool("tap", "Taps at screen coordinates (pixels).", n("x", "X", true), n("y", "Y", true)) { a ->
            done(a11y().tap(a.f("x"), a.f("y")), "tap")
        },

        tool("double_tap", "Double-taps at screen coordinates.", n("x", "X", true), n("y", "Y", true)) { a ->
            done(a11y().doubleTap(a.f("x"), a.f("y")), "double_tap")
        },

        tool("long_press", "Presses and holds at screen coordinates.", n("x", "X", true), n("y", "Y", true),
            i("duration_ms", "Hold time (default 900)")) { a ->
            done(a11y().longPress(a.f("x"), a.f("y"), a.optLong("duration_ms", 900)), "long_press")
        },

        tool("swipe", "Swipes from one point to another.", n("x1", "Start X", true), n("y1", "Start Y", true),
            n("x2", "End X", true), n("y2", "End Y", true), i("duration_ms", "Duration (default 300)")) { a ->
            done(a11y().swipe(a.f("x1"), a.f("y1"), a.f("x2"), a.f("y2"), a.optLong("duration_ms", 300)), "swipe")
        },

        tool("pinch", "Two-finger pinch/zoom around a centre point. Zoom in: end_distance larger than start_distance.",
            n("x", "Centre X", true), n("y", "Centre Y", true),
            n("start_distance", "Start distance between fingers (px)", true), n("end_distance", "End distance (px)", true),
            i("duration_ms", "Duration (default 400)")) { a ->
            done(a11y().pinch(a.f("x"), a.f("y"), a.f("start_distance"), a.f("end_distance"), a.optLong("duration_ms", 400)), "pinch")
        },

        tool("scroll", "Scrolls the whole screen. direction is where you want to go: down reveals content further down the page.",
            s("direction", "up, down, left or right", true, listOf("up", "down", "left", "right"))) { a ->
            done(a11y().scrollScreen(a.getString("direction")), "scroll")
        },

        // ============================== system buttons + power
        tool("press_button", "System actions: back, home, recents, notifications (pull down shade), quick_settings, power_dialog (opens the power menu, tap Power off / Restart via get_screen_state), lock_screen, take_screenshot (saves to the gallery).",
            s("button", "Which action", true,
                listOf("back", "home", "recents", "notifications", "quick_settings", "power_dialog", "lock_screen", "take_screenshot"))) { a ->
            done(a11y().global(a.getString("button")), "press_button ${a.getString("button")}")
        },

        tool("wake_screen", "Turns the screen on (like tapping the power button while it is off). It does not unlock a secured screen.") {
            need(android.Manifest.permission.WAKE_LOCK)
            val pm = ctx.getSystemService(PowerManager::class.java)
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "androidmcp:wake"
            )
            wl.acquire(5000)
            "screen woken"
        },

        tool("set_volume", "Sets a volume stream to a percentage (0-100).",
            s("stream", "Which stream", true, listOf("music", "ring", "alarm", "call", "notification")),
            i("percent", "0-100", true)) { a ->
            val am = ctx.getSystemService(AudioManager::class.java)
            val st = when (a.getString("stream")) {
                "music" -> AudioManager.STREAM_MUSIC
                "ring" -> AudioManager.STREAM_RING
                "alarm" -> AudioManager.STREAM_ALARM
                "call" -> AudioManager.STREAM_VOICE_CALL
                "notification" -> AudioManager.STREAM_NOTIFICATION
                else -> throw IllegalArgumentException("Unknown stream")
            }
            val level = (am.getStreamMaxVolume(st) * a.getInt("percent").coerceIn(0, 100) / 100.0).toInt()
            am.setStreamVolume(st, level, 0)
            "volume set to $level/${am.getStreamMaxVolume(st)}"
        },

        // ============================== apps + intents
        tool("list_apps", "Lists installed apps (name and package).",
            s("query", "Name or package contains"), b("user_only", "Only user-installed apps (default true)")) { a ->
            val pm = ctx.packageManager
            val q = a.str("query")
            val userOnly = a.optBoolean("user_only", true)
            pm.getInstalledApplications(0)
                .filter { !userOnly || (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
                .map { it.loadLabel(pm).toString() to it.packageName }
                .filter { q == null || it.first.contains(q, true) || it.second.contains(q, true) }
                .sortedBy { it.first.lowercase() }
                .joinToString("\n") { "${it.first}\t${it.second}" }
                .ifEmpty { "No apps found" }
        },

        tool("open_app", "Launches an app by package name.", s("package", "Package name, e.g. com.whatsapp", true)) { a ->
            val intent = ctx.packageManager.getLaunchIntentForPackage(a.getString("package"))
                ?: throw IllegalArgumentException("App not found or has no launcher activity")
            startActivity(intent)
            "launched ${a.getString("package")}"
        },

        tool("open_uri", "Opens a URI with ACTION_VIEW (web page, tel:, geo:, mailto:, market:, etc.).", s("uri", "URI", true)) { a ->
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.getString("uri"))))
            "opened"
        },

        tool("send_intent", "Starts an activity with an arbitrary intent.",
            s("action", "Intent action", true), s("uri", "Data URI"), s("package", "Target package"),
            s("mime_type", "MIME type"), Param("extras", "object", "String extras, key to value")) { a ->
            val intent = Intent(a.getString("action"))
            val uri = a.str("uri")
            val mime = a.str("mime_type")
            if (uri != null && mime != null) intent.setDataAndType(Uri.parse(uri), mime)
            else if (uri != null) intent.data = Uri.parse(uri)
            else if (mime != null) intent.type = mime
            a.str("package")?.let { intent.setPackage(it) }
            a.optJSONObject("extras")?.let { ex -> ex.keys().forEach { k -> intent.putExtra(k, ex.getString(k)) } }
            startActivity(intent)
            "started"
        },

        // ============================== phone calls + sms
        tool("make_call", "Places a phone call to a number. The call is real and may cost money.",
            s("number", "Phone number", true)) { a ->
            need(android.Manifest.permission.CALL_PHONE)
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(a.getString("number")))))
            "dialing ${a.getString("number")}"
        },

        tool("answer_call", "Answers a ringing incoming call.") {
            need(android.Manifest.permission.ANSWER_PHONE_CALLS)
            ctx.getSystemService(TelecomManager::class.java).acceptRingingCall()
            "answered"
        },

        tool("end_call", "Hangs up the current call (or rejects a ringing one).") {
            need(android.Manifest.permission.ANSWER_PHONE_CALLS)
            done(ctx.getSystemService(TelecomManager::class.java).endCall(), "end_call")
        },

        tool("call_state", "Returns IDLE, RINGING or OFFHOOK (in a call).") {
            need(android.Manifest.permission.READ_PHONE_STATE)
            when (ctx.getSystemService(TelephonyManager::class.java).callState) {
                TelephonyManager.CALL_STATE_IDLE -> "IDLE"
                TelephonyManager.CALL_STATE_RINGING -> "RINGING"
                TelephonyManager.CALL_STATE_OFFHOOK -> "OFFHOOK"
                else -> "UNKNOWN"
            }
        },

        tool("set_speaker", "Turns the speakerphone on or off (best effort; if the audio route does not change, tap the speaker button in the dialer using get_screen_state).",
            b("on", "true for speaker", true)) { a ->
            val am = ctx.getSystemService(AudioManager::class.java)
            am.isSpeakerphoneOn = a.getBoolean("on")
            "speakerphone ${if (a.getBoolean("on")) "on" else "off"}"
        },

        tool("set_mute", "Mutes or unmutes the microphone.", b("on", "true to mute", true)) { a ->
            ctx.getSystemService(AudioManager::class.java).isMicrophoneMute = a.getBoolean("on")
            "microphone ${if (a.getBoolean("on")) "muted" else "unmuted"}"
        },

        tool("send_sms", "Sends a text message. This is real and may cost money.",
            s("number", "Phone number", true), s("text", "Message", true)) { a ->
            need(android.Manifest.permission.SEND_SMS)
            val sms: SmsManager = if (Build.VERSION.SDK_INT >= 31) {
                ctx.getSystemService(SmsManager::class.java)
            } else {
                SmsManager.getDefault()
            }
            sms.sendMultipartTextMessage(a.getString("number"), null, sms.divideMessage(a.getString("text")), null, null)
            "sms sent"
        },

        // ============================== notifications
        tool("list_notifications", "Lists active notifications (key, app, title, text, actions).",
            s("package", "Only this app"), i("limit", "Max results (default 30)")) { a ->
            notif().list(a.str("package"), a.optInt("limit", 30))
        },

        tool("notification_open", "Opens a notification (runs its tap action).", s("key", "Notification key", true)) { a ->
            notif().open(a.getString("key")); "opened"
        },

        tool("notification_dismiss", "Dismisses a notification.", s("key", "Notification key", true)) { a ->
            notif().dismiss(a.getString("key")); "dismissed"
        },

        tool("notification_snooze", "Snoozes a notification.", s("key", "Notification key", true),
            i("minutes", "Minutes to snooze", true)) { a ->
            notif().snooze(a.getString("key"), a.getLong("minutes") * 60_000); "snoozed"
        },

        tool("notification_action", "Presses an action button on a notification.", s("key", "Notification key", true),
            i("index", "Action index from list_notifications", true)) { a ->
            notif().action(a.getString("key"), a.getInt("index")); "action sent"
        },

        tool("notification_reply", "Sends an inline reply to a notification (messaging apps).", s("key", "Notification key", true),
            s("text", "Reply text", true), i("index", "Action index (default: first reply action)")) { a ->
            notif().reply(a.getString("key"), a.getString("text"), if (a.has("index")) a.getInt("index") else null)
            "reply sent"
        },

        // ============================== files
        tool("list_files", "Lists files in a folder. Without the 'All files access' permission the root is the app's own folder; with it, the root is shared storage.",
            s("path", "Folder relative to the root (default: root)")) { a ->
            val dir = resolve(a.optString("path", ""))
            if (!dir.isDirectory) throw IllegalArgumentException("Not a folder")
            val rows = (dir.listFiles() ?: emptyArray()).sortedBy { it.name.lowercase() }
                .joinToString("\n") { (if (it.isDirectory) "d " else "f ") + it.name + "\t" + (if (it.isFile) it.length() else 0) }
            "root=${root().path}\nfolder=${dir.path}\n" + rows.ifEmpty { "(empty)" }
        },

        tool("read_file", "Reads a text file.", s("path", "File path", true), i("max_bytes", "Max bytes (default 100000)")) { a ->
            val f = resolve(a.getString("path"))
            if (!f.isFile) throw IllegalArgumentException("Not a file")
            val max = a.optInt("max_bytes", 100_000)
            val bytes = f.inputStream().use { ins ->
                val buf = ByteArray(max)
                var off = 0
                while (off < max) {
                    val r = ins.read(buf, off, max - off)
                    if (r < 0) break
                    off += r
                }
                buf.copyOf(off)
            }
            String(bytes, Charsets.UTF_8) + if (f.length() > max) "\n(truncated at $max of ${f.length()} bytes)" else ""
        },

        tool("write_file", "Writes (or appends to) a text file, creating folders as needed.",
            s("path", "File path", true), s("content", "Text content", true), b("append", "Append instead of overwrite")) { a ->
            val f = resolve(a.getString("path"))
            f.parentFile?.mkdirs()
            if (a.optBoolean("append", false)) f.appendText(a.getString("content")) else f.writeText(a.getString("content"))
            "wrote ${f.path} (${f.length()} bytes)"
        },

        tool("delete_file", "Deletes a file or an empty folder.", s("path", "Path", true)) { a ->
            done(resolve(a.getString("path")).delete(), "delete_file")
        },

        tool("download_file", "Downloads a URL to a file (max 200 MB).", s("url", "http(s) URL", true), s("path", "Destination path", true)) { a ->
            val f = resolve(a.getString("path"))
            f.parentFile?.mkdirs()
            val conn = URL(a.getString("url")).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            var total = 0L
            conn.inputStream.use { input ->
                f.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val r = input.read(buf)
                        if (r < 0) break
                        total += r
                        if (total > 200L * 1024 * 1024) throw IllegalStateException("File exceeds 200 MB")
                        out.write(buf, 0, r)
                    }
                }
            }
            "saved ${f.path} ($total bytes)"
        },

        // ============================== misc
        tool("get_clipboard", "Reads the clipboard. Android 10+ only allows this while an app of ours is in the foreground, so it may return empty.") {
            val cm = ctx.getSystemService(ClipboardManager::class.java)
            cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: "(clipboard empty or not readable from the background)"
        },

        tool("set_clipboard", "Sets the clipboard text.", s("text", "Text", true)) { a ->
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("mcp", a.getString("text")))
            "clipboard set"
        },

        tool("get_location", "Returns the last known location.") {
            if (ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
            ) throw IllegalStateException("Location permission not granted. Open the app and tap 'Grant permissions'.")
            val lm = ctx.getSystemService(LocationManager::class.java)
            val best = lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
                ?: throw IllegalStateException("No location available yet. Open Maps once to get a fix.")
            JSONObject().put("latitude", best.latitude).put("longitude", best.longitude)
                .put("accuracy_m", best.accuracy).put("age_seconds", (System.currentTimeMillis() - best.time) / 1000)
        },

        tool("device_info", "Battery, screen and lock state, Android version, and which permissions/services are enabled.") {
            val bat = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = bat?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val plugged = (bat?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            val pm = ctx.getSystemService(PowerManager::class.java)
            val kg = ctx.getSystemService(KeyguardManager::class.java)
            JSONObject()
                .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)
                .put("battery_percent", if (level >= 0) level * 100 / scale else JSONObject.NULL)
                .put("charging", plugged)
                .put("screen_on", pm.isInteractive)
                .put("locked", kg.isKeyguardLocked)
                .put("accessibility_enabled", AgentAccessibilityService.instance != null)
                .put("notification_access_enabled", AgentNotificationListener.instance != null)
                .put("all_files_access", Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager())
                .put("file_root", root().path)
                .put("airplane_mode", Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1)
        }
    )
}
