package com.example.androidmcp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Does the actual device control: reads the UI tree, injects gestures, types text,
 * performs global actions (back, home, power dialog, lock screen...) and takes screenshots.
 */
class AgentAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AgentAccessibilityService? = null
    }

    private val nodeMap = ConcurrentHashMap<String, AccessibilityNodeInfo>()
    private var counter = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ---------------------------------------------------------------- screen reading

    val screenWidth: Int get() = resources.displayMetrics.widthPixels
    val screenHeight: Int get() = resources.displayMetrics.heightPixels

    fun currentPackage(): String? = rootInActiveWindow?.packageName?.toString()

    @Synchronized
    fun snapshot(maxNodes: Int = 400): String {
        val root = rootInActiveWindow ?: return "No active window (the screen may be off or showing a protected screen)."
        nodeMap.clear()
        counter = 0
        val sb = StringBuilder()
        sb.append("screen:${screenWidth}x${screenHeight} package:${root.packageName}\n")
        sb.append("node_id\tclass\ttext\tdesc\tres_id\tbounds\tflags\n")
        walk(root, sb, maxNodes)
        if (counter >= maxNodes) sb.append("(truncated at $maxNodes nodes)\n")
        return sb.toString()
    }

    private fun walk(n: AccessibilityNodeInfo, sb: StringBuilder, max: Int) {
        if (counter >= max) return
        if (!n.isVisibleToUser) return
        val text = clean(n.text)
        val desc = clean(n.contentDescription)
        val res = n.viewIdResourceName ?: ""
        val interesting = text.isNotEmpty() || desc.isNotEmpty() || res.isNotEmpty() ||
            n.isClickable || n.isEditable || n.isScrollable || n.isCheckable
        if (interesting) {
            val id = "n${counter++}"
            nodeMap[id] = n
            sb.append(describe(id, n, text, desc, res)).append('\n')
        }
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            walk(c, sb, max)
        }
    }

    private fun clean(cs: CharSequence?): String =
        cs?.toString()?.replace('\t', ' ')?.replace('\n', ' ')?.take(100) ?: ""

    private fun describe(id: String, n: AccessibilityNodeInfo, text: String, desc: String, res: String): String {
        val r = Rect()
        n.getBoundsInScreen(r)
        val flags = buildList {
            if (n.isClickable) add("clk")
            if (n.isLongClickable) add("lclk")
            if (n.isFocusable) add("foc")
            if (n.isScrollable) add("scr")
            if (n.isEditable) add("edt")
            if (n.isCheckable) add(if (n.isChecked) "checked" else "check")
            if (n.isSelected) add("sel")
            if (!n.isEnabled) add("disabled")
        }.joinToString(",")
        val cls = n.className?.toString()?.substringAfterLast('.') ?: ""
        return "$id\t$cls\t$text\t$desc\t$res\t${r.left},${r.top},${r.right},${r.bottom}\t$flags"
    }

    /** Fresh snapshot, then keep only nodes matching the filters. */
    @Synchronized
    fun find(text: String?, desc: String?, resId: String?, clickableOnly: Boolean): String {
        snapshot(1500)
        val sb = StringBuilder("node_id\tclass\ttext\tdesc\tres_id\tbounds\tflags\n")
        var count = 0
        for ((id, n) in nodeMap.entries.sortedBy { it.key.substring(1).toInt() }) {
            val t = n.text?.toString() ?: ""
            val d = n.contentDescription?.toString() ?: ""
            val r = n.viewIdResourceName ?: ""
            if (text != null && !t.contains(text, ignoreCase = true)) continue
            if (desc != null && !d.contains(desc, ignoreCase = true)) continue
            if (resId != null && !r.contains(resId, ignoreCase = true)) continue
            if (clickableOnly && !n.isClickable) continue
            sb.append(describe(id, n, clean(t), clean(d), r)).append('\n')
            count++
        }
        return if (count == 0) "No matching nodes." else sb.toString()
    }

    private fun node(id: String): AccessibilityNodeInfo =
        nodeMap[id] ?: throw IllegalArgumentException("Unknown node_id '$id'. Call get_screen_state or find_nodes first; ids change whenever you do.")

    // ---------------------------------------------------------------- node actions

    fun clickNode(id: String, long: Boolean = false): Boolean {
        var n: AccessibilityNodeInfo? = node(id)
        val want = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        while (n != null) {
            if (if (long) n.isLongClickable else n.isClickable) return n.performAction(want)
            n = n.parent
        }
        // Fall back to a real gesture at the node's centre.
        val r = Rect()
        node(id).getBoundsInScreen(r)
        return if (long) longPress(r.centerX().toFloat(), r.centerY().toFloat(), 800)
        else tap(r.centerX().toFloat(), r.centerY().toFloat())
    }

    fun scrollNode(id: String, forward: Boolean): Boolean {
        var n: AccessibilityNodeInfo? = node(id)
        val a = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        while (n != null) {
            if (n.isScrollable) return n.performAction(a)
            n = n.parent
        }
        return false
    }

    fun setNodeText(id: String, text: String): Boolean {
        val n = node(id)
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun focusedInput(): AccessibilityNodeInfo? =
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

    /** mode: replace | append | clear | enter */
    fun typeIntoFocused(text: String, mode: String): Boolean {
        val n = focusedInput() ?: throw IllegalStateException("No focused text field. Tap a text field first.")
        if (mode == "enter") {
            return if (Build.VERSION.SDK_INT >= 30)
                n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            else false
        }
        val newText = when (mode) {
            "append" -> (n.text?.toString() ?: "") + text
            "clear" -> ""
            else -> text
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    // ---------------------------------------------------------------- gestures

    private fun dispatch(strokes: List<GestureDescription.StrokeDescription>): Boolean {
        val b = GestureDescription.Builder()
        strokes.forEach { b.addStroke(it) }
        val latch = CountDownLatch(1)
        var ok = false
        val cb = object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                ok = true
                latch.countDown()
            }

            override fun onCancelled(g: GestureDescription?) {
                latch.countDown()
            }
        }
        val started = dispatchGesture(b.build(), cb, Handler(Looper.getMainLooper()))
        if (!started) return false
        latch.await(15, TimeUnit.SECONDS)
        return ok
    }

    private fun stroke(path: Path, start: Long, dur: Long) =
        GestureDescription.StrokeDescription(path, start, maxOf(dur, 1L))

    fun tap(x: Float, y: Float, dur: Long = 60): Boolean =
        dispatch(listOf(stroke(Path().apply { moveTo(x, y) }, 0, dur)))

    fun longPress(x: Float, y: Float, dur: Long = 900): Boolean =
        dispatch(listOf(stroke(Path().apply { moveTo(x, y) }, 0, dur)))

    fun doubleTap(x: Float, y: Float): Boolean = dispatch(
        listOf(
            stroke(Path().apply { moveTo(x, y) }, 0, 50),
            stroke(Path().apply { moveTo(x, y) }, 150, 50)
        )
    )

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long = 300): Boolean =
        dispatch(listOf(stroke(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, 0, dur)))

    fun pinch(cx: Float, cy: Float, startDist: Float, endDist: Float, dur: Long = 400): Boolean {
        val a = Path().apply { moveTo(cx - startDist / 2, cy); lineTo(cx - endDist / 2, cy) }
        val b = Path().apply { moveTo(cx + startDist / 2, cy); lineTo(cx + endDist / 2, cy) }
        return dispatch(listOf(stroke(a, 0, dur), stroke(b, 0, dur)))
    }

    /** direction = the way the content moves into view, e.g. "down" reveals what is below. */
    fun scrollScreen(direction: String): Boolean {
        val w = screenWidth.toFloat()
        val h = screenHeight.toFloat()
        return when (direction) {
            "down" -> swipe(w / 2, h * 0.7f, w / 2, h * 0.3f, 350)
            "up" -> swipe(w / 2, h * 0.3f, w / 2, h * 0.7f, 350)
            "right" -> swipe(w * 0.75f, h / 2, w * 0.25f, h / 2, 350)
            "left" -> swipe(w * 0.25f, h / 2, w * 0.75f, h / 2, 350)
            else -> throw IllegalArgumentException("direction must be up, down, left or right")
        }
    }

    // ---------------------------------------------------------------- global actions

    fun global(name: String): Boolean {
        val action = when (name) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "power_dialog" -> GLOBAL_ACTION_POWER_DIALOG
            "lock_screen" -> GLOBAL_ACTION_LOCK_SCREEN
            "take_screenshot" -> GLOBAL_ACTION_TAKE_SCREENSHOT
            else -> throw IllegalArgumentException("Unknown action '$name'")
        }
        return performGlobalAction(action)
    }

    // ---------------------------------------------------------------- screenshot

    /** Returns a JPEG, scaled to at most maxWidth pixels wide. */
    fun screenshotJpeg(maxWidth: Int = 720, quality: Int = 60): ByteArray {
        if (Build.VERSION.SDK_INT < 30) throw IllegalStateException("Screenshots need Android 11 or newer.")
        val latch = CountDownLatch(1)
        var bmp: Bitmap? = null
        var error: String? = null
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(r: ScreenshotResult) {
                try {
                    val hw = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                    bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    r.hardwareBuffer.close()
                } catch (t: Throwable) {
                    error = t.message
                }
                latch.countDown()
            }

            override fun onFailure(errorCode: Int) {
                error = "takeScreenshot failed with code $errorCode (limited to about one per second; secure screens are blocked)"
                latch.countDown()
            }
        })
        if (!latch.await(10, TimeUnit.SECONDS)) throw IllegalStateException("Screenshot timed out")
        val b = bmp ?: throw IllegalStateException(error ?: "Screenshot failed")
        val scaled = if (b.width > maxWidth) {
            Bitmap.createScaledBitmap(b, maxWidth, (b.height * (maxWidth.toFloat() / b.width)).toInt(), true)
        } else b
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }
}
