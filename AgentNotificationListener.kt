package com.example.androidmcp

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

class AgentNotificationListener : NotificationListenerService() {

    companion object {
        @Volatile
        var instance: AgentNotificationListener? = null
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
    }

    override fun onListenerDisconnected() {
        instance = null
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun list(pkg: String?, limit: Int): JSONArray {
        val arr = JSONArray()
        val all = activeNotifications ?: return arr
        for (sbn in all.sortedByDescending { it.postTime }) {
            if (pkg != null && sbn.packageName != pkg) continue
            if (arr.length() >= limit) break
            val n = sbn.notification
            val ex = n.extras
            val actions = JSONArray()
            n.actions?.forEachIndexed { i, a ->
                actions.put(
                    JSONObject()
                        .put("index", i)
                        .put("title", a.title?.toString() ?: "")
                        .put("accepts_reply", a.remoteInputs?.isNotEmpty() == true)
                )
            }
            arr.put(
                JSONObject()
                    .put("key", sbn.key)
                    .put("package", sbn.packageName)
                    .put("title", ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "")
                    .put("text", (ex.getCharSequence(Notification.EXTRA_BIG_TEXT)
                        ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: "")
                    .put("time", sbn.postTime)
                    .put("ongoing", sbn.isOngoing)
                    .put("actions", actions)
            )
        }
        return arr
    }

    private fun find(key: String): StatusBarNotification =
        activeNotifications?.firstOrNull { it.key == key }
            ?: throw IllegalArgumentException("Notification not found (it may have been dismissed). Call list again.")

    fun open(key: String) {
        val pi = find(key).notification.contentIntent ?: throw IllegalStateException("Notification has no content intent")
        pi.send()
    }

    fun dismiss(key: String) = cancelNotification(key)

    fun snooze(key: String, ms: Long) = snoozeNotification(key, ms)

    fun action(key: String, index: Int) {
        val a = find(key).notification.actions?.getOrNull(index) ?: throw IllegalArgumentException("No action at index $index")
        a.actionIntent.send()
    }

    fun reply(key: String, text: String, index: Int?) {
        val acts = find(key).notification.actions ?: throw IllegalStateException("Notification has no actions")
        val a = if (index != null) acts.getOrNull(index)
        else acts.firstOrNull { it.remoteInputs?.isNotEmpty() == true }
        if (a == null || a.remoteInputs.isNullOrEmpty()) throw IllegalStateException("No reply action on this notification")
        val intent = Intent()
        val results = Bundle()
        for (ri in a.remoteInputs) results.putCharSequence(ri.resultKey, text)
        RemoteInput.addResultsToIntent(a.remoteInputs, intent, results)
        a.actionIntent.send(App.ctx, 0, intent)
    }
}
