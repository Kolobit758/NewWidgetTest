package com.example.widgettesting

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews

class CatWidgetProvider : AppWidgetProvider() {

    companion object {
        fun bindThemePicker(context: Context, views: RemoteViews, widgetId: Int) {
            val intent = Intent(context, WidgetThemePickerActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context, widgetId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btnTestBg, pendingIntent)
        }
        const val ACTION_TAP_CAT = "com.example.catiwidget.ACTION_TAP_CAT"
        const val ACTION_TAP_STATUS = "com.example.widgettesting.ACTION_TAP_STATUS"
        const val ACTION_FEED = "com.example.catiwidget.ACTION_FEED"
        const val ACTION_BALL = "com.example.catiwidget.ACTION_BALL"
        const val ACTION_UNITY_COMMAND = "com.example.widgettesting.UNITY_COMMAND"
        const val ACTION_TEST_COMMAND = "com.example.widgettesting.TEST_COMMAND"
        const val ACTION_TEST_WALKCOUNT = "com.example.widgettesting.TEST_WALKCOUNT"
        const val ACTION_TOGGLE_MUTE = "com.example.catiwidget.ACTION_TOGGLE_MUTE"
        const val ACTION_RESET = "com.example.widgettesting.ACTION_RESET"
        const val ACTION_TAP_WIDGET = "com.example.widgettesting.ACTION_TAP_WIDGET"
        const val ACTION_APP_FOCUS_CHANGED = "com.example.widgettesting.APP_FOCUS_CHANGED"
        const val ACTION_SYNC_STATS = "com.example.widgettesting.SYNC_STATS"
        const val ACTION_STATS_UPDATED_TO_UNITY = "com.example.widgettesting.STATS_UPDATED_TO_UNITY"
        const val ACTION_TEST_JUMP = "com.example.widgettesting.ACTION_TEST_JUMP"
        const val ACTION_TEST_CHASE = "com.example.widgettesting.ACTION_TEST_CHASE"

        const val ACTION_TOGGLE_ITEM_DRAWER = "com.example.widgettesting.ACTION_TOGGLE_ITEM_DRAWER"
        const val ACTION_OPEN_ITEM_DRAWER = "com.example.widgettesting.ACTION_OPEN_ITEM_DRAWER"
        const val ACTION_CLOSE_ITEM_DRAWER = "com.example.widgettesting.ACTION_CLOSE_ITEM_DRAWER"
        const val ACTION_SELECT_WAND = "com.example.widgettesting.ACTION_SELECT_WAND"
        const val ACTION_SELECT_BOX = "com.example.widgettesting.ACTION_SELECT_BOX"
        const val ACTION_SELECT_FOOD = "com.example.widgettesting.ACTION_SELECT_FOOD"
        const val ACTION_SELECT_COCKROACH = "com.example.widgettesting.ACTION_SELECT_COCKROACH"
        const val ACTION_SELECT_SNAKE = "com.example.widgettesting.ACTION_SELECT_SNAKE"
        const val ACTION_TEST_CAT_PICK = "com.example.widgettesting.ACTION_TEST_CAT_PICK"

        // ธีมพื้นหลัง (จัดการที่ provider เอง ไม่ส่งต่อไป service)
        const val ACTION_SET_BACKGROUND = "com.example.widgettesting.ACTION_SET_BACKGROUND"
        const val ACTION_TEST_BACKGROUND = "com.example.widgettesting.ACTION_TEST_BACKGROUND"
        const val EXTRA_THEME = "theme"
        const val ACTION_THEME_EXPIRE = "com.example.widgettesting.ACTION_THEME_EXPIRE"
        const val EXTRA_DURATION_SEC = "duration_sec"   // ใช้ทดสอบผ่าน adb (ไม่ใส่ = 1 ชั่วโมง)

        private val HANDLED_ACTIONS = setOf(
            ACTION_TAP_CAT, ACTION_TAP_STATUS, ACTION_FEED, ACTION_BALL,
            ACTION_UNITY_COMMAND, ACTION_TEST_COMMAND, ACTION_TEST_WALKCOUNT,
            ACTION_TOGGLE_MUTE, ACTION_TAP_WIDGET, ACTION_RESET,
            ACTION_APP_FOCUS_CHANGED, ACTION_SYNC_STATS, ACTION_TEST_JUMP,
            ACTION_TEST_CHASE, ACTION_TOGGLE_ITEM_DRAWER, ACTION_OPEN_ITEM_DRAWER,
            ACTION_CLOSE_ITEM_DRAWER, ACTION_SELECT_WAND, ACTION_SELECT_BOX,
            ACTION_SELECT_FOOD, ACTION_SELECT_COCKROACH, ACTION_SELECT_SNAKE,
            ACTION_TEST_CAT_PICK
        )
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // 1) วาด widget ให้กดได้ทันที (ไม่ต้องรอ service)
        appWidgetIds.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.cat_widget)
            views.setImageViewResource(R.id.catiBackground, WidgetTheme.backgroundRes(context))
            views.setOnClickPendingIntent(R.id.catImage, pi(context, id, ACTION_TAP_CAT))
            views.setOnClickPendingIntent(R.id.catiBackground, pi(context, id, ACTION_TAP_WIDGET))
            views.setOnClickPendingIntent(R.id.btnMute, pi(context, id, ACTION_TOGGLE_MUTE))
            bindThemePicker(context, views, id)
            views.setOnClickPendingIntent(
                R.id.drawerTabContainer,
                pi(context, id, ACTION_TOGGLE_ITEM_DRAWER)
            )
            appWidgetManager.updateAppWidget(id, views)
        }

        // 2) ปลุก service ให้รัน loop
        val updateIntent = Intent().apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds)
        }
        startCatService(context, updateIntent)
    }

    // วาง widget ตัวแรกลงหน้าจอ → reset
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        startCatService(context, Intent().apply { action = ACTION_RESET })
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // รับข้อความธีมจากภายนอก (adb / RN / Unity) เช่น --es theme "Beach"
            ACTION_SET_BACKGROUND -> {
                val text = intent.getStringExtra(EXTRA_THEME)
                if (text != null) {
                    val sec = intent.getLongExtra(EXTRA_DURATION_SEC, 0L)
                    val durationMs = if (sec > 0) sec * 1000L else WidgetTheme.DEFAULT_DURATION_MS
                    WidgetTheme.setFromText(context, text, durationMs)
                }
                return
            }
            // ปุ่มทดสอบบน widget: วน Beach -> Coffee -> Minimal
            ACTION_TEST_BACKGROUND -> {
                WidgetTheme.testNext(context)
                return
            }
            // alarm ครบ 1 ชั่วโมง -> กลับเป็นพื้นหลังปกติ
            ACTION_THEME_EXPIRE -> {
                WidgetTheme.onExpire(context)
                return
            }
        }
        super.onReceive(context, intent)
        if (intent.action in HANDLED_ACTIONS) {
            startCatService(context, intent)
        }
    }

    private fun pi(context: Context, id: Int, action: String): PendingIntent {
        val intent = Intent(context, CatWidgetProvider::class.java).apply {
            this.action = action
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        }
        return PendingIntent.getBroadcast(
            context,
            (id.toString() + "_" + action).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun startCatService(context: Context, originalIntent: Intent?) {
        val serviceIntent = Intent(context, CatWidgetService::class.java).apply {
            originalIntent?.let {
                action = it.action
                it.extras?.let { extras -> putExtras(extras) }
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            android.util.Log.e("CatWidgetDebug", "start service failed", e)
        }
    }
}
