package com.example.widgettesting

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CatWidgetTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CatWidgetDashboard()
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }

        ContextCompat.startForegroundService(
            this,
            Intent(this, CatWidgetService::class.java).apply {
                action = CatWidgetProvider.ACTION_RESET
            }
        )
    }
}

@Composable
fun CatWidgetTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFFFF9800),
            secondary = Color(0xFF4CAF50),
            tertiary = Color(0xFF2196F3),
            background = Color(0xFFFFF8E1),
            surface = Color.White
        ),
        content = content
    )
}

@Composable
fun CatWidgetDashboard() {
    val context = LocalContext.current
    var hunger by remember { mutableIntStateOf(0) }
    var energy by remember { mutableIntStateOf(100) }
    var boredom by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("cat_widget_prefs", Context.MODE_PRIVATE)
        hunger = prefs.getInt("hunger", 0)
        energy = prefs.getInt("energy", 100)
        boredom = prefs.getInt("boredom", 0)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Header
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "🐱 Cat Companion Widget",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF5D4037)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Jetpack Compose & Glance Edition",
                fontSize = 14.sp,
                color = Color(0xFF8D6E63)
            )
        }

        // Card with instructions & 3-stats overview
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Cat Stats Dashboard",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))

                StatBar(label = "🍔 Hunger (หิว)", value = hunger, color = Color(0xFFE53935))
                Spacer(modifier = Modifier.height(12.dp))
                StatBar(label = "⚡ Energy (พลังงาน)", value = energy, color = Color(0xFF4CAF50))
                Spacer(modifier = Modifier.height(12.dp))
                StatBar(label = "🎾 Boredom (ความเบื่อ)", value = boredom, color = Color(0xFF1E88E5))
            }
        }

        // Instructions Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "📌 วิธีใช้งาน Widget:",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color(0xFFE65100)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "1. พับหน้าจอหรือกด Home ออกจากแอป\n2. กดค้างที่หน้าจอโฮม เลือก Widgets -> Cat Widget\n3. เมื่อพับแอป Widget จะรันอัปเดต 3 ค่าสถานะ และเล่นท่าทางตามระดับความหิว/ง่วง/เบื่ออัตโนมัติ!",
                    fontSize = 13.sp,
                    color = Color(0xFF4E342E)
                )
            }
        }

        // Quick Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = {
                    sendWidgetAction(context, CatWidgetProvider.ACTION_FEED)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
            ) {
                Text("🍔 Feed")
            }

            Button(
                onClick = {
                    sendWidgetAction(context, CatWidgetProvider.ACTION_RESET)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF757575))
            ) {
                Text("🔄 Reset")
            }
        }
    }
}

@Composable
fun StatBar(label: String, value: Int, color: Color) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(text = "$value/100", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = color)
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { value / 100f },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp),
            color = color,
            trackColor = Color(0xFFEEEEEE)
        )
    }
}

private fun sendWidgetAction(context: Context, action: String) {
    val intent = Intent(context, CatWidgetProvider::class.java).apply {
        this.action = action
    }
    context.sendBroadcast(intent)
}