//package com.example.widgettesting
//
//import android.content.Context
//import android.content.Intent
//import androidx.compose.runtime.Composable
//import androidx.compose.ui.unit.dp
//import androidx.compose.ui.unit.sp
//import androidx.glance.GlanceId
//import androidx.glance.GlanceModifier
//import androidx.glance.Image
//import androidx.glance.ImageProvider
//import androidx.glance.action.clickable
//import androidx.glance.appwidget.GlanceAppWidget
//import androidx.glance.appwidget.action.actionSendBroadcast
//import androidx.glance.appwidget.provideContent
//import androidx.glance.background
//import androidx.glance.layout.*
//import androidx.glance.text.FontWeight
//import androidx.glance.text.Text
//import androidx.glance.text.TextStyle
//import androidx.glance.unit.ColorProvider
//
//class CatGlanceWidget : GlanceAppWidget() {
//
//    override suspend fun provideGlance(context: Context, id: GlanceId) {
//        provideContent {
//            GlanceWidgetContent(context)
//        }
//    }
//
//    @Composable
//    private fun GlanceWidgetContent(context: Context) {
//        val prefs = context.getSharedPreferences("cat_widget_prefs", Context.MODE_PRIVATE)
//        val hunger = prefs.getInt("hunger", 0)
//        val energy = prefs.getInt("energy", 100)
//        val boredom = prefs.getInt("boredom", 0)
//        val catStateName = prefs.getString("cat_state", "IDLE") ?: "IDLE"
//        val statusMessage = prefs.getString("status_message", "") ?: ""
//        val statusIconRes = prefs.getInt("status_icon_res", 0)
//        val isMuted = prefs.getBoolean("is_muted", false)
//
//        val catImageRes = when (catStateName) {
//            "EATING" -> R.drawable.cat_eat
//            "PLAYING", "WAND_PLAY", "BOX_EMERGE" -> R.drawable.cat_play
//            "STRETCH" -> R.drawable.cat_stresh
//            "ANGRY" -> R.drawable.cat_angry
//            "NAP" -> R.drawable.cat_nap
//            "SNUGGLE", "GROOMING" -> R.drawable.cat_fun
//            "HUNGRY" -> R.drawable.cat_hungry
//            else -> R.drawable.cat_idle
//        }
//
//        val muteIconRes = if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up
//
//        Box(
//            modifier = GlanceModifier
//                .fillMaxSize()
//                .background(ImageProvider(R.drawable.realbg))
//                .clickable(actionSendBroadcast(Intent(context, CatWidgetProvider::class.java).apply {
//                    action = CatWidgetProvider.ACTION_TAP_WIDGET
//                }))
//        ) {
//            // Main Center/Bottom Content Area
//            Column(
//                modifier = GlanceModifier
//                    .fillMaxSize()
//                    .padding(8.dp),
//                verticalAlignment = Alignment.Bottom,
//                horizontalAlignment = Alignment.CenterHorizontally
//            ) {
//                // Speech Bubble Container (Icon + Message + Stats)
//                Row(
//                    modifier = GlanceModifier
//                        .background(ImageProvider(R.drawable.bubble_bg))
//                        .padding(horizontal = 10.dp, vertical = 6.dp)
//                        .clickable(actionSendBroadcast(Intent(context, CatWidgetProvider::class.java).apply {
//                            action = CatWidgetProvider.ACTION_TAP_STATUS
//                        })),
//                    verticalAlignment = Alignment.CenterVertically
//                ) {
//                    if (statusIconRes != 0) {
//                        Image(
//                            provider = ImageProvider(statusIconRes),
//                            contentDescription = "Status Icon",
//                            modifier = GlanceModifier.size(22.dp)
//                        )
//                        Spacer(modifier = GlanceModifier.width(4.dp))
//                    }
//
//                    val displayText = if (statusMessage.isNotEmpty()) {
//                        "$statusMessage (H:$hunger E:$energy B:$boredom)"
//                    } else {
//                        "H:$hunger E:$energy B:$boredom"
//                    }
//
//                    Text(
//                        text = displayText,
//                        style = TextStyle(
//                            fontSize = 11.sp,
//                            fontWeight = FontWeight.Bold,
//                            color = ColorProvider(android.graphics.Color.BLACK)
//                        )
//                    )
//                }
//
//                Spacer(modifier = GlanceModifier.height(4.dp))
//
//                // Cat Companion Image
//                Image(
//                    provider = ImageProvider(catImageRes),
//                    contentDescription = "Cat Companion",
//                    modifier = GlanceModifier
//                        .size(110.dp)
//                        .clickable(actionSendBroadcast(Intent(context, CatWidgetProvider::class.java).apply {
//                            action = CatWidgetProvider.ACTION_TAP_CAT
//                        }))
//                )
//            }
//
//            // Top Bar Controls (Mute button, Test button, Reset button)
//            Row(
//                modifier = GlanceModifier
//                    .fillMaxWidth()
//                    .padding(6.dp),
//                horizontalAlignment = Alignment.Start,
//                verticalAlignment = Alignment.CenterVertically
//            ) {
//                Text(
//                    text = "Test",
//                    style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = ColorProvider(android.graphics.Color.BLACK)),
//                    modifier = GlanceModifier
//                        .background(ImageProvider(R.drawable.bubble_bg))
//                        .padding(horizontal = 6.dp, vertical = 2.dp)
//                        .clickable(actionSendBroadcast(Intent(context, CatWidgetProvider::class.java).apply {
//                            action = CatWidgetProvider.ACTION_TEST_COMMAND
//                        }))
//                )
//                Spacer(modifier = GlanceModifier.width(6.dp))
//                Text(
//                    text = "Reset",
//                    style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = ColorProvider(android.graphics.Color.BLACK)),
//                    modifier = GlanceModifier
//                        .background(ImageProvider(R.drawable.bubble_bg))
//                        .padding(horizontal = 6.dp, vertical = 2.dp)
//                        .clickable(actionSendBroadcast(Intent(context, CatWidgetProvider::class.java).apply {
//                            action = CatWidgetProvider.ACTION_RESET
//                        }))
//                )
//
//                Spacer(modifier = GlanceModifier.defaultWeight())
//
//                // Mute Toggle Button
//                Image(
//                    provider = ImageProvider(muteIconRes),
//                    contentDescription = "Mute Toggle",
//                    modifier = GlanceModifier
//                        .size(24.dp)
//                        .clickable(actionSendBroadcast(Intent(context, CatWidgetProvider::class.java).apply {
//                            action = CatWidgetProvider.ACTION_TOGGLE_MUTE
//                        }))
//                )
//            }
//        }
//    }
//}
