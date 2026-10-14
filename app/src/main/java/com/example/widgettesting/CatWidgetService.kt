package com.example.widgettesting

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.random.Random

class CatWidgetService : Service() {

    private val CHANNEL_ID = "CatWidgetServiceChannel"
    private val TAG = "CatWidgetDebug"
    private var serviceJob: Job? = null
    private var actionJob: Job? = null
    private var itemFallJob: Job? = null
    private var walkTestJob: Job? = null
    private var jumpJob: Job? = null
    private var squishJob: Job? = null
    private var chaseJob: Job? = null
    private var drawerAnimJob: Job? = null
    private var catToyPickJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    private var isDrawerOpen = false
    private var drawerProgress = 0f
    private var isDrawerAnimating = false
    private var lastToyChooseTime = 0L
    private val TOY_CHOOSE_COOLDOWN_MS = 20000L

    // Share panel width with the layout and slide animation.
    private val drawerPanelWidthPx: Int
        get() = resources.getDimensionPixelSize(R.dimen.drawer_panel_width)

    private var isFirstRun = true
    private var catState = State.IDLE
    private var catX = 0
    private var catY = 0
    private var targetX = 0
    private var targetY = 0

    // ---- ทิศทางการหันหน้าของแมว (ซ้าย = true, ขวา = false) ----
    private var isFacingLeft = false

    // Message and Status Tracking
    private var unityMessage: String? = null
    private var currentStatusIconRes: Int? = null
    private var interactionReactionIconRes: Int? = null
    private var interactionReactionJob: Job? = null
    private val visibleCatBoundsCache = android.util.LruCache<String, android.graphics.Rect>(128)
    private val catFloorPaddingCache = android.util.LruCache<String, Int>(64)
    private val CAT_FLOOR_GAP_DP = 6f

    // Head positions within the visible artwork, before horizontal mirroring.
    // Stretching raises the tail above the head, so the overall bounds are misleading.
    private val reactionHeadAnchors = mapOf(
        "blackcat_stresh/stresh1.png" to android.graphics.PointF(0.33f, 0.39f),
        "blackcat_stresh/stresh2.png" to android.graphics.PointF(0.33f, 0.36f),
        "blackcat_stresh/stresh3.png" to android.graphics.PointF(0.30f, 0.46f)
    )

    private fun reactionAnchor(frame: String, bounds: android.graphics.Rect): android.graphics.PointF {
        val head = reactionHeadAnchors[frame] ?: android.graphics.PointF(0.5f, 0f)
        val horizontalFraction = if (isFacingLeft) 1f - head.x else head.x
        return android.graphics.PointF(
            bounds.left + bounds.width() * horizontalFraction,
            bounds.top + bounds.height() * head.y
        )
    }

    private fun catFloorPadding(frames: Array<String>, width: Int, height: Int): Int {
        val key = "${frames.joinToString()}_${width}_$height"
        catFloorPaddingCache.get(key)?.let { return it }
        // Use the lowest contact point of the whole sequence, not each frame.
        // This removes asset padding without cancelling animated foot lifts or rolls.
        val padding = frames.mapNotNull { frame ->
            getBitmapFromAsset(frame, width, height)?.let { bitmap ->
                val bounds = visibleCatBounds(bitmap, "${frame}_${width}_${height}_false")
                height - bounds.bottom
            }
        }.minOrNull() ?: 0
        catFloorPaddingCache.put(key, padding)
        return padding
    }

