package com.example.widgettesting

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews

object WidgetTheme {
    private const val TAG = "CatWidgetDebug"
    private const val PREFS = "cat_widget_prefs"   // ไฟล์เดียวกับที่ service ใช้
    private const val KEY_THEME = "bg_theme"
    private const val KEY_EXPIRES_AT = "bg_theme_expires_at"
    private const val KEY_TEST_INDEX = "bg_test_index"
    private const val DEFAULT_THEME = "minimal"

    /** ระยะเวลาที่ธีมพิเศษอยู่ก่อนกลับเป็นปกติ (1 ชั่วโมง) */
//    const val DEFAULT_DURATION_MS = 30 * 60 * 1000L
    const val DEFAULT_DURATION_MS = 5 * 1000L

    private val themeMap = mapOf(
        "minimal" to R.drawable.minimal_bg,
        "beach" to R.drawable.bg_beach,
        "coffee" to R.drawable.bg_coffee
    )

    private val keywords = mapOf(
        "beach" to listOf("beach", "sea", "ocean", "ทะเล", "ชายหาด", "หาด"),
        "coffee" to listOf("coffee", "cafe", "café", "กาแฟ", "คาเฟ่"),
        "minimal" to listOf("minimal", "default", "plain", "เรียบ", "ปกติ")
    )

    private val testTexts = listOf("Beach", "Coffee", "Minimal")

    fun resolve(text: String): String? {
        val t = text.trim().lowercase()
        if (t.isEmpty()) return null
        if (themeMap.containsKey(t)) return t
        return keywords.entries.firstOrNull { (_, words) -> words.any { t.contains(it) } }?.key
    }

    /**
     * รับข้อความ -> เปลี่ยนพื้นหลัง และเริ่มนับเวลา (ค่าเริ่มต้น 1 ชั่วโมง)
     * ถ้าเป็นธีมปกติ (minimal) จะไม่มีตัวนับเวลา
     * คืน true ถ้าสำเร็จ, false ถ้าข้อความไม่ตรงธีมไหน
     */
    fun setFromText(
        context: Context,
        text: String,
        durationMs: Long = DEFAULT_DURATION_MS
    ): Boolean {
        val theme = resolve(text)
        if (theme == null) {
            Log.w(TAG, "Unknown theme text: \"$text\" (valid: ${themeMap.keys})")
            return false
        }

        val editor = prefs(context).edit().putString(KEY_THEME, theme)
        if (theme == DEFAULT_THEME) {
            editor.putLong(KEY_EXPIRES_AT, 0L).apply()
            cancelExpiryAlarm(context)
            Log.d(TAG, "Background theme set: $theme (no timer)")
        } else {
            val expiresAt = System.currentTimeMillis() + durationMs
            editor.putLong(KEY_EXPIRES_AT, expiresAt).apply()
            scheduleExpiryAlarm(context, expiresAt)
            Log.d(TAG, "Background theme set: $theme, revert in ${durationMs / 1000}s")
        }
        applyToWidgets(context)
        return true
    }

    /** ธีมปัจจุบัน (ถ้าหมดเวลาแล้วจะรีเซ็ตเป็นปกติให้เอง) */
    fun current(context: Context): String {
        val p = prefs(context)
        val theme = p.getString(KEY_THEME, DEFAULT_THEME) ?: DEFAULT_THEME
        if (theme == DEFAULT_THEME) return DEFAULT_THEME

        val expiresAt = p.getLong(KEY_EXPIRES_AT, 0L)
        if (expiresAt > 0L && System.currentTimeMillis() >= expiresAt) {
            p.edit()
                .putString(KEY_THEME, DEFAULT_THEME)
                .putLong(KEY_EXPIRES_AT, 0L)
                .apply()
            Log.d(TAG, "Background theme \"$theme\" expired -> $DEFAULT_THEME")
            return DEFAULT_THEME
        }
        return theme
    }

    fun backgroundRes(context: Context): Int =
        themeMap[current(context)] ?: R.drawable.minimal_bg

    /** เวลาที่เหลือก่อนกลับเป็นปกติ (ms) คืน 0 ถ้าไม่มีตัวนับ */
    fun remainingMs(context: Context): Long {
        if (current(context) == DEFAULT_THEME) return 0L
        val expiresAt = prefs(context).getLong(KEY_EXPIRES_AT, 0L)
        return (expiresAt - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    fun themes(): List<String> = themeMap.keys.toList()

    /** ปุ่มทดสอบ: วน Beach -> Coffee -> Minimal */
    fun testNext(context: Context): String {
        val p = prefs(context)
        val idx = (p.getInt(KEY_TEST_INDEX, -1) + 1) % testTexts.size
        p.edit().putInt(KEY_TEST_INDEX, idx).apply()
        val text = testTexts[idx]
        setFromText(context, text)
        return text
    }

    /** เรียกจาก provider เมื่อ alarm ครบเวลา */
    fun onExpire(context: Context) {
        current(context)          // เช็กและรีเซ็ตถ้าหมดอายุ
        applyToWidgets(context)   // วาดพื้นหลังใหม่ทันที
    }

    // ---------- Alarm ----------

    private fun expiryPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, CatWidgetProvider::class.java).apply {
            action = CatWidgetProvider.ACTION_THEME_EXPIRE
        }
        return PendingIntent.getBroadcast(
            context,
            9001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun scheduleExpiryAlarm(context: Context, triggerAtMs: Long) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            // ไม่ต้องขอ permission exact alarm (อาจเลื่อนช้าเล็กน้อยได้ แต่ service เช็กทุก 200ms อยู่แล้ว)
            am.setAndAllowWhileIdle(
                AlarmManager.RTC,
                triggerAtMs,
                expiryPendingIntent(context)
            )
        } catch (e: Exception) {
            Log.e(TAG, "scheduleExpiryAlarm failed", e)
        }
    }

    private fun cancelExpiryAlarm(context: Context) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(expiryPendingIntent(context))
        } catch (e: Exception) {
            Log.e(TAG, "cancelExpiryAlarm failed", e)
        }
    }

    // ---------- Widget ----------

    private fun applyToWidgets(context: Context) {
        try {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, CatWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val views = RemoteViews(context.packageName, R.layout.cat_widget)
            views.setImageViewResource(R.id.catiBackground, backgroundRes(context))
            ids.forEach { mgr.partiallyUpdateAppWidget(it, views) }
        } catch (e: Exception) {
            Log.e(TAG, "applyToWidgets failed", e)
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}