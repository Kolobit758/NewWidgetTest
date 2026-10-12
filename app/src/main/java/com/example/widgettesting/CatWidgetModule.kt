//package com.example.widgettesting
//
//import android.content.Intent
//import com.facebook.react.bridge.Promise
//import com.facebook.react.bridge.ReactApplicationContext
//import com.facebook.react.bridge.ReactContextBaseJavaModule
//import com.facebook.react.bridge.ReactMethod
//
//class CatWidgetModule(private val ctx: ReactApplicationContext) :
//    ReactContextBaseJavaModule(ctx) {
//
//    override fun getName() = "CatWidget"
//
//    @ReactMethod
//    fun setBackground(theme: String, promise: Promise) {
//        if (!WidgetTheme.isValid(theme)) {
//            promise.reject("INVALID_THEME", "Unknown theme: $theme")
//            return
//        }
//        try {
//            val intent = Intent(ctx, CatWidgetProvider::class.java).apply {
//                action = CatWidgetProvider.ACTION_SET_BACKGROUND
//                putExtra(CatWidgetProvider.EXTRA_THEME, theme)
//            }
//            ctx.sendBroadcast(intent)
//            promise.resolve(true)
//        } catch (e: Exception) {
//            promise.reject("SEND_FAILED", e)
//        }
//    }
//
//    @ReactMethod
//    fun getBackground(promise: Promise) {
//        promise.resolve(WidgetTheme.current(ctx))
//    }
//}