    private fun visibleCatBounds(bitmap: android.graphics.Bitmap, key: String): android.graphics.Rect {
        visibleCatBoundsCache.get(key)?.let { return it }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var left = bitmap.width
        var top = bitmap.height
        var right = 0
        var bottom = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if ((pixels[y * bitmap.width + x] ushr 24) > 32) {
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x + 1)
                    bottom = maxOf(bottom, y + 1)
                }
            }
        }
        val bounds = if (right > left && bottom > top) {
            android.graphics.Rect(left, top, right, bottom)
        } else {
            android.graphics.Rect(0, 0, bitmap.width, bitmap.height)
        }
        visibleCatBoundsCache.put(key, bounds)
        return bounds
    }

    // Brief feedback overlays the status without changing the cat's action or needs.
    private fun showInteractionReaction(iconRes: Int) {
        interactionReactionJob?.cancel()
        interactionReactionIconRes = iconRes
        updateWidget()
        interactionReactionJob = scope.launch {
            delay(1500L)
            interactionReactionIconRes = null
            updateWidget()
        }
    }
    private var randomMessage: String? = null

    // Animation frame tracking (เฟรมเปลี่ยนตามเวลา ไม่ใช่ตามจำนวนครั้งที่เรียก updateWidget)
    private var currentFrameIndex = 0
    private var isStartingWalk = false
    private var lastFrameAdvanceTime = 0L
    private val FRAME_INTERVAL_MS = 180L   // ยิ่งมากยิ่งช้า
    private val bitmapCache = mutableMapOf<String, android.graphics.Bitmap>()
    private val BITMAP_CACHE_MAX = 120

    // ไอเทมลงถึงพื้นแล้วหรือยัง (ใช้ให้แมวรอกล่องตก)
    private var itemLanded = false

    private var isMuted = false
    private val meowSounds = arrayOf(R.raw.meow01, R.raw.meow02, R.raw.meow03, R.raw.meow04)

    private var eatPlayer: MediaPlayer? = null
    private var clickPlayer: MediaPlayer? = null

    private var currentSteps: Int = 0
    private var walkTargetSteps: Int = 0

    // ---- Squash & Stretch effect state ----
    private var squishScaleX = 1f
    private var squishScaleY = 1f

    // ---- 3 Stats System State ----
    private var hunger: Int = 0
    private var energy: Int = 100
    private var boredom: Int = 0
    private var isAppOpen: Boolean = false

    private val PREFS_NAME = "cat_widget_prefs"
    private val KEY_HUNGER = "hunger"
    private val KEY_ENERGY = "energy"
    private val KEY_BOREDOM = "boredom"
    private val KEY_IS_APP_OPEN = "is_app_open"

    private var statTickCounter = 0

    // ---- Chase (ชวนเล่นจับ) event state ----
    private var chaseActive = false
    private var chaseCaught = false
    private var chaseStarted = false
    private var chaseHits = 0
    private val CHASE_ROUNDS = 5

    private val chaseInviteMsgs =
        listOf("มาเล่นกับเราสิ~", "เบื่อจังเลย เล่นกันมั้ย?", "จับเราให้ได้สิ~")
    private val chaseFleeMsgs =
        listOf("โดนละ! หนีดีกว่า~", "จับได้แล้วเหรอ! ไปล่ะนะ~", "เก่งนี่... แต่จับไม่ทันหรอก!")
    private val chaseTauntMsgs =
        listOf("จับไม่ได้หรอก~ เบ้ๆ", "ช้าจัง~ ๆ", "พลาดละสิ! ฮิๆ", "อยู่นี่นะ~ จับไม่โดน")
    private val chaseTiredMsgs = listOf(
        "เหนื่อยแล้ววว... จับเก่งจริงๆ หอบๆ",
        "เหนื่อยแล้ว ไม่ไหวแล้วน้า~ หอบๆ",
        "เหนื่อยแล้ว... แต่จับเราไม่ได้เลยนะ! หอบๆ"
    )
    private var isJumpscareActive = false
    private var itemScale: Float = 1.0f

    private fun saveStatsToPrefs() {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val configInfo = statusConfigMap[catState]
            val iconRes = currentStatusIconRes ?: configInfo?.iconRes ?: 0
            val displayMessage = unityMessage ?: configInfo?.message ?: randomMessage ?: ""

            prefs.edit()
                .putInt(KEY_HUNGER, hunger)
                .putInt(KEY_ENERGY, energy)
                .putInt(KEY_BOREDOM, boredom)
                .putBoolean(KEY_IS_APP_OPEN, isAppOpen)
                .putString("cat_state", catState.name)
                .putString("status_message", displayMessage)
                .putInt("status_icon_res", iconRes)
                .putBoolean("is_muted", isMuted)
                .apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadStatsFromPrefs() {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            hunger = prefs.getInt(KEY_HUNGER, 0).coerceIn(0, 100)
            energy = prefs.getInt(KEY_ENERGY, 100).coerceIn(0, 100)
            boredom = prefs.getInt(KEY_BOREDOM, 0).coerceIn(0, 100)
            isAppOpen = prefs.getBoolean(KEY_IS_APP_OPEN, false)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun broadcastStatsToUnity() {
        saveStatsToPrefs()
        val intent = Intent(CatWidgetProvider.ACTION_STATS_UPDATED_TO_UNITY).apply {
            putExtra("hunger", hunger)
            putExtra("energy", energy)
            putExtra("boredom", boredom)
            putExtra("isAppOpen", isAppOpen)
        }
        sendBroadcast(intent)
        Log.d(
            TAG,
            "broadcastStatsToUnity: hunger=$hunger, energy=$energy, boredom=$boredom, isAppOpen=$isAppOpen"
        )
    }

    private val idleRandomStateWeights: List<Pair<State, Int>> = listOf(
        State.PLAYING to 30,
        State.JUMPING to 50,
        State.ROLLING to 20,
        State.STRETCH to 15,
        State.ITCHY to 10,
        State.GROOMING to 45,
        State.NAP to 5
    )

    enum class Emotion {
        CONTENT, HAPPY, BORED, HUNGRY, TIRED
    }

    private var currentEmotion: Emotion = Emotion.CONTENT
    private var lastEmotionChangeTime: Long = 0L
    private val MIN_EMOTION_DURATION_MS = 15_000L

    private var lastChaseTime: Long = 0L
    private val CHASE_COOLDOWN_MS = 60_000L

    private var lastSpecialItemTime: Long = 0L
    private val SPECIAL_ITEM_COOLDOWN_MS = 30_000L

    private fun updateEmotion(): Emotion {
        val now = System.currentTimeMillis()
        val targetEmotion = when {
            energy <= 25 || (currentEmotion == Emotion.TIRED && energy < 40) -> Emotion.TIRED
            hunger >= 65 || (currentEmotion == Emotion.HUNGRY && hunger > 50) -> Emotion.HUNGRY
            boredom >= 65 || (currentEmotion == Emotion.BORED && boredom > 50) -> Emotion.BORED
            energy >= 60 && hunger <= 35 && boredom <= 35 -> Emotion.HAPPY
            else -> Emotion.CONTENT
        }

        if (targetEmotion != currentEmotion && (now - lastEmotionChangeTime >= MIN_EMOTION_DURATION_MS)) {
            currentEmotion = targetEmotion
            lastEmotionChangeTime = now
            Log.d(
                TAG,
                "Cat Emotion updated to: $currentEmotion (Hunger: $hunger, Energy: $energy, Boredom: $boredom)"
            )
        }
        return currentEmotion
    }

    private fun pickActionForEmotion(emotion: Emotion): State {
        val now = System.currentTimeMillis()
        return when (emotion) {
            Emotion.CONTENT -> {
                val weights = listOf(
                    State.WALKING to 40,
                    State.GROOMING to 20,
                    State.STRETCH to 15,
                    State.SNUGGLE to 15,
                    State.ROLLING to 30
                )
                pickWeightedState(weights)
            }

            Emotion.HAPPY -> {
                val canChooseToy = (now - lastToyChooseTime >= TOY_CHOOSE_COOLDOWN_MS)
                val weights = mutableListOf(
                    State.PLAYING to 30,
                    State.ROLLING to 40,
                    State.JUMPING to 15,
                    State.STRETCH to 15,
                    State.WALKING to 10
                )
                if (canChooseToy) {
                    weights.add(State.CAT_CHOOSE_TOY to 15)
                }
                pickWeightedState(weights)
            }

            Emotion.BORED -> {
                val canChase = (now - lastChaseTime >= CHASE_COOLDOWN_MS)
                val canChooseToy = (now - lastToyChooseTime >= TOY_CHOOSE_COOLDOWN_MS)
                val weights = mutableListOf(
                    State.WALKING to 35,
                    State.ITCHY to 25,
                    State.PLAYING to 20,
                    State.ROLLING to 15
                )
                if (canChase) {
                    weights.add(State.CHASE_INVITE to 10)
                }
                if (canChooseToy) {
                    weights.add(State.CAT_CHOOSE_TOY to 15)
                }
                pickWeightedState(weights)
            }

            Emotion.HUNGRY -> {
                val weights = listOf(
                    State.HUNGRY to 50,
                    State.WALKING to 35,
                    State.IDLE to 15
                )
                pickWeightedState(weights)
            }

            Emotion.TIRED -> {
                val weights = listOf(
                    State.NAP to 50,
                    State.STRETCH to 30,
                    State.IDLE to 20
                )
                pickWeightedState(weights)
            }
        }
    }

    private fun pickWeightedState(weights: List<Pair<State, Int>>): State {
        val totalWeight = weights.sumOf { it.second }
        var roll = Random.nextInt(totalWeight)
        for ((state, weight) in weights) {
            if (roll < weight) return state
            roll -= weight
        }
        return weights.last().first
    }

    private fun pickWeightedIdleState(): State {
        val totalWeight = idleRandomStateWeights.sumOf { it.second }
        val originalRoll = Random.nextInt(totalWeight)
        var roll = originalRoll
        for ((state, weight) in idleRandomStateWeights) {
            if (roll < weight) return state
            roll -= weight
        }
        return idleRandomStateWeights.last().first
    }

    enum class State {
        IDLE, WALKING, WALKING_TO_CENTER, EATING, JUMPING, PLAYING, STRETCH, NAP, SNUGGLE, GROOMING, WALK_COUNTING,
        WALKING_TO_ITEM, WAND_PLAY, BOX_HIDDEN, BOX_EMERGE, TRAIN_IDLE, HUNGRY, ANGRY, ITCHY, ROLLING,
        CHASE_INVITE, CHASE_HOP, CAT_CHOOSE_TOY
    }

    enum class ItemType { WAND, BOX, FOOD, COCKROACH, SNAKE }

    private val itemTypes =
        arrayOf(ItemType.WAND, ItemType.BOX, ItemType.FOOD, ItemType.COCKROACH, ItemType.SNAKE)

    private var activeItem: ItemType? = null
    private var itemX = 0
    private var itemY = 0

    data class StatusInfo(
        val iconRes: Int? = null,
        val message: String? = null,
        val isClickable: Boolean = false,
        val targetState: State? = null,
        val targetDuration: Long = 4000L
    )

    data class CatReaction(
        val state: State,
        val duration: Long = 3000L,
        val message: String? = null,
        val iconRes: Int? = null
    )

    private val statusConfigMap = mapOf(
        State.HUNGRY to StatusInfo(
            iconRes = R.drawable.ic_status_food,
            message = "hungry",
            isClickable = true,
            targetState = State.EATING,
            targetDuration = 5000L
        ),
        State.ITCHY to StatusInfo(
            iconRes = R.drawable.ic_status_scratch,
            message = "เกาพุงหน่อย!",
            isClickable = true,
            targetState = State.STRETCH,
            targetDuration = 4000L
        ),
        State.NAP to StatusInfo(
            iconRes = R.drawable.ic_status_zzz,
            message = "",
            isClickable = false
        ),
        State.SNUGGLE to StatusInfo(
            iconRes = R.drawable.ic_status_heart,
            message = "",
            isClickable = false
        ),
        State.EATING to StatusInfo(
            iconRes = R.drawable.ic_status_food,
            message = "อร่อยจัง!",
            isClickable = false
        ),
        State.PLAYING to StatusInfo(
            iconRes = R.drawable.ic_status_heart,
            message = "เล่นกัน!",
            isClickable = false
        ),
        State.ANGRY to StatusInfo(
            iconRes = R.drawable.ic_status_angry,
            message = "โกรธแล้วนะ!",
            isClickable = true,
            targetState = State.SNUGGLE,
            targetDuration = 3500L
        ),
        State.STRETCH to StatusInfo(
            iconRes = R.drawable.ic_status_scratch,
            message = "บิดขี้เกียจ~",
            isClickable = false
        ),
        State.JUMPING to StatusInfo(
            iconRes = R.drawable.ic_status_scratch,
            message = "กระโดดดึ๋ง!",
            isClickable = false
        ),
        State.ROLLING to StatusInfo(
            iconRes = R.drawable.ic_status_heart,
            message = "กลิ้งเกลือก~",
            isClickable = false
        )
    )

    private val idleFrames = arrayOf(
        "cati_idle_new/00_Cati_Idle_new.png", "cati_idle_new/01_Cati_Idle_new.png",
        "cati_idle_new/02_Cati_Idle_new.png", "cati_idle_new/03_Cati_Idle_new.png",
        "cati_idle_new/04_Cati_Idle_new.png", "cati_idle_new/05_Cati_Idle_new.png",
        "cati_idle_new/06_Cati_Idle_new.png", "cati_idle_new/07_Cati_Idle_new.png",
        "cati_idle_new/08_Cati_Idle_new.png", "cati_idle_new/09_Cati_Idle_new.png",
        "cati_idle_new/10_Cati_Idle_new.png", "cati_idle_new/11_Cati_Idle_new.png"
    )
    private val walkFrames = arrayOf(
        "cati_walk_new/00_Cati_Walk_new.png", "cati_walk_new/01_Cati_Walk_new.png",
        "cati_walk_new/02_Cati_Walk_new.png", "cati_walk_new/03_Cati_Walk_new.png",
        "cati_walk_new/04_Cati_Walk_new.png", "cati_walk_new/05_Cati_Walk_new.png",
        "cati_walk_new/06_Cati_Walk_new.png", "cati_walk_new/07_Cati_Walk_new.png",
        "cati_walk_new/08_Cati_Walk_new.png", "cati_walk_new/09_Cati_Walk_new.png",
        "cati_walk_new/10_Cati_Walk_new.png", "cati_walk_new/11_Cati_Walk_new.png"
    )
    private val idleToWalkFrames = arrayOf(
        "cati_idle_to_walk/transition_01.png", "cati_idle_to_walk/transition_02.png"
    )
    private val eatFrames = arrayOf("blackcat_eat/eat1.png", "blackcat_eat/eat2.png", "blackcat_eat/eat3.png", "blackcat_eat/eat4.png")
    private val playFrames = arrayOf("blackcat_play/play1.png", "blackcat_play/play2.png", "blackcat_play/play3.png")
    private val stretchFrames = arrayOf("blackcat_stresh/stresh1.png", "blackcat_stresh/stresh2.png", "blackcat_stresh/stresh3.png")
    private val angryFrames = arrayOf(
        "blackcat_angry/angry4.png",
        "blackcat_angry/angry5.png",
        "blackcat_angry/angry6.png",
        "blackcat_angry/angry7.png",
    )
    private val napFrames = arrayOf("blackcat_nap/nap1.png", "blackcat_nap/nap2.png", "blackcat_nap/nap3.png")
    private val snuggleFrames = arrayOf("blackcat_nap/nap3.png")
    private val groomingFrames = arrayOf("blackcat_grooming/grooming1.png", "blackcat_grooming/grooming2.png", "blackcat_grooming/grooming3.png", "blackcat_grooming/grooming4.png")
    private val hungryFrames = arrayOf("blackcat_hungry/hungry1.png", "blackcat_hungry/hungry2.png", "blackcat_hungry/hungry3.png")

    private val jumpFrames = arrayOf(
        "blackcat_jump/jump1.png",
        "blackcat_jump/jump2.png",
        "blackcat_jump/jump3.png",
        "blackcat_jump/jump4.png",
        "blackcat_jump/jump5.png",
        "blackcat_jump/jump6.png"

    )

    private val rollFrames = arrayOf("blackcat_roll/roll1.png", "blackcat_roll/roll2.png", "blackcat_roll/roll3.png", "blackcat_roll/roll4.png")
    private val wandPlayFrames = arrayOf("catwand/catwand_01.gif", "catwand/catwand_02.gif", "catwand/catwand_03.gif", "catwand/catwand_04.gif")
    private val boxHiddenFrames = arrayOf("blackcat_anim/nap.png")
    private val boxEmergeFrames = arrayOf("catboxemerge/catboxemerge_1.gif", "catboxemerge/catboxemerge_2.gif", "catboxemerge/catboxemerge_3.gif", "catboxemerge/catboxemerge_4.gif")
    private val trainIdleFrames = arrayOf("cattrain/cattrain_1.gif", "cattrain/cattrain_2.gif")

    private fun getFramesForState(): Array<String> = when (catState) {
        State.EATING -> eatFrames
        State.PLAYING -> playFrames
        State.WALKING, State.WALKING_TO_CENTER, State.WALKING_TO_ITEM ->
            if (isStartingWalk) idleToWalkFrames else walkFrames
        State.STRETCH -> stretchFrames
        State.ANGRY -> angryFrames
        State.NAP -> napFrames
        State.SNUGGLE -> snuggleFrames
        State.GROOMING -> groomingFrames
        State.HUNGRY -> hungryFrames
        State.WAND_PLAY -> wandPlayFrames
        State.BOX_HIDDEN -> boxHiddenFrames
        State.BOX_EMERGE -> boxEmergeFrames
        State.TRAIN_IDLE -> trainIdleFrames
        State.JUMPING -> jumpFrames
        State.ROLLING -> rollFrames
        State.CHASE_INVITE -> napFrames
        State.CHASE_HOP -> jumpFrames
        State.CAT_CHOOSE_TOY -> playFrames
        else -> idleFrames
    }

    private fun setCatState(newState: State) {
        if (catState == newState) return
        isStartingWalk = catState == State.IDLE && newState in listOf(
            State.WALKING, State.WALKING_TO_CENTER, State.WALKING_TO_ITEM
        )
        catState = newState
        // เปลี่ยนท่าใหม่ เริ่มเฟรมแรกเสมอ
        currentFrameIndex = 0
        lastFrameAdvanceTime = System.currentTimeMillis()

        when (catState) {
            State.JUMPING, State.PLAYING, State.STRETCH, State.ROLLING, State.GROOMING, State.NAP, State.EATING, State.CHASE_INVITE -> {
                playMeowSound()
            }
            else -> {}
        }
    }

    private fun safeStartForeground() {
        try {
            androidx.core.app.ServiceCompat.startForeground(
                this, 1, createNotification(),
                if (Build.VERSION.SDK_INT >= 34)
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            )
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        safeStartForeground()
        loadStatsFromPrefs()

        try {
            eatPlayer = MediaPlayer.create(this, R.raw.eat)
            clickPlayer = MediaPlayer.create(this, R.raw.click)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        startAnimationLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand received action = ${intent?.action}")
        safeStartForeground()

        try {
            if (intent?.action == null) {
                // Service ถูก Android kill แล้ว restart → รีเซ็ต state และเริ่ม loop ใหม่
                resetState()
                ensureAnimationLoopRunning()
                return START_STICKY
            }
            if (intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE) {
                // widget ใหม่ถูกเพิ่ม/อัปเดต → แค่ ensure loop รันอยู่แล้ว update UI
                ensureAnimationLoopRunning()
                updateWidget()
                return START_STICKY
            }
            when (intent?.action) {
                CatWidgetProvider.ACTION_TOGGLE_MUTE -> {
                    isMuted = !isMuted
                    playClickSound()
                    updateWidget()
                }

                CatWidgetProvider.ACTION_TAP_CAT -> {
                    playClickSound()
                    triggerSquishEffect(0.3f)
                    if (chaseActive) handleChaseCatTap() else handleDirectCatTap()
                }

                CatWidgetProvider.ACTION_TAP_STATUS -> {
                    playClickSound()
                    triggerSquishEffect(0.2f)
                    handleStatusBubbleTap()
                }

                CatWidgetProvider.ACTION_FEED -> {
                    playClickSound()
                    playEatSound()
                    triggerSquishEffect(0.3f)
                    hunger = (hunger - 60).coerceAtLeast(0)
                    energy = (energy + 15).coerceAtMost(100)
                    boredom = (boredom - 10).coerceAtLeast(0)
                    saveStatsToPrefs()
                    val foodInfo = statusConfigMap[State.HUNGRY]
                    performStateAction(State.EATING, 5000L, "อร่อยจัง!", foodInfo?.iconRes)
                }

                CatWidgetProvider.ACTION_APP_FOCUS_CHANGED -> {
                    val focusState = intent.getBooleanExtra("isAppOpen", isAppOpen) || intent.getBooleanExtra("is_app_open", false)
                    if (intent.hasExtra("hunger")) hunger = intent.getIntExtra("hunger", hunger).coerceIn(0, 100)
                    if (intent.hasExtra("energy")) energy = intent.getIntExtra("energy", energy).coerceIn(0, 100)
                    if (intent.hasExtra("boredom")) boredom = intent.getIntExtra("boredom", boredom).coerceIn(0, 100)
                    isAppOpen = focusState
                    if (isAppOpen) broadcastStatsToUnity() else saveStatsToPrefs()
                    updateWidget()
                }

                CatWidgetProvider.ACTION_SYNC_STATS -> {
                    if (intent.hasExtra("hunger")) hunger = intent.getIntExtra("hunger", hunger).coerceIn(0, 100)
                    if (intent.hasExtra("energy")) energy = intent.getIntExtra("energy", energy).coerceIn(0, 100)
                    if (intent.hasExtra("boredom")) boredom = intent.getIntExtra("boredom", boredom).coerceIn(0, 100)
                    broadcastStatsToUnity()
                    updateWidget()
                }

                CatWidgetProvider.ACTION_TAP_WIDGET -> {
                    playClickSound()
                    if (isDrawerOpen) {
                        animateCloseDrawer()
                    } else if (chaseActive) {
                        handleChaseMissTap()
                    } else {
                        triggerSquishEffect(0.15f)
                        if (Random.nextInt(100) < 30) {
                            playMeowSound()
                        }
                    }
                }

                CatWidgetProvider.ACTION_TOGGLE_ITEM_DRAWER -> {
                    if (isDrawerOpen) animateCloseDrawer() else animateOpenDrawer()
                }

                CatWidgetProvider.ACTION_OPEN_ITEM_DRAWER -> {
                    animateOpenDrawer()
                }

                CatWidgetProvider.ACTION_CLOSE_ITEM_DRAWER -> {
                    animateCloseDrawer()
                }

                CatWidgetProvider.ACTION_SELECT_WAND -> handleSelectItem(ItemType.WAND)
                CatWidgetProvider.ACTION_SELECT_BOX -> handleSelectItem(ItemType.BOX)
                CatWidgetProvider.ACTION_SELECT_FOOD -> handleSelectItem(ItemType.FOOD)
                CatWidgetProvider.ACTION_SELECT_COCKROACH -> handleSelectItem(ItemType.COCKROACH)
                CatWidgetProvider.ACTION_SELECT_SNAKE -> handleSelectItem(ItemType.SNAKE)
                CatWidgetProvider.ACTION_TEST_CAT_PICK -> {
                    playClickSound()
                    startCatChooseToySequence()
                }

                CatWidgetProvider.ACTION_UNITY_COMMAND -> {
                    parseAndExecuteUnityCommand(intent)
                }

                CatWidgetProvider.ACTION_TEST_COMMAND -> {
                    hunger = 80
                    energy = 15
                    boredom = 80
                    saveStatsToPrefs()
                    val hungryInfo = statusConfigMap[State.HUNGRY]
                    performStateAction(
                        State.HUNGRY,
                        6000L,
                        hungryInfo?.message ?: "หิวจังเลย!",
                        hungryInfo?.iconRes
                    )
                }

                CatWidgetProvider.ACTION_RESET -> {
                    playClickSound()
                    resetState()
                }

                CatWidgetProvider.ACTION_TEST_WALKCOUNT -> {
                    playClickSound()
                    if (catState == State.WALK_COUNTING || catState == State.WALKING_TO_CENTER) {
                        stopWalkCountTest()
                    } else {
                        startWalkCountTest()
                    }
                }

                CatWidgetProvider.ACTION_TEST_JUMP -> {
                    playClickSound()
                    if (catState == State.JUMPING) stopJumpAround() else startJumpAround()
                }

                CatWidgetProvider.ACTION_TEST_CHASE -> {
                    playClickSound()
                    if (chaseActive) {
                        cancelChase()
                        setCatState(State.IDLE)
                        unityMessage = null
                        currentStatusIconRes = null
                        updateWidget()
                    } else {
                        startChaseEvent()
                    }
                }

            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return START_STICKY
    }

    private fun parseAndExecuteUnityCommand(intent: Intent) {
        if (intent.hasExtra("hunger")) hunger = intent.getIntExtra("hunger", hunger).coerceIn(0, 100)
        if (intent.hasExtra("energy")) energy = intent.getIntExtra("energy", energy).coerceIn(0, 100)
        if (intent.hasExtra("boredom")) boredom = intent.getIntExtra("boredom", boredom).coerceIn(0, 100)

        val rawAction = intent.getStringExtra("action") ?: intent.getStringExtra("command")
        var durationMs = intent.getLongExtra("duration", 0L)
        if (durationMs == 0L) {
            durationMs = intent.getStringExtra("duration")?.toLongOrNull() ?: 0L
        }

        var actionName = rawAction
        if (rawAction != null && rawAction.contains(",")) {
            val parts = rawAction.split(",")
            actionName = parts[0].trim()
            durationMs = parts.getOrNull(1)?.trim()?.toLongOrNull() ?: 3000L
        }

        val lowerAction = actionName?.lowercase()
        if (lowerAction == "chase" || lowerAction == "tease" || lowerAction == "invite") {
            startChaseEvent()
            return
        }

        val message = intent.getStringExtra("message")
        val targetState = when (lowerAction) {
            "stretch" -> State.STRETCH
            "nap", "sleep" -> State.NAP
            "snuggle" -> State.SNUGGLE
            "grooming" -> State.GROOMING
            "eating", "eat", "feed" -> State.EATING
            "playing", "play" -> State.PLAYING
            "hungry" -> State.HUNGRY
            "angry" -> State.ANGRY
            "itchy", "scratch" -> State.ITCHY
            "rolling", "roll" -> State.ROLLING
            "jumping", "jump" -> State.JUMPING
            else -> State.IDLE
        }

        val finalDuration = if (durationMs > 0) durationMs else 4000L
        if (targetState != State.IDLE) {
            if (targetState == State.JUMPING) {
                startJumpAround()
                return
            }
            val configInfo = statusConfigMap[targetState]

            if (catState != State.BOX_HIDDEN) {
                performStateAction(
                    targetState,
                    finalDuration,
                    message ?: configInfo?.message,
                    configInfo?.iconRes
                )
            }
        } else {
            cancelChase()
            setCatState(State.IDLE)
            unityMessage = message
            currentStatusIconRes = null
            saveStatsToPrefs()
            updateWidget()
        }
    }

    private fun handleDirectCatTap() {
        actionJob?.cancel()
        jumpJob?.cancel()
        val isBusyOrSleeping = (catState == State.EATING || catState == State.NAP)
        if (isBusyOrSleeping && Random.nextInt(100) < 30) {
            val msg = if (catState == State.EATING) "อย่ามากวนตอนกินข้าว!" else "ง่วงอยู่ อย่ากวนนะ!"
            performStateAction(State.ANGRY, 3500L, msg, R.drawable.ic_status_angry)
            return
        }

        val emotion = updateEmotion()
        val reaction = pickTapReactionForEmotion(emotion)
        showInteractionReaction(R.drawable.ic_status_heart)

        if (reaction.state == State.JUMPING) {
            startJumpAround()
        } else if (reaction.state == State.ROLLING) {
            startRolling()
        } else {
            performStateAction(
                reaction.state,
                reaction.duration,
                reaction.message,
                reaction.iconRes
            )
        }
    }

    private fun pickTapReactionForEmotion(emotion: Emotion): CatReaction {
        val pool = when (emotion) {
            Emotion.CONTENT -> listOf(
                CatReaction(State.SNUGGLE, 4000L, "แง๊ว~", R.drawable.ic_status_heart),
                CatReaction(State.STRETCH, 3000L, "บิดขี้เกียจ~", R.drawable.ic_status_scratch),
                CatReaction(State.ROLLING, 3500L, "กลิ้งเกลือก~", R.drawable.ic_status_heart),
                CatReaction(State.GROOMING, 3500L, "", null)
            )

            Emotion.HAPPY -> listOf(
                CatReaction(State.PLAYING, 3500L, "คิกๆ สนุกจัง!", R.drawable.ic_status_heart),
                CatReaction(State.ROLLING, 3500L, "กลิ้งๆ~", R.drawable.ic_status_heart),
                CatReaction(State.SNUGGLE, 4000L, "รักนะมนุษย์~", R.drawable.ic_status_heart),
                CatReaction(State.JUMPING, 3000L, "ดึ๋งๆ!", R.drawable.ic_status_scratch)
            )

            Emotion.BORED -> listOf(
                CatReaction(State.PLAYING, 3500L, "เล่นกันหน่อยสิ!", R.drawable.ic_status_heart),
                CatReaction(State.ITCHY, 4000L, "เกาพุงหน่อย!", R.drawable.ic_status_scratch),
                CatReaction(State.CHASE_INVITE, 3000L, "จับเราให้ได้สิ~", R.drawable.ic_status_heart)
            )

            Emotion.HUNGRY -> listOf(
                CatReaction(State.HUNGRY, 4000L, "หิวแล้วนะ ขอข้าวหน่อย!", R.drawable.ic_status_food)
            )

            Emotion.TIRED -> listOf(
                CatReaction(State.STRETCH, 3000L, "หาววว... ง่วงจัง", R.drawable.ic_status_scratch),
                CatReaction(State.NAP, 5000L, "ขอนอนก่อนนะ... Zzz", R.drawable.ic_status_zzz)
            )
        }
        return pool.random()
    }

    private fun handleStatusBubbleTap() {
        if (chaseActive || interactionReactionIconRes != null) return

        val currentConfig = statusConfigMap[catState]
        if (currentConfig != null && currentConfig.isClickable && currentConfig.targetState != null) {
            val targetState = currentConfig.targetState
            val nextConfig = statusConfigMap[targetState]
            performStateAction(
                targetState,
                currentConfig.targetDuration,
                nextConfig?.message,
                nextConfig?.iconRes
            ) {
                if (targetState == State.EATING) {
                    hunger = (hunger - 60).coerceAtLeast(0)
                    energy = (energy + 15).coerceAtMost(100)
                    boredom = (boredom - 10).coerceAtLeast(0)
                    saveStatsToPrefs()
                    playEatSound()
                }
            }
        }
    }

    private fun performStateAction(
        actionState: State,
        durationMs: Long,
        message: String? = null,
        iconRes: Int? = null,
        onStartAction: () -> Unit = {}
    ) {
        cancelChase()
        actionJob?.cancel()
        triggerSquishEffect(0.2f)
        actionJob = scope.launch {
            setCatState(actionState)
            unityMessage = message
            currentStatusIconRes = iconRes ?: statusConfigMap[actionState]?.iconRes

            when (actionState) {
                State.EATING -> {
                    hunger = (hunger - 60).coerceAtLeast(0)
                    energy = (energy + 15).coerceAtMost(100)
                    boredom = (boredom - 10).coerceAtLeast(0)
                }

                State.NAP -> {
                    energy = (energy + 60).coerceAtMost(100)
                    hunger = (hunger + 10).coerceAtMost(100)
                }

                State.PLAYING, State.WAND_PLAY, State.BOX_EMERGE, State.ROLLING -> {
                    boredom = (boredom - 60).coerceAtLeast(0)
                    energy = (energy - 15).coerceAtLeast(0)
                    hunger = (hunger + 15).coerceAtMost(100)
                }

                State.STRETCH, State.ITCHY, State.GROOMING, State.SNUGGLE -> {
                    boredom = (boredom - 30).coerceAtLeast(0)
                    energy = (energy - 5).coerceAtLeast(0)
                }

                else -> {}
            }
            saveStatsToPrefs()
            onStartAction()
            updateWidget()

            if (durationMs > 0) {
                delay(durationMs)
                if (catState == actionState) {
                    setCatState(State.IDLE)
                    unityMessage = null
                    currentStatusIconRes = null
                    updateWidget()
                }
            }
        }
    }

    private fun resetState() {
        interactionReactionJob?.cancel()
        interactionReactionIconRes = null
        actionJob?.cancel()
        walkTestJob?.cancel()
        itemFallJob?.cancel()
        jumpJob?.cancel()
        squishJob?.cancel()
        drawerAnimJob?.cancel()
        catToyPickJob?.cancel()
        cancelChase()
        isDrawerOpen = false
        drawerProgress = 0f
        isDrawerAnimating = false
        squishScaleX = 1f
        squishScaleY = 1f
        setCatState(State.IDLE)
        catX = 0
        catY = 0
        unityMessage = null
        currentStatusIconRes = null
        activeItem = null
        itemLanded = false
        randomMessage = null
        hunger = 0
        energy = 100
        boredom = 0
        isFacingLeft = false
        itemScale = 1.0f
        isJumpscareActive = false
        saveStatsToPrefs()
        updateWidget()
    }

    private fun animateOpenDrawer(onComplete: (() -> Unit)? = null) {
        if (isDrawerOpen && drawerProgress >= 1f) {
            onComplete?.invoke()
            return
        }
        drawerAnimJob?.cancel()
        drawerAnimJob = scope.launch {
            isDrawerAnimating = true
            isDrawerOpen = true
            playClickSound()

            val steps = listOf(0.15f, 0.35f, 0.55f, 0.75f, 0.90f, 1.0f)
            for (p in steps) {
                drawerProgress = p
                updateWidget()
                delay(25)
            }
            drawerProgress = 1.0f
            isDrawerAnimating = false
            updateWidget()
            onComplete?.invoke()
        }
    }

    private fun animateCloseDrawer(onComplete: (() -> Unit)? = null) {
        if (!isDrawerOpen && drawerProgress <= 0f) {
            onComplete?.invoke()
            return
        }
        drawerAnimJob?.cancel()
        drawerAnimJob = scope.launch {
            isDrawerAnimating = true
            playClickSound()

            val steps = listOf(0.85f, 0.65f, 0.45f, 0.25f, 0.10f, 0.0f)
            for (p in steps) {
                drawerProgress = p
                updateWidget()
                delay(25)
            }
            drawerProgress = 0.0f
            isDrawerOpen = false
            isDrawerAnimating = false
            updateWidget()
            onComplete?.invoke()
        }
    }

    private fun handleSelectItem(item: ItemType) {
        if (isDrawerAnimating) return
        playClickSound()
        animateCloseDrawer {
            spawnSelectedItem(item)
        }
    }

    private fun spawnSelectedItem(
        item: ItemType,
        targetLandX: Int? = null,
        targetLandY: Int? = null
    ) {
        if (catState in listOf(State.EATING, State.NAP, State.BOX_HIDDEN, State.BOX_EMERGE)) return

        val now = System.currentTimeMillis()
        if (item in listOf(ItemType.BOX, ItemType.COCKROACH, ItemType.SNAKE)) {
            lastSpecialItemTime = now
        }

        val (maxX, maxY) = getItemBounds(itemType = item)
        val safeX = targetLandX?.coerceIn(0, maxX) ?: if (maxX > 0) Random.nextInt(
            (maxX * 0.1f).toInt(),
            (maxX * 0.9f).toInt() + 1
        ) else 0

        // พิกัดพื้นล่างสุดคือ 0
        val safeY = targetLandY?.coerceIn(0, maxY) ?: 0

        playClickSound()
        actionJob?.cancel()
        itemFallJob?.cancel()

        itemScale = 1.0f
        activeItem = item
        dropItemThenAct(item, safeX, safeY)
    }

    private fun startCatChooseToySequence() {
        if (isDrawerAnimating || catState in listOf(
                State.EATING, State.NAP, State.BOX_HIDDEN, State.BOX_EMERGE, State.WALK_COUNTING
            )
        ) return
        cancelChase()
        actionJob?.cancel()
        catToyPickJob?.cancel()

        lastToyChooseTime = System.currentTimeMillis()
        catToyPickJob = scope.launch {
            val (maxX, maxY) = getMaxBounds()
            val density = resources.displayMetrics.density

            // 1. เดินไปใกล้ๆ แถบ Drawer ฝั่งขวามือ (Right Edge)
            val panelWpx = drawerPanelWidthPx
            val rightTargetX = (maxX - panelWpx / 2).coerceIn(0, maxX)

            setCatState(State.WALKING)
            targetX = rightTargetX
            targetY = 0
            isFacingLeft = false // หันหน้าไปทางขวาหาปุ่ม Drawer
            updateWidget()

            val step = (8 * density / 2f).toInt().coerceAtLeast(6)
            while (isStartingWalk || kotlin.math.abs(catX - rightTargetX) > step) {
                if (isStartingWalk) {
                    updateWidget()
                    delay(80)
                    continue
                }
                if (catX < rightTargetX) catX += step else catX -= step
                catX = catX.coerceIn(0, maxX)
                updateWidget()
                delay(80)
            }
            catX = rightTargetX

            // 2. แสดงท่าทางอยากเล่น + สั่งเปิด Drawer
            setCatState(State.PLAYING)
            playMeowSound()
            triggerSquishEffect(0.25f)
            unityMessage = listOf("อยากเล่นของเล่นจัง~", "เลือกของเล่นกันนะ!", "เล่นอันไหนดีนะ? ✨").random()
            currentStatusIconRes = R.drawable.ic_status_heart
            updateWidget()
            delay(800)

            var drawerOpened = false
            animateOpenDrawer {
                drawerOpened = true
            }
            while (!drawerOpened) {
                delay(30)
            }
            delay(300)

            // 3. สุ่มไอเทม แล้วคำนวณพิกัด Y ของปุ่มไอเทมนั้นใน Drawer
            val chosenItem = itemTypes.random()
            val slotIndex = itemTypes.indexOf(chosenItem)
            val targetSlotY = getDrawerSlotCatY(slotIndex)

            unityMessage = "เล็งชิ้นนี้ไว้แล้ว! 🐾"
            isFacingLeft = false // หันหน้าไปหาปุ่มฝั่งขวา
            setCatState(State.JUMPING)
            targetY = targetSlotY
            updateWidget()

            // 4. แมวไต่/กระโดดลอยขึ้นไปที่ตำแหน่งของปุ่มไอเทมนั้นโดยตรง
            val startY = catY
            val climbSteps = 12
            for (s in 1..climbSteps) {
                val p = s / climbSteps.toFloat()
                catY = (startY + (targetSlotY - startY) * p).toInt().coerceIn(0, maxY)
                updateWidget()
                delay(50)
            }
            catY = targetSlotY

            // 5. แมวแตะเลือกปุ่มไอเทมนั้น
            setCatState(State.CAT_CHOOSE_TOY)
            triggerSquishEffect(0.35f)
            playClickSound()
            playMeowSound()

            val itemName = when (chosenItem) {
                ItemType.WAND -> "ไหมพรม! 🧶"
                ItemType.BOX -> "กล่อง! 📦"
                ItemType.FOOD -> "อาหาร! 🥣"
                ItemType.COCKROACH -> "แมลงสาบ! 🪳"
                ItemType.SNAKE -> "งู! 🐍"
            }
            unityMessage = "เลือก $itemName"
            currentStatusIconRes = null
            updateWidget()
            delay(900)

            // 6. ปิดแถบ Drawer
            var drawerClosed = false
            animateCloseDrawer {
                drawerClosed = true
            }
            while (!drawerClosed) {
                delay(30)
            }
            delay(200)

            // 7. แมวทิ้งตัวลงมาที่พื้นล่าง
            val fromY = catY
            val dropSteps = 8
            for (s in 1..dropSteps) {
                catY = (fromY * (1f - s / dropSteps.toFloat())).toInt().coerceAtLeast(0)
                updateWidget()
                delay(40)
            }
            catY = 0
            targetY = 0
            triggerSquishEffect(0.3f)
            updateWidget()

            // 8. สปอว์นไอเทมที่แมวเลือก ให้ร่วงลงมาเล่น
            spawnSelectedItem(chosenItem, targetLandX = null, targetLandY = null)
        }
    }

    /**
     * เดินแมวไปยังพิกัดที่ต้องการ
     * คืน true ถ้าถึงเป้าหมาย, false ถ้าถูกขัด (state เปลี่ยน/ยกเลิก)
     */
    private suspend fun walkCatTo(
        goalX: Int,
        goalY: Int,
        state: State = State.WALKING_TO_ITEM,
        stepDp: Float = 6f,
        frameDelayMs: Long = 100L
    ): Boolean {
        val (maxX, maxY) = getMaxBounds()
        val gx = goalX.coerceIn(0, maxX)
        val gy = goalY.coerceIn(0, maxY)
        val step = (stepDp * resources.displayMetrics.density).toInt().coerceAtLeast(1)

        if (gx != catX) isFacingLeft = gx < catX
        setCatState(state)
        while (catState == state) {
            if (isStartingWalk) {
                updateWidget()
                delay(frameDelayMs)
                continue
            }
            val dx = gx - catX
            val dy = gy - catY
            if (kotlin.math.abs(dx) <= step && kotlin.math.abs(dy) <= step) {
                catX = gx
                catY = gy
                updateWidget()
                return true
            }
            if (kotlin.math.abs(dx) > step) isFacingLeft = dx < 0
            catX = (catX + dx.coerceIn(-step, step)).coerceIn(0, maxX)
            catY = (catY + dy.coerceIn(-step, step)).coerceIn(0, maxY)
            updateWidget()
            delay(frameDelayMs)
        }
        return false
    }

    private fun dropItemThenAct(item: ItemType, landX: Int, landY: Int) {
        itemFallJob?.cancel()
        itemLanded = false

        if (item == ItemType.BOX) {
            handleBoxItem(landX, landY)
        }

        itemFallJob = scope.launch {
            val density = resources.displayMetrics.density
            val (maxX, maxY) = getItemBounds(itemType = item)

            val safeLandX = landX.coerceIn(0, maxX)
            val safeLandY = landY.coerceIn(0, maxY) // พื้นล่างสุดคือ 0

            // เริ่มตกลงมาจากขอบบนสุดของ Widget (maxY + ระยะส่ง)
            val startY = maxY.toFloat() + (50 * density)

            var currentY = startY
            var velocityY = 0f
            val gravity = 9.5f * density

            itemX = safeLandX
            itemY = currentY.toInt().coerceAtLeast(safeLandY)
            updateWidget()

            // วนลูปให้ไอเทมร่วงลงมาจนถึงพื้น (currentY <= safeLandY)
            while (currentY > safeLandY) {
                velocityY += gravity
                currentY -= velocityY // ลบค่าออกเพื่อให้ตำแหน่ง Y ลดลงมาหาพื้น (0)

                itemX = safeLandX
                itemY = currentY.toInt().coerceAtLeast(safeLandY)

                updateWidget()
                delay(16)
            }

            itemX = safeLandX
            itemY = safeLandY
            itemLanded = true
            updateWidget()

            when (item) {
                ItemType.WAND -> handleWandItem(landX, landY)
                ItemType.BOX -> { /* handleBoxItem เริ่มไปแล้ว */ }
                ItemType.FOOD -> handleFoodItem(landX, landY)
                ItemType.COCKROACH -> handleScaryBugItem(landX, landY, isSnake = false)
                ItemType.SNAKE -> handleScaryBugItem(landX, landY, isSnake = true)
            }
        }
    }

    private fun handleWandItem(x: Int, y: Int) {
        cancelChase()
        actionJob?.cancel()
        actionJob = scope.launch {
            val (maxX, maxY) = getMaxBounds()
            val (itemMaxX, itemMaxY) = getItemBounds(itemType = ItemType.WAND)
            val density = resources.displayMetrics.density

            val willPlay = Random.nextInt(100) < 65

            targetX = x.coerceIn(0, maxX)
            if (targetX < catX) isFacingLeft = true else if (targetX > catX) isFacingLeft = false
            updateWidget()

            if (!willPlay) {
                unityMessage = listOf("เมิน... ⚽", "ไม่อยากเล่นอ่ะ~", "เฉยๆ อะ ⚽", "ไม่เห็นน่าสนใจเลย~").random()
                currentStatusIconRes = null
                setCatState(State.IDLE)
                updateWidget()

                delay(2000)

                activeItem = null
                unityMessage = null
                setCatState(State.IDLE)
                updateWidget()
                return@launch
            }

            unityMessage = listOf("บอลลล! ⚽", "มาเล่นกัน~!", "จะจับให้ได้เลย! ⚽").random()
            playMeowSound()
            updateWidget()
            delay(600)

            val bounceRounds = Random.nextInt(4, 7)
            var currentBallX = x.coerceIn(0, itemMaxX)
            var currentBallY = y.coerceIn(0, itemMaxY)

            // ตั้งท่า JUMPING ครั้งเดียวก่อนเข้าลูป ไม่สลับ state ไปมา
            setCatState(State.JUMPING)

            for (round in 1..bounceRounds) {
                val nextBallX = Random.nextInt(0, maxOf(1, itemMaxX + 1))
                val minLandY = (itemMaxY * 0.5f).toInt()
                val nextBallY = Random.nextInt(minLandY, maxOf(minLandY + 1, itemMaxY + 1))

                val nextCatX = nextBallX.coerceIn(0, maxX)
                val startCatX = catX
                val startCatY = catY

                val startBallX = currentBallX
                val startBallY = currentBallY

                if (nextCatX < catX) isFacingLeft = true else if (nextCatX > catX) isFacingLeft = false
                setCatState(State.JUMPING)

                val steps = 10
                for (step in 1..steps) {
                    val t = step.toFloat() / steps
                    val sinT = kotlin.math.sin(t * Math.PI).toFloat()

                    itemX = (startBallX + (nextBallX - startBallX) * t).toInt()
                    val ballArc = (60 * density * sinT).toInt()

                    itemY = (startBallY + (nextBallY - startBallY) * t - ballArc).toInt()
                        .coerceIn(0, itemMaxY)

                    catX = (startCatX + (nextCatX - startCatX) * t).toInt().coerceIn(0, maxX)
                    val catArc = (40 * density * sinT).toInt()
                    catY = (startCatY * (1f - t) + catArc).toInt().coerceIn(0, maxY)

                    updateWidget()
                    delay(45)
                }

                currentBallX = nextBallX
                currentBallY = nextBallY
                catX = nextCatX
                catY = 0
                itemX = currentBallX
                itemY = currentBallY
                updateWidget()

                playClickSound()
                triggerSquishEffect(0.2f)
                delay(150)
            }

            catX = currentBallX.coerceIn(0, maxX)
            catY = 0
            itemX = currentBallX
            itemY = currentBallY

            setCatState(State.WAND_PLAY)
            showInteractionReaction(R.drawable.ic_status_sparkle)
            unityMessage = listOf("จับได้แล้ว! ⚽✨", "แปะๆๆ 🐾", "ตะครุบโดนแล้ว!").random()
            updateWidget()

            for (i in 1..3) {
                triggerSquishEffect(0.3f)
                playClickSound()
                delay(250)
            }

            // ---------- เตะบอลแบบ projectile ออกนอกจอ ----------
            unityMessage = listOf("เตะขึ้นฟ้าเลย! 🚀⚽", "ยิงงงง! ⚽💥", "ลอยไปนู่นแล้ว!").random()
            playMeowSound()
            triggerSquishEffect(0.3f)
            itemScale = 1.0f

            val widgetBounds = getWidgetBounds()
            val itemSizePx = getItemSizePxForType(ItemType.WAND)

            // เตะออกไปทางฝั่งที่ใกล้ขอบกว่า
            val dir = if (itemX < widgetBounds.widthPx / 2) -1 else 1
            val distToEdge = if (dir > 0) (widgetBounds.widthPx - itemX) + itemSizePx
            else itemX + itemSizePx * 2

            val g = 0.55f * density                                  // px/frame²
            val peakPx = minOf(90 * density, maxOf(itemY - 10 * density, 40 * density))
            var vy = -kotlin.math.sqrt(2f * g * peakPx)              // ขึ้นบน = ค่าลบ
            val framesToReturn = 2f * kotlin.math.abs(vy) / g
            val vx = dir * (distToEdge / (framesToReturn * 0.8f))    // ออกข้างก่อนตกกลับลงมา

            var px = itemX.toFloat()
            var py = itemY.toFloat()
            var frames = 0
            while (frames < 240 &&
                px > -itemSizePx * 2 && px < widgetBounds.widthPx + itemSizePx &&
                py < widgetBounds.heightPx + itemSizePx
            ) {
                vy += g
                px += vx
                py += vy
                itemX = px.toInt()
                itemY = py.toInt()
                updateWidget()
                delay(16)
                frames++
            }

            activeItem = null
            itemScale = 1.0f
            catY = 0

            boredom = (boredom - 60).coerceAtLeast(0)
            energy = (energy - 15).coerceAtLeast(0)
            hunger = (hunger + 15).coerceAtMost(100)
            saveStatsToPrefs()

            unityMessage = listOf("ปลิวไปเลย~ 🌟", "สนุกจัง! ✨", "หายไปไหนแล้วนะ? ⚽").random()
            setCatState(State.IDLE)
            updateWidget()

            delay(1800)
            unityMessage = null
            updateWidget()
        }
    }

    private fun handleBoxItem(x: Int, y: Int) {
        cancelChase()
        actionJob?.cancel()

        actionJob = scope.launch {
            try {
                unityMessage = null
                currentStatusIconRes = null
                randomMessage = null
                val (maxX, maxY) = getMaxBounds()
                val density = resources.displayMetrics.density
                val boxSizePx = getItemSizePxForType(ItemType.BOX)
                val (catWidthPx, _) = getCatSizePxForState(State.WALKING_TO_ITEM)

                // ตำแหน่งกึ่งกลางกล่อง
                val boxCenterX = (x + (boxSizePx / 2) - (catWidthPx / 2)).coerceIn(0, maxX)

                // 1. เดินไปตั้งหลักข้างกล่อง
                val startJumpX = if (catX < boxCenterX) {
                    (boxCenterX - (40 * density).toInt()).coerceAtLeast(0)
                } else {
                    (boxCenterX + (40 * density).toInt()).coerceIn(0, maxX)
                }
                if (!walkCatTo(startJumpX, 0)) return@launch

                // 2. รอกล่องตกถึงพื้น
                while (!itemLanded && activeItem == ItemType.BOX) delay(30)
                if (activeItem != ItemType.BOX) return@launch

                // ==========================================
                // 3. กระโดดเข้ากล่อง (แกน Y ลอยขึ้นแบบโปรเจกไทล์)
                // ==========================================
                setCatState(State.JUMPING)
                val jumpInStartX = catX
                val jumpInTargetX = boxCenterX
                isFacingLeft = jumpInTargetX < jumpInStartX

                // คำนวณความสูงไม่ให้เกิน maxY ของวิดเจ็ต (ใช้ 70% ของความสูงวิดเจ็ต)
                val safeMaxArcY = if (maxY > 0) (maxY * 0.85f).toInt() else (80 * density).toInt()
                val jumpInSteps = 12

                for (s in 1..jumpInSteps) {
                    if (catState != State.JUMPING) return@launch
                    val p = s / jumpInSteps.toFloat()

                    // เคลื่อนที่แกน X เข้าหากล่อง
                    catX = (jumpInStartX + (jumpInTargetX - jumpInStartX) * p).toInt().coerceIn(0, maxX)

                    // คำนวณแกน Y ให้ลอยโค้งพาราโบลา
                    val heightOffset = (safeMaxArcY * kotlin.math.sin(p * Math.PI)).toInt()
                    catY = heightOffset.coerceIn(0, maxOf(1, maxY))

                    updateWidget()
                    delay(35) // ความเร็วเฟรมการลอย
                }

                // แตะพื้นกล่องพอดี
                catX = boxCenterX
                catY = 0
                updateWidget()

                // ==========================================
                // 4. มุดแอบในกล่อง
                // ==========================================
                setCatState(State.BOX_HIDDEN)
                squishScaleX = 0f
                squishScaleY = 0f
                boredom = (boredom - 60).coerceAtLeast(0)
                energy = (energy - 15).coerceAtLeast(0)
                saveStatsToPrefs()
                updateWidget()
                delay(400)
                if (catState != State.BOX_HIDDEN) return@launch

                // กล่องสั่นดุ๊กดิ๊ก
                val shakeBaseX = itemX
                repeat(6) { i ->
                    if (catState != State.BOX_HIDDEN) return@launch
                    itemX = shakeBaseX + if (i % 2 == 0) (5 * density).toInt() else -(5 * density).toInt()
                    updateWidget()
                    delay(75)
                }
                itemX = shakeBaseX
                updateWidget()
                delay(200)
                if (catState != State.BOX_HIDDEN) return@launch

                // ==========================================
                // 5. กระโดดออกจากกล่อง (แกน Y ลอยขึ้นสูง)
                // ==========================================
                squishScaleX = 1f
                squishScaleY = 1f
                setCatState(State.JUMPING)

                val jumpOutStartX = catX
                val jumpOutDistance = (50 * density).toInt()
                val jumpOutTargetX = if (Random.nextBoolean()) {
                    (jumpOutStartX + jumpOutDistance).coerceIn(0, maxX)
                } else {
                    (jumpOutStartX - jumpOutDistance).coerceIn(0, maxX)
                }
                isFacingLeft = jumpOutTargetX < jumpOutStartX

                val jumpOutSteps = 14

                for (s in 1..jumpOutSteps) {
                    val p = s / jumpOutSteps.toFloat()

                    // เคลื่อนที่แกน X เฉียงออกจากกล่อง
                    catX = (jumpOutStartX + (jumpOutTargetX - jumpOutStartX) * p).toInt().coerceIn(0, maxX)

                    // คำนวณแกน Y ลอยสูงขึ้นฟ้า
                    val heightOffset = (safeMaxArcY * kotlin.math.sin(p * Math.PI)).toInt()
                    catY = heightOffset.coerceIn(0, maxOf(1, maxY))

                    // ลบกล่องออกตอนลอยถึงจุดสูงสุด
                    if (s == jumpOutSteps / 2) {
                        activeItem = null
                    }

                    updateWidget()
                    delay(35)
                }

                // แตะพื้นนอกกล่อง
                catX = jumpOutTargetX
                catY = 0
                activeItem = null
                setCatState(State.IDLE)
                triggerSquishEffect(0.3f)
                playMeowSound()
                updateWidget()

                delay(300)

                // 6. หน้า Jumpscare ขึ้นมาโชว์
                isJumpscareActive = true
                updateWidget()
                delay(700)

                isJumpscareActive = false
                updateWidget()

            } finally {
                isJumpscareActive = false
                activeItem = null
                squishScaleX = 1f
                squishScaleY = 1f
                if (catState == State.BOX_EMERGE || catState == State.BOX_HIDDEN) {
                    setCatState(State.IDLE)
                }
                triggerSquishEffect(0.3f)
                updateWidget()
            }
        }
    }

    private fun handleFoodItem(x: Int, y: Int) {
        cancelChase()
        actionJob?.cancel()
        actionJob = scope.launch {
            val (maxX, _) = getMaxBounds()
            val foodSizePx = getItemSizePxForType(ItemType.FOOD)
            val (catWidthPx, _) = getCatSizePxForState(State.WALKING_TO_ITEM)

            val goalX = (x + foodSizePx / 2 - catWidthPx / 2).coerceIn(0, maxX)
            val goalY = 0 // เดินไปกินที่พื้นล่างสุด

            if (!walkCatTo(goalX, goalY)) return@launch

            activeItem = null
            setCatState(State.EATING)
            hunger = (hunger - 60).coerceAtLeast(0)
            energy = (energy + 15).coerceAtMost(100)
            boredom = (boredom - 10).coerceAtLeast(0)
            saveStatsToPrefs()
            playEatSound()
            unityMessage = "อร่อยจัง!"
            currentStatusIconRes = R.drawable.ic_status_food
            updateWidget()
            delay(4000)
            if (catState == State.EATING) {
                setCatState(State.IDLE)
                unityMessage = null
                currentStatusIconRes = null
                updateWidget()
            }
        }
    }

    private fun handleScaryBugItem(x: Int, y: Int, isSnake: Boolean) {
        cancelChase()
        actionJob?.cancel()
        actionJob = scope.launch {
            setCatState(State.ANGRY)
            unityMessage = if (isSnake) "กรี๊ด! งูมา! ช่วยด้วยยย!" else "ยี้! แมลงสาบ น่ากลัวโว้ย!"
            currentStatusIconRes = R.drawable.ic_status_angry

            triggerSquishEffect(0.4f)
            updateWidget()
            delay(800)

            activeItem = null
            boredom = (boredom - 20).coerceAtLeast(0)
            saveStatsToPrefs()

            startJumpAround(
                jumps = Random.nextInt(8, 13),
                message = if (isSnake) "ไปให้พ้นนน!" else "อย๊ากกก!",
                iconRes = R.drawable.ic_status_angry,
                fleeFromX = x
            )
        }
    }

    private fun cancelChase() {

        chaseJob?.cancel()
        chaseJob = null
        chaseActive = false
        chaseCaught = false
    }

    private fun startChaseEvent() {
        cancelChase()
        actionJob?.cancel()
        jumpJob?.cancel()
        itemFallJob?.cancel()
        activeItem = null
        chaseActive = true
        chaseStarted = false
        chaseHits = 0
        lastChaseTime = System.currentTimeMillis()

        chaseJob = scope.launch {
            setCatState(State.CHASE_INVITE)
            unityMessage = chaseInviteMsgs.random()
            currentStatusIconRes = R.drawable.ic_status_heart

            triggerSquishEffect(0.2f)
            updateWidget()

            var waited = 0L
            val maxWaitMs = 3000L
            while (chaseActive && catState == State.CHASE_INVITE && !chaseStarted && waited < maxWaitMs) {
                delay(50)
                waited += 50
            }
            if (!chaseActive) return@launch

            for (round in 1..CHASE_ROUNDS) {
                if (!chaseActive) return@launch
                chaseCaught = false

                setCatState(State.CHASE_HOP)
                unityMessage = "รอบ $round/$CHASE_ROUNDS จับเราให้ได้สิ~"
                currentStatusIconRes = R.drawable.ic_status_heart
                updateWidget()

                var hops = 0
                while (hops < 3 && !chaseCaught) {
                    chaseHopOnceFast()
                    hops++
                }
                if (!chaseActive) return@launch

                if (chaseCaught) {
                    chaseHits++
                    unityMessage = chaseFleeMsgs.random()
                    currentStatusIconRes = R.drawable.ic_status_heart

                    triggerSquishEffect(0.3f)
                    updateWidget()
                    fleeHopFast()
                } else {
                    unityMessage = chaseTauntMsgs.random()
                    currentStatusIconRes = R.drawable.ic_status_angry

                    triggerSquishEffect(0.2f)
                    updateWidget()
                    delay(600)
                }
            }
            if (!chaseActive) return@launch

            returnToBottomFloor()

            setCatState(State.CHASE_INVITE)
            unityMessage = when {
                chaseHits >= 4 -> chaseTiredMsgs[0]
                chaseHits >= 1 -> chaseTiredMsgs[1]
                else -> chaseTiredMsgs[2]
            }
            currentStatusIconRes = R.drawable.ic_status_zzz
            energy = (energy - 30).coerceAtLeast(0)
            boredom = (boredom - 60).coerceAtLeast(0)
            hunger = (hunger + 15).coerceAtMost(100)
            saveStatsToPrefs()
            updateWidget()
            delay(3000)
            if (!chaseActive) return@launch

            chaseActive = false
            val afterState = if (Random.nextBoolean()) State.NAP else State.ROLLING
            val afterInfo = statusConfigMap[afterState]

            setCatState(afterState)
            unityMessage = afterInfo?.message
            currentStatusIconRes = afterInfo?.iconRes
            when (afterState) {
                State.NAP -> {
                    energy = (energy + 60).coerceAtMost(100)
                    hunger = (hunger + 10).coerceAtMost(100)
                }

                State.ROLLING -> {
                    boredom = (boredom - 60).coerceAtLeast(0)
                    energy = (energy - 15).coerceAtLeast(0)
                    hunger = (hunger + 15).coerceAtMost(100)
                }

                else -> {}
            }
            saveStatsToPrefs()
            updateWidget()

            val afterDuration = if (afterState == State.NAP) 6000L else 4000L
            delay(afterDuration)
            if (catState == afterState) {
                setCatState(State.IDLE)
                unityMessage = null
                currentStatusIconRes = null
                updateWidget()
            }
        }
    }

    private fun handleChaseCatTap() {
        when (catState) {
            State.CHASE_INVITE -> chaseStarted = true
            State.CHASE_HOP -> chaseCaught = true
            else -> {}
        }
    }

    private suspend fun chaseHopOnceFast() {
        val (maxX, maxY) = getMaxBounds()
        val jumpArcHeight = (30 * resources.displayMetrics.density).toInt()
        val startX = catX
        val startY = catY

        val landX = if (maxX > 0) Random.nextInt(0, maxX + 1) else 0
        val landY = if (maxY > 0) Random.nextInt(0, maxY + 1) else 0

        if (landX < startX) isFacingLeft = true else if (landX > startX) isFacingLeft = false

        val steps = 8
        for (s in 1..steps) {
            if (chaseCaught) return
            val p = s / steps.toFloat()

            catX = (startX + (landX - startX) * p).toInt().coerceIn(0, maxX)
            val linearY = (startY + (landY - startY) * p).toInt()
            val arcY = (jumpArcHeight * kotlin.math.sin(p * Math.PI)).toInt()
            catY = (linearY + arcY).coerceIn(0, maxY)

            updateWidget()
            delay(35)
        }

        catX = landX.coerceIn(0, maxX)
        catY = landY.coerceIn(0, maxY)
        triggerSquishEffect(0.15f)
        updateWidget()
        delay(120)
    }

    private suspend fun fleeHopFast() {
        val (maxX, maxY) = getMaxBounds()
        val jumpArcHeight = (40 * resources.displayMetrics.density).toInt()
        val startX = catX
        val startY = catY

        val landX = if (maxX > 0) Random.nextInt(0, maxX + 1) else 0
        val landY = if (maxY > 0) Random.nextInt(0, maxY + 1) else 0

        if (landX < startX) isFacingLeft = true else if (landX > startX) isFacingLeft = false

        val steps = 6
        for (s in 1..steps) {
            if (!chaseActive) return
            val p = s / steps.toFloat()
            catX = (startX + (landX - startX) * p).toInt().coerceIn(0, maxX)

            val linearY = (startY + (landY - startY) * p).toInt()
            val arcY = (jumpArcHeight * kotlin.math.sin(p * Math.PI)).toInt()
            catY = (linearY + arcY).coerceIn(0, maxY)

            updateWidget()
            delay(30)
        }
        catX = landX.coerceIn(0, maxX)
        catY = landY.coerceIn(0, maxY)
        triggerSquishEffect(0.2f)
        updateWidget()
        delay(200)
    }

    private suspend fun returnToBottomFloor() {
        val (maxX, maxY) = getMaxBounds()
        val targetBottomY = 0

        if (catY != targetBottomY) {
            val startY = catY
            val startX = catX
            val landX = maxX / 2
            val steps = 10

            if (landX < startX) isFacingLeft = true else if (landX > startX) isFacingLeft = false

            for (s in 1..steps) {
                if (!chaseActive) return
                val p = s / steps.toFloat()
                catX = (startX + (landX - startX) * p).toInt().coerceIn(0, maxX)
                catY = (startY + (targetBottomY - startY) * p).toInt().coerceIn(0, maxY)
                updateWidget()
                delay(35)
            }
            catX = landX.coerceIn(0, maxX)
            catY = targetBottomY
            updateWidget()
        }
    }

    private fun handleChaseMissTap() {
        if (catState != State.CHASE_HOP) return
        unityMessage = chaseTauntMsgs.random()
        currentStatusIconRes = R.drawable.ic_status_angry
        updateWidget()
    }

    private fun ensureAnimationLoopRunning() {
        if (serviceJob?.isActive == true) {
            Log.d(TAG, "ensureAnimationLoopRunning: loop already active, skip")
            return
        }
        Log.d(TAG, "ensureAnimationLoopRunning: loop NOT active, restarting...")
        startAnimationLoop()
    }

    private fun startAnimationLoop() {
        serviceJob?.cancel()
        serviceJob = scope.launch {
            while (true) {
                updateEmotion()

                // ดักไว้: ถ้ากำลังกระโดด, กลิ้ง, เดินไปหาไอเทม หรือทำกิจกรรมพิเศษ
                // ให้ข้ามการขยับของลูปหลัก (แต่ละกิจกรรมขยับแมวเองอยู่แล้ว)
                val isBusyState = catState in listOf(
                    State.JUMPING, State.ROLLING, State.CHASE_INVITE, State.CHASE_HOP,
                    State.CAT_CHOOSE_TOY, State.WALKING_TO_ITEM, State.EATING, State.BOX_HIDDEN, State.BOX_EMERGE
                )

                if (!isBusyState) {
                    if (catState == State.IDLE) {
                        // Keep idle frames playing during the existing pause before choosing an action.
                        repeat(5) {
                            delay(200)
                            updateWidget()
                        }
                        val rand = Random.nextInt(100)
                        if (rand < 50) {
                            setCatState(State.WALKING)
                            val (maxX, maxY) = getMaxBounds()
                            targetX = if (maxX > 0) Random.nextInt(0, maxX + 1) else 0
                            targetY = 0
                            if (targetX < catX) isFacingLeft = true else if (targetX > catX) isFacingLeft = false
                        } else {
                            val chosenState = pickActionForEmotion(currentEmotion)
                            if (chosenState == State.CHASE_INVITE) {
                                startChaseEvent()
                            } else if (chosenState == State.CAT_CHOOSE_TOY) {
                                startCatChooseToySequence()
                            } else if (chosenState == State.JUMPING) {
                                startJumpAround()
                            } else if (chosenState == State.ROLLING) {
                                startRolling()
                            } else if (chosenState != State.IDLE && chosenState != State.WALKING) {
                                val statusInfo = statusConfigMap[chosenState]
                                performStateAction(
                                    chosenState,
                                    statusInfo?.targetDuration ?: 4000L,
                                    statusInfo?.message,
                                    statusInfo?.iconRes
                                )
                            }
                        }
                    } else if ((catState == State.WALKING || catState == State.WALKING_TO_CENTER) && !isStartingWalk) {
                        val (maxX, maxY) = getMaxBounds()
                        targetX = targetX.coerceIn(0, maxX)
                        targetY = targetY.coerceIn(0, maxY)

                        if (targetX < catX) {
                            isFacingLeft = true
                        } else if (targetX > catX) {
                            isFacingLeft = false
                        }

                        val step = 8
                        if (catX < targetX) catX += step else if (catX > targetX) catX -= step
                        if (catY < targetY) catY += step else if (catY > targetY) catY -= step
                        catX = catX.coerceIn(0, maxX)
                        catY = catY.coerceIn(0, maxY)

                        if (kotlin.math.abs(catX - targetX) < step && kotlin.math.abs(catY - targetY) < step) {
                            if (catState == State.WALKING) setCatState(State.IDLE)
                        }
                    }
                }

                tickStats()
                updateWidget()
                delay(200) // ลดความถี่อัปเดตลง (เดิม 120ms)
            }
        }
    }

    private fun tickStats() {
        if (isAppOpen) return
        statTickCounter++
        if (statTickCounter % 10 != 0) return

        when (catState) {
            State.IDLE -> {
                hunger = (hunger + 1).coerceAtMost(100)
                boredom = (boredom + 1).coerceAtMost(100)
                energy = (energy - 1).coerceAtLeast(0)
            }

            State.WALKING, State.WALKING_TO_CENTER, State.WALKING_TO_ITEM -> {
                hunger = (hunger + 1).coerceAtMost(100)
                energy = (energy - 2).coerceAtLeast(0)
            }

            State.PLAYING, State.WAND_PLAY, State.BOX_HIDDEN, State.BOX_EMERGE, State.ROLLING -> {
                boredom = (boredom - 2).coerceAtLeast(0)
                energy = (energy - 2).coerceAtLeast(0)
                hunger = (hunger + 2).coerceAtMost(100)
            }

            State.EATING -> {
                hunger = (hunger - 5).coerceAtLeast(0)
                energy = (energy + 2).coerceAtMost(100)
            }

            State.NAP -> {
                energy = (energy + 5).coerceAtMost(100)
                hunger = (hunger + 1).coerceAtMost(100)
            }

            else -> {
                energy = (energy - 1).coerceAtLeast(0)
                boredom = (boredom - 1).coerceAtLeast(0)
            }
        }

        saveStatsToPrefs()
    }

    /** กัน cache โตไม่หยุดจากขนาดที่เปลี่ยนตาม squish */
    private fun trimBitmapCacheIfNeeded() {
        if (bitmapCache.size > BITMAP_CACHE_MAX) {
            bitmapCache.clear()
        }
    }

    /**
     * โหลดรูปจาก assets แล้ววาดลงกล่องขนาด reqWidthPx x reqHeightPx
     * รักษาอัตราส่วนของรูป วางชิดล่างกึ่งกลาง ทำให้ตัวแมวไม่ถูกบีบ/ยืดเมื่อแต่ละท่ามีสัดส่วนต่างกัน
     */
    private fun getBitmapFromAsset(
        filePath: String,
        reqWidthPx: Int,
        reqHeightPx: Int,
        flipHorizontal: Boolean = false
    ): android.graphics.Bitmap? {
        val cacheKey = "${filePath}_${reqWidthPx}x${reqHeightPx}_flip_$flipHorizontal"
        bitmapCache[cacheKey]?.let { return it }

        return try {
            val boundsOptions =
                android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            assets.open(filePath)
                .use { android.graphics.BitmapFactory.decodeStream(it, null, boundsOptions) }

            val decodeOptions = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(boundsOptions, reqWidthPx, reqHeightPx)
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            }
            val sampledBitmap = assets.open(filePath)
                .use { android.graphics.BitmapFactory.decodeStream(it, null, decodeOptions) }
                ?: return null

            val scale = minOf(
                reqWidthPx / sampledBitmap.width.toFloat(),
                reqHeightPx / sampledBitmap.height.toFloat()
            )
            val dw = (sampledBitmap.width * scale).toInt().coerceAtLeast(1)
            val dh = (sampledBitmap.height * scale).toInt().coerceAtLeast(1)

            val boxBitmap = android.graphics.Bitmap.createBitmap(
                reqWidthPx, reqHeightPx, android.graphics.Bitmap.Config.ARGB_8888
            )
            val left = (reqWidthPx - dw) / 2
            android.graphics.Canvas(boxBitmap).drawBitmap(
                sampledBitmap,
                null,
                android.graphics.Rect(left, reqHeightPx - dh, left + dw, reqHeightPx),
                android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
            )
            sampledBitmap.recycle()

            val finalBitmap = if (flipHorizontal) {
                val matrix = android.graphics.Matrix().apply { setScale(-1f, 1f) }
                val flipped = android.graphics.Bitmap.createBitmap(
                    boxBitmap, 0, 0, boxBitmap.width, boxBitmap.height, matrix, true
                )
                if (flipped !== boxBitmap) boxBitmap.recycle()
                flipped
            } else {
                boxBitmap
            }

            trimBitmapCacheIfNeeded()
            bitmapCache[cacheKey] = finalBitmap
            finalBitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** bitmap ของไอเทม ขนาดตายตัวตามที่คำนวณ (cache ตามขนาด ไม่สร้างใหม่ทุกเฟรม) */
    private fun getItemBitmap(resId: Int, sizePx: Int): android.graphics.Bitmap? {
        val key = "item_${resId}_$sizePx"
        bitmapCache[key]?.let { return it }

        return try {
            val drawable = androidx.core.content.ContextCompat.getDrawable(this, resId) ?: return null
            val bmp = android.graphics.Bitmap.createBitmap(
                sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(bmp)
            drawable.setBounds(0, 0, sizePx, sizePx)
            drawable.draw(canvas)
            trimBitmapCacheIfNeeded()
            bitmapCache[key] = bmp
            bmp
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun calculateInSampleSize(
        options: android.graphics.BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun getItemSizeDpForType(type: ItemType?): Int {
        return when (type) {
            ItemType.WAND -> 100   // ขนาดบอล (dp) ปรับตรงนี้
            ItemType.BOX -> 150
            ItemType.FOOD -> 100
            ItemType.COCKROACH -> 100
            ItemType.SNAKE -> 100
            else -> 100
        }
    }

    private fun getItemSizePxForType(type: ItemType?): Int {
        val density = resources.displayMetrics.density
        return (getItemSizeDpForType(type) * density).toInt().coerceAtLeast(1)
    }

    private fun getBaseCatSizeDp(widgetId: Int? = null): Int {
        val appWidgetManager = AppWidgetManager.getInstance(this)
        val componentName = ComponentName(this, CatWidgetProvider::class.java)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)

        var currentWidthDp = 150
        val resolvedWidgetId = widgetId ?: appWidgetIds.firstOrNull()
        if (resolvedWidgetId != null) {
            val options = appWidgetManager.getAppWidgetOptions(resolvedWidgetId)
            val isPortrait =
                resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
            val w =
                options.getInt(if (isPortrait) AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
            if (w > 0) currentWidthDp = w
        }

        // Scale proportionally instead of jumping at 200dp and 350dp.
        // Keep the established minimum and maximum for readability and clearance.
        return (currentWidthDp * 0.32f).roundToInt().coerceIn(60, 130)
    }

    private fun getCatSizeDpForState(state: State, widgetId: Int? = null): Pair<Int, Int> {
        val baseCatSizeDp = getBaseCatSizeDp(widgetId)
        return when (state) {
            State.IDLE -> Pair(baseCatSizeDp - 2, baseCatSizeDp - 2)
            State.NAP -> Pair((baseCatSizeDp * 1f).toInt(), (baseCatSizeDp * 1.4f).toInt())

            State.WALKING, State.WALKING_TO_CENTER, State.WALKING_TO_ITEM,
            State.SNUGGLE, State.HUNGRY, State.ITCHY,
            State.ANGRY -> Pair(baseCatSizeDp + 8, baseCatSizeDp + 8)
            State.TRAIN_IDLE, State.WALK_COUNTING -> Pair(baseCatSizeDp, baseCatSizeDp)

            State.EATING -> Pair(baseCatSizeDp, baseCatSizeDp)
            State.STRETCH -> Pair(baseCatSizeDp + 8, baseCatSizeDp)
            State.PLAYING -> Pair(baseCatSizeDp + 5, baseCatSizeDp + 5)
            // ใกล้เคียง JUMPING เพื่อไม่ให้ขนาดกระชากตอนสลับท่าระหว่างไล่บอล
            State.WAND_PLAY -> Pair(baseCatSizeDp + 10, baseCatSizeDp + 10)
            State.BOX_HIDDEN, State.BOX_EMERGE -> Pair(baseCatSizeDp + 20, baseCatSizeDp + 20)
            State.GROOMING -> Pair(baseCatSizeDp + 4, baseCatSizeDp + 4)
            State.JUMPING -> Pair(baseCatSizeDp + 10, baseCatSizeDp + 10)
            State.ROLLING -> Pair(baseCatSizeDp + 10, baseCatSizeDp + 4)
            State.CHASE_INVITE -> Pair(baseCatSizeDp + 10, baseCatSizeDp)
            State.CHASE_HOP -> Pair(baseCatSizeDp + 10, baseCatSizeDp + 10)
            State.CAT_CHOOSE_TOY -> Pair(baseCatSizeDp + 5, baseCatSizeDp + 5)
        }
    }

    private fun getCatSizePxForState(state: State, widgetId: Int? = null): Pair<Int, Int> {
        val density = resources.displayMetrics.density
        val (widthDp, heightDp) = getCatSizeDpForState(state, widgetId)
        // ปัดเป็นทีละ 4px กัน bitmap ใหม่ทุกเฟรมจาก squish
        val widthPx = ((widthDp * density * squishScaleX).toInt() / 4 * 4).coerceAtLeast(4)
        val heightPx = ((heightDp * density * squishScaleY).toInt() / 4 * 4).coerceAtLeast(4)
        return Pair(widthPx, heightPx)
    }

    private val SAFETY_SCALE = 1.1f

    private fun getStableCatSizePx(widgetId: Int? = null): Pair<Int, Int> {
        val density = resources.displayMetrics.density
        val (curW, curH) = getCatSizeDpForState(catState, widgetId)
        val (idleW, idleH) = getCatSizeDpForState(State.IDLE, widgetId)
        val (refW, refH) = getCatSizeDpForState(State.JUMPING, widgetId)

        val maxW = maxOf(curW, refW, idleW)
        val maxH = maxOf(curH, refH, idleH)

        val widthPx = (maxW * SAFETY_SCALE * density).toInt().coerceAtLeast(1)
        val heightPx = (maxH * SAFETY_SCALE * density).toInt().coerceAtLeast(1)
        return widthPx to heightPx
    }

    private fun triggerSquishEffect(intensity: Float = 0.15f) {
        squishJob?.cancel()
        squishJob = scope.launch {
            val keyframes = listOf(
                (1f + 0.08f * intensity) to (1f - 0.06f * intensity),
                (1f - 0.06f * intensity) to (1f + 0.08f * intensity),
                (1f + 0.03f * intensity) to (1f - 0.02f * intensity),
                1f to 1f
            )
            for ((sx, sy) in keyframes) {
                squishScaleX = sx
                squishScaleY = sy
                updateWidget()
                delay(50)
            }
            squishScaleX = 1f
            squishScaleY = 1f
        }
    }

    private fun updateWidget() {
        val appWidgetManager = AppWidgetManager.getInstance(this)
        val componentName = ComponentName(this, CatWidgetProvider::class.java)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
        // เฟรมเปลี่ยนตามเวลา ไม่ใช่ตามจำนวนครั้งที่เรียก
        var currentFrames = getFramesForState()
        val now = System.currentTimeMillis()
        if (now - lastFrameAdvanceTime >= FRAME_INTERVAL_MS) {
            if (isStartingWalk && currentFrameIndex == idleToWalkFrames.lastIndex) {
                isStartingWalk = false
                currentFrameIndex = 0
                currentFrames = getFramesForState()
            } else {
                currentFrameIndex = (currentFrameIndex + 1) % currentFrames.size
            }
            lastFrameAdvanceTime = now
        }
        val frameIdx = currentFrameIndex % currentFrames.size
        Log.d(TAG, "updateWidget: ids=${appWidgetIds.toList()}, state=$catState, frame=${currentFrames[frameIdx]}, facingLeft=$isFacingLeft, serviceJob.isActive=${serviceJob?.isActive}")

        for (widgetId in appWidgetIds) {
            val views = RemoteViews(packageName, R.layout.cat_widget)
            CatWidgetProvider.bindThemePicker(this, views, widgetId)
            views.setImageViewResource(R.id.catiBackground, WidgetTheme.backgroundRes(this))  // <-- เพิ่มบรรทัดนี้
            val (catWidthPx, catHeightPx) = getCatSizePxForState(catState, widgetId)

            val bitmap = getBitmapFromAsset(
                currentFrames[frameIdx],
                catWidthPx,
                catHeightPx,
                flipHorizontal = isFacingLeft
            )
            if (bitmap != null) {
                views.setImageViewBitmap(R.id.catImage, bitmap)
                // Ignore transparent asset padding when placing the badge above the cat.
                val bounds = visibleCatBounds(
                    bitmap, "${currentFrames[frameIdx]}_${bitmap.width}_${bitmap.height}_$isFacingLeft"
                )
                val anchor = reactionAnchor(currentFrames[frameIdx], bounds)
                val density = resources.displayMetrics.density
                views.setViewLayoutMargin(
                    R.id.statusBubbleContainer, RemoteViews.MARGIN_START,
                    (anchor.x - 12f * density).coerceAtLeast(0f),
                    TypedValue.COMPLEX_UNIT_PX
                )
                views.setViewLayoutMargin(
                    R.id.statusBubbleContainer, RemoteViews.MARGIN_BOTTOM,
                    bitmap.height - anchor.y + 3f * density,
                    TypedValue.COMPLEX_UNIT_PX
                )
            }

            val (maxX, maxY) = getMaxBounds(widgetId)
            catX = catX.coerceIn(0, maxX)
            catY = catY.coerceIn(0, maxY)

            views.setViewLayoutMargin(
                R.id.catContainer,
                RemoteViews.MARGIN_START,
                catX.toFloat(),
                TypedValue.COMPLEX_UNIT_PX
            )
            views.setViewLayoutMargin(
                R.id.catContainer,
                RemoteViews.MARGIN_BOTTOM,
                catY.toFloat() + when (catState) {
                    // Airborne and box animations have their own placement choreography.
                    State.JUMPING, State.CHASE_HOP, State.BOX_HIDDEN, State.BOX_EMERGE -> 0f
                    else -> CAT_FLOOR_GAP_DP * resources.displayMetrics.density -
                        catFloorPadding(currentFrames, catWidthPx, catHeightPx)
                },
                TypedValue.COMPLEX_UNIT_PX
            )

            val isBoxThere = (catState == State.BOX_HIDDEN || catState == State.BOX_EMERGE)
            val configInfo = statusConfigMap[catState]
            val iconRes = if (isBoxThere) null else interactionReactionIconRes ?: currentStatusIconRes ?: configInfo?.iconRes
            val displayMessage: String? = null

            if (iconRes != null) {
                views.setImageViewResource(R.id.statusIcon, iconRes)
                views.setViewVisibility(R.id.statusIcon, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.statusIcon, View.GONE)
            }

            if (!displayMessage.isNullOrEmpty()) {
                views.setTextViewText(R.id.chatBubble, displayMessage)
                views.setViewVisibility(R.id.chatBubble, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.chatBubble, View.GONE)
            }

            if (iconRes != null || !displayMessage.isNullOrEmpty()) {
                views.setViewVisibility(R.id.statusBubbleContainer, View.VISIBLE)
                val statusIntent = if (interactionReactionIconRes == null &&
                    configInfo?.isClickable == true && configInfo.targetState != null
                ) {
                    makePendingIntent(widgetId, CatWidgetProvider.ACTION_TAP_STATUS)
                } else {
                    null
                }
                views.setOnClickPendingIntent(R.id.statusBubbleContainer, statusIntent)
            } else {
                views.setViewVisibility(R.id.statusBubbleContainer, View.GONE)
            }

            if (isJumpscareActive) {
                views.setImageViewResource(R.id.imgJumpscare, R.drawable.cat_jumpscare_face)
                views.setViewVisibility(R.id.imgJumpscare, View.VISIBLE)
                views.setViewVisibility(R.id.statusBubbleContainer, View.GONE)
                views.setViewVisibility(R.id.itemImage, View.GONE)
            } else {
                views.setViewVisibility(R.id.imgJumpscare, View.GONE)
            }

            views.setImageViewResource(
                R.id.btnMute,
                if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up
            )
            views.setOnClickPendingIntent(
                R.id.btnMute,
                makePendingIntent(widgetId, CatWidgetProvider.ACTION_TOGGLE_MUTE)
            )
            views.setOnClickPendingIntent(
                R.id.catImage,
                makePendingIntent(widgetId, CatWidgetProvider.ACTION_TAP_CAT)
            )
            views.setOnClickPendingIntent(
                R.id.catiBackground,
                makePendingIntent(widgetId, CatWidgetProvider.ACTION_TAP_WIDGET)
            )

            // ---------- Item Selection Drawer (สไลด์เลื่อนเข้า-ออกด้วย LayoutMargin) ----------
            try {
                val isPanelActive = isDrawerOpen || drawerProgress > 0f
                if (!isPanelActive) {
                    views.setViewVisibility(R.id.drawerTabContainer, View.VISIBLE)
                    views.setViewVisibility(R.id.drawerPanelContainer, View.GONE)
                    views.setOnClickPendingIntent(
                        R.id.drawerTabContainer,
                        makePendingIntent(widgetId, CatWidgetProvider.ACTION_TOGGLE_ITEM_DRAWER)
                    )
                } else {
                    views.setViewVisibility(R.id.drawerPanelContainer, View.VISIBLE)

                    val density = resources.displayMetrics.density
                    val panelFullWidthPx = drawerPanelWidthPx
                    val restMarginPx = resources.getDimensionPixelSize(R.dimen.drawer_edge_inset)

                    // progress 0 = ซ่อนอยู่นอกจอขวา, 1 = เปิดเต็ม
                    val endMarginPx = ((drawerProgress - 1.0f) * (panelFullWidthPx + restMarginPx) + restMarginPx).toInt()

                    views.setViewLayoutMargin(
                        R.id.drawerPanelContainer,
                        RemoteViews.MARGIN_END,
                        endMarginPx.toFloat(),
                        TypedValue.COMPLEX_UNIT_PX
                    )

                    // แตะพื้นหลังแถบเพื่อปิด
                    views.setOnClickPendingIntent(
                        R.id.drawerBackdrop,
                        makePendingIntent(widgetId, CatWidgetProvider.ACTION_CLOSE_ITEM_DRAWER)
                    )

                    if (drawerProgress >= 1.0f) {
                        views.setViewVisibility(R.id.drawerTabContainer, View.GONE)
                        views.setViewVisibility(R.id.drawerContentContainer, View.VISIBLE)

                        views.setImageViewResource(R.id.itemIconWand, R.drawable.yarn)
                        views.setOnClickPendingIntent(
                            R.id.btnItemWand,
                            makePendingIntent(widgetId, CatWidgetProvider.ACTION_SELECT_WAND)
                        )

                        views.setImageViewResource(R.id.itemIconBox, R.drawable.cartonbox)
                        views.setOnClickPendingIntent(
                            R.id.btnItemBox,
                            makePendingIntent(widgetId, CatWidgetProvider.ACTION_SELECT_BOX)
                        )

                        views.setImageViewResource(R.id.itemIconFood, R.drawable.bowl)
                        views.setOnClickPendingIntent(
                            R.id.btnItemFood,
                            makePendingIntent(widgetId, CatWidgetProvider.ACTION_SELECT_FOOD)
                        )

                        views.setImageViewResource(R.id.itemIconCockroach, R.drawable.cockroach)
                        views.setOnClickPendingIntent(
                            R.id.btnItemCockroach,
                            makePendingIntent(widgetId, CatWidgetProvider.ACTION_SELECT_COCKROACH)
                        )

                        views.setImageViewResource(R.id.itemIconSnake, R.drawable.snake)
                        views.setOnClickPendingIntent(
                            R.id.btnItemSnake,
                            makePendingIntent(widgetId, CatWidgetProvider.ACTION_SELECT_SNAKE)
                        )
                    } else {
                        views.setViewVisibility(R.id.drawerTabContainer, View.VISIBLE)
                        views.setViewVisibility(R.id.drawerContentContainer, View.GONE)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // ---------- Item overlay ----------
            try {
                if (activeItem != null && !isJumpscareActive) {
                    val itemIconRes = when (activeItem) {
                        ItemType.WAND -> R.drawable.new_yarn
                        ItemType.BOX -> R.drawable.paper_box
                        ItemType.FOOD -> R.drawable.new_cartoon_bowl
                        ItemType.COCKROACH -> R.drawable.new_cockaroch
                        ItemType.SNAKE -> R.drawable.new_snake
                        else -> null
                    }

                    if (itemIconRes != null) {
                        val sizePx = (getItemSizePxForType(activeItem) * itemScale).toInt().coerceAtLeast(1)
                        val itemBitmap = getItemBitmap(itemIconRes, sizePx)

                        if (itemBitmap != null) {
                            views.setImageViewBitmap(R.id.itemImage, itemBitmap)
                        } else {
                            views.setImageViewResource(R.id.itemImage, itemIconRes)
                        }

                        views.setViewPadding(R.id.itemImage, 0, 0, 0, 0)
                        views.setViewLayoutMargin(
                            R.id.itemImage,
                            RemoteViews.MARGIN_START,
                            itemX.toFloat(),
                            TypedValue.COMPLEX_UNIT_PX
                        )
                        views.setViewLayoutMargin(
                            R.id.itemImage,
                            RemoteViews.MARGIN_BOTTOM,
                            itemY.toFloat(),
                            TypedValue.COMPLEX_UNIT_PX
                        )
                        views.setViewVisibility(R.id.itemImage, View.VISIBLE)
                    } else {
                        views.setViewVisibility(R.id.itemImage, View.GONE)
                    }
                } else {
                    views.setViewVisibility(R.id.itemImage, View.GONE)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }

    private fun makePendingIntent(widgetId: Int, action: String): PendingIntent {
        val intent = Intent(this, CatWidgetProvider::class.java).apply {
            this.action = action
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        }
        val requestCode = (widgetId.toString() + "_" + action).hashCode()
        return PendingIntent.getBroadcast(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private data class WidgetBounds(val widthPx: Int, val heightPx: Int)

    private fun getWidgetBounds(widgetId: Int? = null): WidgetBounds {
        val appWidgetManager = AppWidgetManager.getInstance(this)
        val resolvedWidgetId = widgetId ?: run {
            val componentName = ComponentName(this, CatWidgetProvider::class.java)
            appWidgetManager.getAppWidgetIds(componentName).firstOrNull()
        }

        var widthDp = 150
        var heightDp = 150
        if (resolvedWidgetId != null && resolvedWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            val options = appWidgetManager.getAppWidgetOptions(resolvedWidgetId)
            val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
            val maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)
            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
            val maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)

            widthDp = when {
                minWidth > 0 -> minWidth
                maxWidth > 0 -> maxWidth
                else -> widthDp
            }
            heightDp = when {
                minHeight > 0 -> minHeight
                maxHeight > 0 -> maxHeight
                else -> heightDp
            }
        }

        val density = resources.displayMetrics.density
        return WidgetBounds(
            widthPx = (widthDp * density).toInt().coerceAtLeast(1),
            heightPx = (heightDp * density).toInt().coerceAtLeast(1)
        )
    }

    private fun getMaxBounds(widgetId: Int? = null): Pair<Int, Int> {
        val bounds = getWidgetBounds(widgetId)
        val (catWidthPx, catHeightPx) = getStableCatSizePx(widgetId)
        val density = resources.displayMetrics.density

        val systemInsetXPx = (16 * density).toInt()
        val marginRightPx = (16 * density).toInt()
        val marginYPx = (8 * density).toInt()

        val usableWidthPx = bounds.widthPx - systemInsetXPx

        val maxX = (usableWidthPx - catWidthPx - marginRightPx).coerceAtLeast(0)

        // Y นับจากด้านล่าง
        val maxY = (bounds.heightPx - catHeightPx - marginYPx).coerceAtLeast(0)

        return maxX to maxY
    }

    private fun getDrawerSlotCatY(slotIndex: Int): Int {
        val bounds = getWidgetBounds()
        val panelHeight = resources.getDimension(R.dimen.drawer_panel_height)
            .coerceAtMost(bounds.heightPx.toFloat())
        val padding = resources.getDimension(R.dimen.drawer_vertical_padding)
        val slotH = (panelHeight - 2 * padding) / itemTypes.size
        val centerFromTop = (bounds.heightPx - panelHeight) / 2f + padding +
            (slotIndex + 0.5f) * slotH
        val (_, catH) = getStableCatSizePx()
        val (_, maxY) = getMaxBounds()
        // พิกัด Y ใน Widget นับจากด้านล่างขึ้นบน
        return (bounds.heightPx - centerFromTop - catH / 2f).toInt().coerceIn(0, maxY)
    }

    private fun getItemBounds(
        widgetId: Int? = null,
        itemType: ItemType? = activeItem
    ): Pair<Int, Int> {
        val bounds = getWidgetBounds(widgetId)
        val density = resources.displayMetrics.density

        val itemSizePx = getItemSizePxForType(itemType)
        val marginPx = (8 * density).toInt()

        val maxX = (bounds.widthPx - itemSizePx - marginPx).coerceAtLeast(0)
        val maxY = (bounds.heightPx - itemSizePx - (2 * marginPx)).coerceAtLeast(0)

        return maxX to maxY
    }

    private fun playClickSound() {
        if (!isMuted) {
            try {
                clickPlayer?.start()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun playEatSound() {
        if (!isMuted) {
            try {
                eatPlayer?.start()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun playMeowSound() {
        if (isMuted) return
        try {
            val player = MediaPlayer.create(this, meowSounds.random())
            player?.setOnCompletionListener {
                try {
                    it.release()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            player?.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startWalkCountTest() {
        cancelChase()
        walkTestJob?.cancel(); actionJob?.cancel()
        currentSteps = 0; walkTargetSteps = 50
        actionJob = scope.launch {
            val (maxX, maxY) = getMaxBounds()
            targetX = maxX / 2
            targetY = (maxY * 0.85).toInt().coerceAtLeast(0)

            // ใช้ walkCatTo เดินไปกลางจอ (WALKING_TO_CENTER ไม่ถูกลูปหลักขยับ จึงต้องเดินเอง)
            val reached = walkCatTo(targetX, targetY, state = State.WALKING_TO_CENTER)

            if (reached && catState == State.WALKING_TO_CENTER) {
                setCatState(State.WALK_COUNTING)
                unityMessage = "กำลังเดิน... (ทดสอบ)"
                updateWidget()

                walkTestJob = scope.launch {
                    while (catState == State.WALK_COUNTING) {
                        delay(500)
                        if (catState != State.WALK_COUNTING) break
                        currentSteps++
                        updateWidget()
                        if (walkTargetSteps > 0 && currentSteps >= walkTargetSteps) {
                            unityMessage = "เดินครบ $walkTargetSteps ก้าวแล้ว! 🎉"
                            updateWidget()
                            delay(2000)
                            stopWalkCountTest()
                            break
                        }
                    }
                }
            }
        }
    }

    private fun stopWalkCountTest() {
        walkTestJob?.cancel(); walkTestJob = null; actionJob?.cancel()
        setCatState(State.IDLE); unityMessage = null; currentStatusIconRes = null
        currentSteps = 0; walkTargetSteps = 0
        updateWidget()
    }

    private fun startJumpAround(
        jumps: Int = 5,
        message: String = "โดดไปมา~",
        iconRes: Int? = null,
        fleeFromX: Int? = null
    ) {
        cancelChase()
        jumpJob?.cancel(); actionJob?.cancel()
        jumpJob = scope.launch {
            setCatState(State.JUMPING)
            unityMessage = message
            currentStatusIconRes = iconRes
            updateWidget()

            val (maxX, maxY) = getMaxBounds()
            val jumpHeight = (70 * resources.displayMetrics.density).toInt()

            for (i in 0 until jumps) {
                if (catState != State.JUMPING) return@launch
                val startX = catX
                val landX = when {
                    maxX <= 0 -> 0
                    i == 0 && fleeFromX != null ->
                        if (fleeFromX < maxX / 2) Random.nextInt(maxX / 2, maxX + 1)
                        else Random.nextInt(0, maxX / 2 + 1)
                    else -> Random.nextInt(0, maxX + 1)
                }
                val landY = catY

                if (landX < startX) isFacingLeft = true else if (landX > startX) isFacingLeft = false

                // วาด 5 เฟรมย่อยเพื่อความต่อเนื่องของวิถีโค้ง
                val steps = 5
                for (s in 1..steps) {
                    if (catState != State.JUMPING) return@launch
                    val progress = s.toFloat() / steps

                    catX = (startX + (landX - startX) * progress).toInt().coerceIn(0, maxX)
                    val arcY = (jumpHeight * kotlin.math.sin(progress * Math.PI)).toInt()
                    catY = (landY + arcY).coerceIn(0, maxY)

                    updateWidget()
                    delay(90)
                }

                // จังหวะแลนดิ้งถึงพื้นย่อตัว
                catX = landX.coerceIn(0, maxX)
                catY = landY.coerceIn(0, maxY)
                triggerSquishEffect(0.2f)
                updateWidget()
                delay(150) // ค้างท่าแลนดิ้งแป๊บนึงก่อนกระโดดต่อ
            }

            if (catState == State.JUMPING) {
                setCatState(State.IDLE)
                unityMessage = null
                currentStatusIconRes = null
                updateWidget()
            }
        }
    }

    private fun stopJumpAround() {
        jumpJob?.cancel(); jumpJob = null
        if (catState == State.JUMPING) {
            setCatState(State.IDLE)
            unityMessage = null
            currentStatusIconRes = null
            updateWidget()
        }
    }

    private fun startRolling(durationMs: Long = 3500L) {
        cancelChase()
        actionJob?.cancel()
        actionJob = scope.launch {
            setCatState(State.ROLLING)
            unityMessage = "กลิ้งเกลือก~"
            currentStatusIconRes = R.drawable.ic_status_heart
            boredom = (boredom - 40).coerceAtLeast(0)
            energy = (energy - 10).coerceAtLeast(0)
            saveStatsToPrefs()

            val (maxX, _) = getMaxBounds()
            val rollDirection = if (Random.nextBoolean()) 1 else -1
            val density = resources.displayMetrics.density
            val rollStepPx = (10 * density).toInt()

            val startTime = System.currentTimeMillis()
            var rollFrameIdx = 0

            while (System.currentTimeMillis() - startTime < durationMs && catState == State.ROLLING) {
                catX = (catX + rollDirection * rollStepPx).coerceIn(0, maxX)

                rollFrameIdx = (rollFrameIdx + 1) % rollFrames.size

                // สลับหันหน้าเฉพาะบางเฟรมให้ดูม้วนตัว
                if (rollFrameIdx % 2 == 0) {
                    isFacingLeft = !isFacingLeft
                }

                updateWidget()
                delay(160)
            }

            if (catState == State.ROLLING) {
                setCatState(State.IDLE)
                unityMessage = null
                currentStatusIconRes = null
                updateWidget()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Cat Widget Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Virtual Cat Companion")
            .setContentText("Your cat is hanging out on your screen")
            .setSmallIcon(R.drawable.cool_fun)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceJob?.cancel(); actionJob?.cancel(); walkTestJob?.cancel()
        itemFallJob?.cancel(); jumpJob?.cancel(); squishJob?.cancel()
        chaseJob?.cancel()
        drawerAnimJob?.cancel(); catToyPickJob?.cancel()
        interactionReactionJob?.cancel()
        bitmapCache.clear()
        visibleCatBoundsCache.evictAll()
        catFloorPaddingCache.evictAll()

        try {
            eatPlayer?.stop()
            eatPlayer?.release()
            eatPlayer = null

            clickPlayer?.stop()
            clickPlayer?.release()
            clickPlayer = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
