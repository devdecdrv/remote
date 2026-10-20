package com.example.androidmcp

import android.app.Application
import android.content.Context
import java.security.SecureRandom

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ctx = applicationContext
    }

    companion object {
        lateinit var ctx: Context
            private set

        const val PORT = 8765
        private const val PREFS = "mcp"
        private const val KEY_TOKEN = "token"

        fun token(): String {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            var t = p.getString(KEY_TOKEN, null)
            if (t == null) t = regenerateToken()
            return t
        }

        fun regenerateToken(): String {
            val bytes = ByteArray(24)
            SecureRandom().nextBytes(bytes)
            val t = bytes.joinToString("") { "%02x".format(it) }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TOKEN, t).apply()
            return t
        }
    }
}
