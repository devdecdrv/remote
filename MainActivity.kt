package com.example.androidmcp

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var info: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        val title = TextView(this).apply {
            text = "Android MCP"
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
        }
        status = TextView(this).apply { textSize = 15f; setPadding(0, 24, 0, 24) }
        info = TextView(this).apply { textSize = 13f; setTextIsSelectable(true); setPadding(0, 0, 0, 24) }
        col.addView(title)
        col.addView(status)
        col.addView(info)

        fun button(label: String, onClick: () -> Unit) {
            col.addView(Button(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener { onClick() }
            })
        }

        button("1. Accessibility settings (enable Android MCP Control)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button("2. Notification access") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        button("3. Grant permissions (calls, SMS, location, notifications)") {
            val perms = mutableListOf(
                Manifest.permission.CALL_PHONE, Manifest.permission.ANSWER_PHONE_CALLS,
                Manifest.permission.READ_PHONE_STATE, Manifest.permission.SEND_SMS,
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
            requestPermissions(perms.toTypedArray(), 1)
        }
        button("4. All files access (optional, for shared storage)") {
            if (Build.VERSION.SDK_INT >= 30) {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                )
            }
        }
        button("5. Start server") {
            startForegroundService(Intent(this, McpForegroundService::class.java))
            status.postDelayed({ refresh() }, 500)
        }
        button("Stop server") {
            stopService(Intent(this, McpForegroundService::class.java))
            status.postDelayed({ refresh() }, 500)
        }
        button("Copy token") {
            copy("token", App.token())
        }
        button("Copy MCP URL") {
            copy("url", "http://${ip()}:${App.PORT}/mcp/${App.token()}")
        }
        button("Generate new token") {
            App.regenerateToken()
            stopService(Intent(this, McpForegroundService::class.java))
            Toast.makeText(this, "New token created. Start the server again.", Toast.LENGTH_LONG).show()
            status.postDelayed({ refresh() }, 500)
        }

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun copy(label: String, text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun refresh() {
        val a11y = AgentAccessibilityService.instance != null
        val notif = AgentNotificationListener.instance != null
        status.text = buildString {
            append("Server: ").append(if (McpForegroundService.running) "running" else "stopped").append('\n')
            append("Accessibility: ").append(if (a11y) "enabled" else "NOT enabled").append('\n')
            append("Notification access: ").append(if (notif) "enabled" else "NOT enabled")
        }
        info.text = "Local URL: http://${ip()}:${App.PORT}/mcp/${App.token()}\n\n" +
            "For Claude, run a tunnel and use: https://<your-tunnel-host>/mcp/${App.token()}\n\n" +
            "Token: ${App.token()}"
    }

    private fun ip(): String {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress ?: "<phone-ip>"
        } catch (t: Throwable) {
            "<phone-ip>"
        }
    }
}
