package com.example.widgettesting

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build

class CatWidgetProvider : AppWidgetProvider() {

    companion object {
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
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        startCatService(context, null)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        if (intent.action == ACTION_TAP_CAT ||
            intent.action == ACTION_TAP_STATUS ||
            intent.action == ACTION_FEED ||
            intent.action == ACTION_BALL ||
            intent.action == ACTION_UNITY_COMMAND ||
            intent.action == ACTION_TEST_COMMAND ||
            intent.action == ACTION_TEST_WALKCOUNT ||
            intent.action == ACTION_TOGGLE_MUTE ||
            intent.action == ACTION_TAP_WIDGET ||
            intent.action == ACTION_RESET ||
            intent.action == ACTION_APP_FOCUS_CHANGED ||
            intent.action == ACTION_SYNC_STATS ||
            intent.action == ACTION_TEST_JUMP) {

            startCatService(context, intent)
        }
    }

    private fun startCatService(context: Context, originalIntent: Intent?) {
        val serviceIntent = Intent(context, CatWidgetService::class.java).apply {
            originalIntent?.let {
                action = it.action
                putExtras(it)
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            try {
                context.startService(serviceIntent)
            } catch (e2: Exception) {
                e2.printStackTrace()
            }
        }
    }
}