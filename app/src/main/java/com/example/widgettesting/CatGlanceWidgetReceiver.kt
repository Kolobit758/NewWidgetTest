//package com.example.widgettesting
//
//import android.content.Context
//import android.content.Intent
//import androidx.glance.appwidget.GlanceAppWidget
//import androidx.glance.appwidget.GlanceAppWidgetReceiver
//
//class CatGlanceWidgetReceiver : GlanceAppWidgetReceiver() {
//    override val glanceAppWidget: GlanceAppWidget = CatGlanceWidget()
//
//    override fun onReceive(context: Context, intent: Intent) {
//        super.onReceive(context, intent)
//
//        if (intent.action == CatWidgetProvider.ACTION_TAP_CAT ||
//            intent.action == CatWidgetProvider.ACTION_TAP_STATUS ||
//            intent.action == CatWidgetProvider.ACTION_FEED ||
//            intent.action == CatWidgetProvider.ACTION_BALL ||
//            intent.action == CatWidgetProvider.ACTION_UNITY_COMMAND ||
//            intent.action == CatWidgetProvider.ACTION_TEST_COMMAND ||
//            intent.action == CatWidgetProvider.ACTION_TEST_WALKCOUNT ||
//            intent.action == CatWidgetProvider.ACTION_TOGGLE_MUTE ||
//            intent.action == CatWidgetProvider.ACTION_TAP_WIDGET ||
//            intent.action == CatWidgetProvider.ACTION_RESET ||
//            intent.action == CatWidgetProvider.ACTION_APP_FOCUS_CHANGED ||
//            intent.action == CatWidgetProvider.ACTION_SYNC_STATS) {
//
//            val serviceIntent = Intent(context, CatWidgetService::class.java).apply {
//                action = intent.action
//                putExtras(intent)
//            }
//            try {
//                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
//                    context.startForegroundService(serviceIntent)
//                } else {
//                    context.startService(serviceIntent)
//                }
//            } catch (e: Exception) {
//                e.printStackTrace()
//            }
//        }
//    }
//}
