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
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    private val scope = CoroutineScope(Dispatchers.Main)

    private var isFirstRun = true
    private var catState = State.IDLE
    private var catX = 0
    private var catY = -5
    private var targetX = 0
    private var targetY = 0

    // Message and Status Tracking
    private var unityMessage: String? = null
    private var currentStatusIconRes: Int? = null
    private var randomMessage: String? = null

    // Animation frame tracking
    private var currentFrameIndex = 0
    private val bitmapCache = mutableMapOf<String, android.graphics.Bitmap>()

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
    private var chaseHits = 0
    private val CHASE_ROUNDS = 5

    private val chaseInviteMsgs = listOf("มาเล่นกับเราสิ~", "เบื่อจังเลย เล่นกันมั้ย?", "จับเราให้ได้สิ~")
    private val chaseCaughtMsgs = listOf("โดนละเหรอ!", "อ๊ะ! จับได้แล้วเหรอ~", "เก่งนี่... โดนละเหรอ")
    private val chaseTauntMsgs = listOf("จับไม่ได้หรอก~ เบ้ๆ", "ช้าจัง~ ๆ", "พลาดละสิ! ฮิๆ", "อยู่นี่นะ~ จับไม่โดน")

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
        Log.d(TAG, "broadcastStatsToUnity: hunger=$hunger, energy=$energy, boredom=$boredom, isAppOpen=$isAppOpen")
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
        CHASE_INVITE, CHASE_HOP
    }

    enum class ItemType { WAND, BOX, FOOD, COCKROACH, SNAKE }
    private val itemTypes = arrayOf(ItemType.WAND, ItemType.BOX, ItemType.FOOD, ItemType.COCKROACH, ItemType.SNAKE)

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
        State.NAP to StatusInfo(iconRes = R.drawable.ic_status_zzz, message = "", isClickable = false),
        State.SNUGGLE to StatusInfo(iconRes = R.drawable.ic_status_heart, message = "", isClickable = false),
        State.EATING to StatusInfo(iconRes = R.drawable.ic_status_food, message = "อร่อยจัง!", isClickable = false),
        State.PLAYING to StatusInfo(iconRes = R.drawable.ic_status_heart, message = "เล่นกัน!", isClickable = false),
        State.ANGRY to StatusInfo(
            iconRes = R.drawable.ic_status_angry,
            message = "โกรธแล้วนะ!",
            isClickable = true,
            targetState = State.SNUGGLE,
            targetDuration = 3500L
        ),
        State.STRETCH to StatusInfo(iconRes = R.drawable.ic_status_scratch, message = "บิดขี้เกียจ~", isClickable = false),
        State.JUMPING to StatusInfo(iconRes = R.drawable.ic_status_scratch, message = "กระโดดดึ๋ง!", isClickable = false),
        State.ROLLING to StatusInfo(iconRes = R.drawable.ic_status_heart, message = "กลิ้งเกลือก~", isClickable = false)
    )

    private val catTapReactions = listOf(
        CatReaction(State.PLAYING, 3500L, "", R.drawable.ic_status_heart),
        CatReaction(State.STRETCH, 3000L, "", R.drawable.ic_status_scratch),
        CatReaction(State.SNUGGLE, 4000L, "", R.drawable.ic_status_heart),
        CatReaction(State.ANGRY, 3000L, "", R.drawable.ic_status_angry),
        CatReaction(State.GROOMING, 3500L, "", null),
        CatReaction(State.ITCHY, 4000L, "", R.drawable.ic_status_scratch),
        CatReaction(State.HUNGRY, 4000L, "", R.drawable.ic_status_food),
        CatReaction(State.ROLLING, 3500L, "", R.drawable.ic_status_heart)
    )

    private val idleFrames = arrayOf("basic_anim/cat_idle.png")
    private val walkFrames = arrayOf("basic_anim/cat_walk.png")
    private val eatFrames = arrayOf(
        "cateat/cateat_01.png", "cateat/cateat_02.png", "cateat/cateat_03.png",
        "cateat/cateat_04.png", "cateat/cateat_05.png", "cateat/cateat_06.png", "cateat/cateat_07.png"
    )
    private val playFrames = arrayOf("basic_anim/cat_play.png")
    private val stretchFrames = arrayOf("basic_anim/cat_stresh.png")
    private val angryFrames = arrayOf("basic_anim/cat_angry.png")
    private val napFrames = arrayOf("basic_anim/cat_nap.png")
    private val snuggleFrames = arrayOf("basic_anim/cat_fun.png")
    private val groomingFrames = arrayOf(
        "gromming/grooming_01.png", "gromming/grooming_02.png", "gromming/grooming_03.png",
        "gromming/grooming_04.png", "gromming/grooming_05.png"
    )
    private val hungryFrames = arrayOf("basic_anim/cat_hungry.png")
    private val jumpFrames = arrayOf("basic_anim/cat_walk.png")
    private val rollFrames = arrayOf("basic_anim/cat_play.png", "basic_anim/cat_idle.png")
    private val wandPlayFrames = arrayOf(
        "catwand/catwand_01.gif", "catwand/catwand_02.gif",
        "catwand/catwand_03.gif", "catwand/catwand_04.gif"
    )
    private val boxHiddenFrames = arrayOf("catbox/bo")
    private val boxEmergeFrames = arrayOf(
        "catboxemerge/catboxemerge_1.gif", "catboxemerge/catboxemerge_2.gif",
        "catboxemerge/catboxemerge_3.gif", "catboxemerge/catboxemerge_4.gif"
    )
    private val trainIdleFrames = arrayOf("cattrain/cattrain_1.gif", "cattrain/cattrain_2.gif")

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, createNotification())
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
        try {
            startForeground(1, createNotification())
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            when (intent?.action) {
                CatWidgetProvider.ACTION_TOGGLE_MUTE -> {
                    isMuted = !isMuted
                    playClickSound()
                    updateWidget()
                }
                CatWidgetProvider.ACTION_TAP_CAT -> {
                    playClickSound()
                    playMeowSound()
                    triggerSquishEffect(1f)
                    if (chaseActive) handleChaseCatTap() else handleDirectCatTap()
                }
                CatWidgetProvider.ACTION_TAP_STATUS -> {
                    playClickSound()
                    triggerSquishEffect(0.6f)
                    handleStatusBubbleTap()
                }
                CatWidgetProvider.ACTION_FEED -> {
                    playClickSound()
                    playEatSound()
                    triggerSquishEffect(0.8f)
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
                    if (chaseActive) {
                        // ระหว่างเล่นจับ: แตะพลาด = แมวเยาะเย้ย (ไม่ปล่อยไอเทมตก)
                        handleChaseMissTap()
                    } else {
                        val (maxX, maxY) = getItemBounds()
                        if (maxX > 0 && maxY > 0) {
                            val randomX = Random.nextInt(0, maxX + 1)

                            // *** บังคับให้จุดตกลงมาอยู่บริเวณพื้น (ช่วง 75% ถึง 95% ของความสูงทั้งหมด) ***
                            val minLandY = (maxY * 0.75f).toInt()
                            val randomY = Random.nextInt(minLandY, maxY + 1)

                            spawnRandomItem(randomX, randomY)
                        } else {
                            Log.w(TAG, "Cannot spawn item: invalid bounds maxX=$maxX maxY=$maxY")
                        }
                    }
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
                    performStateAction(State.HUNGRY, 6000L, hungryInfo?.message ?: "หิวจังเลย!", hungryInfo?.iconRes)
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

        // Unity สั่งเริ่มเกมชวนเล่นจับได้ด้วย chase / tease / invite
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
            performStateAction(targetState, finalDuration, message ?: configInfo?.message, configInfo?.iconRes)
        } else {
            cancelChase()
            catState = State.IDLE
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
        val rand = Random.nextInt(100)
        val reaction = if (isBusyOrSleeping && rand < 10) {
            CatReaction(State.ANGRY, 3500L, if (catState == State.EATING) "อย่ามากวนตอนกินข้าว!" else "ง่วงอยู่ อย่ากวนนะ!", R.drawable.ic_status_angry)
        } else {
            catTapReactions.random()
        }

        if (reaction.state == State.JUMPING) {
            playMeowSound()
            startJumpAround()
        } else {
            performStateAction(reaction.state, reaction.duration, reaction.message, reaction.iconRes)
        }
    }

    private fun handleStatusBubbleTap() {
        if (chaseActive) return // กันแตะ bubble แล้วแมวไปกินข้าวกลางเกม

        val currentConfig = statusConfigMap[catState]
        if (currentConfig != null && currentConfig.isClickable && currentConfig.targetState != null) {
            val targetState = currentConfig.targetState
            val nextConfig = statusConfigMap[targetState]
            performStateAction(targetState, currentConfig.targetDuration, nextConfig?.message, nextConfig?.iconRes) {
                if (targetState == State.EATING) {
                    hunger = (hunger - 60).coerceAtLeast(0)
                    energy = (energy + 15).coerceAtMost(100)
                    boredom = (boredom - 10).coerceAtLeast(0)
                    saveStatsToPrefs()
                    playEatSound()
                }
            }
        } else {
            val eatingConfig = statusConfigMap[State.EATING]
            performStateAction(State.EATING, 5000L, eatingConfig?.message ?: "อร่อยจัง!", R.drawable.ic_status_food) {
                hunger = (hunger - 60).coerceAtLeast(0)
                energy = (energy + 15).coerceAtMost(100)
                boredom = (boredom - 10).coerceAtLeast(0)
                saveStatsToPrefs()
                playEatSound()
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
        triggerSquishEffect(0.4f)
        actionJob = scope.launch {
            catState = actionState
            unityMessage = message
            currentStatusIconRes = iconRes ?: statusConfigMap[actionState]?.iconRes

            when (actionState) {
                State.EATING -> { hunger = (hunger - 60).coerceAtLeast(0); energy = (energy + 15).coerceAtMost(100); boredom = (boredom - 10).coerceAtLeast(0) }
                State.NAP -> { energy = (energy + 60).coerceAtMost(100); hunger = (hunger + 10).coerceAtMost(100) }
                State.PLAYING, State.WAND_PLAY, State.BOX_EMERGE, State.ROLLING -> { boredom = (boredom - 60).coerceAtLeast(0); energy = (energy - 15).coerceAtLeast(0); hunger = (hunger + 15).coerceAtMost(100) }
                State.STRETCH, State.ITCHY, State.GROOMING, State.SNUGGLE -> { boredom = (boredom - 30).coerceAtLeast(0); energy = (energy - 5).coerceAtLeast(0) }
                else -> {}
            }
            saveStatsToPrefs()
            onStartAction()
            updateWidget()

            if (durationMs > 0) {
                delay(durationMs)
                if (catState == actionState) {
                    catState = State.IDLE
                    unityMessage = null
                    currentStatusIconRes = null
                    updateWidget()
                }
            }
        }
    }

    private fun resetState() {
        actionJob?.cancel()
        walkTestJob?.cancel()
        itemFallJob?.cancel()
        jumpJob?.cancel()
        squishJob?.cancel()
        cancelChase()
        squishScaleX = 1f
        squishScaleY = 1f
        catState = State.IDLE
        unityMessage = null
        currentStatusIconRes = null
        activeItem = null
        randomMessage = null
        hunger = 0
        energy = 100
        boredom = 0
        saveStatsToPrefs()
        updateWidget()
    }

    private fun spawnRandomItem(x: Int, y: Int) {
        if (catState in listOf(State.EATING, State.STRETCH, State.NAP, State.SNUGGLE, State.GROOMING)) return
        val (maxX, maxY) = getItemBounds()
        val safeX = x.coerceIn(0, maxX)
        val safeY = y.coerceIn(0, maxY)

        playClickSound()
        actionJob?.cancel()
        itemFallJob?.cancel()

        val item = itemTypes.random()
        activeItem = item
        dropItemThenAct(item, safeX, safeY)
    }

    private fun dropItemThenAct(item: ItemType, landX: Int, landY: Int) {
        itemFallJob?.cancel()
        itemFallJob = scope.launch {
            val density = resources.displayMetrics.density
            val (maxX, maxY) = getItemBounds()

            val safeLandX = landX.coerceIn(0, maxX)
            val safeLandY = landY.coerceIn(0, maxY)

            // เริ่มจากเหนือจอสุดๆ
            val startY = (-300 * density).toInt()

            itemX = safeLandX
            itemY = startY
            updateWidget()

            var currentY = startY.toFloat()

            // ระบบ Physics (แรงโน้มถ่วง)
            var velocityY = 0f
            val gravity = 9.5f * density

            while (currentY < safeLandY) {
                velocityY += gravity
                currentY += velocityY

                itemX = safeLandX
                itemY = currentY.toInt().coerceAtMost(safeLandY)

                updateWidget()
                delay(16)
            }

            itemX = safeLandX
            itemY = safeLandY
            updateWidget()

            when (item) {
                ItemType.WAND -> handleWandItem(landX, landY)
                ItemType.BOX -> handleBoxItem(landX, landY)
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
            targetX = x.coerceIn(0, maxX)
            targetY = y.coerceIn(0, maxY)
            catState = State.WALKING_TO_ITEM
            while (catState == State.WALKING_TO_ITEM && (kotlin.math.abs(catX - targetX) >= 5 || kotlin.math.abs(catY - targetY) >= 5)) {
                delay(100)
            }
            if (catState == State.WALKING_TO_ITEM) {
                catX = targetX; catY = targetY
                activeItem = null
                catState = State.WAND_PLAY
                triggerSquishEffect(0.7f)
                boredom = (boredom - 60).coerceAtLeast(0)
                energy = (energy - 15).coerceAtLeast(0)
                hunger = (hunger + 15).coerceAtMost(100)
                saveStatsToPrefs()
                updateWidget()
                delay(3000)
                if (catState == State.WAND_PLAY) { catState = State.IDLE; updateWidget() }
            }
        }
    }

    private fun handleBoxItem(x: Int, y: Int) {
        cancelChase()
        actionJob?.cancel()
        actionJob = scope.launch {
            val (maxX, maxY) = getMaxBounds()
            targetX = x.coerceIn(0, maxX); targetY = y.coerceIn(0, maxY)
            catState = State.WALKING_TO_ITEM
            while (catState == State.WALKING_TO_ITEM && (kotlin.math.abs(catX - targetX) >= 5 || kotlin.math.abs(catY - targetY) >= 5)) {
                delay(100)
            }
            if (catState == State.WALKING_TO_ITEM) {
                catX = targetX; catY = targetY
                catState = State.BOX_HIDDEN
                boredom = (boredom - 60).coerceAtLeast(0)
                energy = (energy - 15).coerceAtLeast(0)
                saveStatsToPrefs()
                updateWidget()
                delay(1500)
                if (catState == State.BOX_HIDDEN) {
                    catState = State.BOX_EMERGE
                    triggerSquishEffect(0.9f)
                    updateWidget()
                    delay(1000)
                    if (catState == State.BOX_EMERGE) { catState = State.IDLE; activeItem = null; updateWidget() }
                }
            }
        }
    }

    private fun handleFoodItem(x: Int, y: Int) {
        cancelChase()
        actionJob?.cancel()
        actionJob = scope.launch {
            val (maxX, maxY) = getMaxBounds()
            targetX = x.coerceIn(0, maxX); targetY = y.coerceIn(0, maxY)
            catState = State.WALKING_TO_ITEM
            while (catState == State.WALKING_TO_ITEM && (kotlin.math.abs(catX - targetX) >= 5 || kotlin.math.abs(catY - targetY) >= 5)) {
                delay(100)
            }
            if (catState == State.WALKING_TO_ITEM) {
                catX = targetX; catY = targetY
                activeItem = null
                catState = State.EATING
                hunger = (hunger - 60).coerceAtLeast(0)
                energy = (energy + 15).coerceAtMost(100)
                boredom = (boredom - 10).coerceAtLeast(0)
                saveStatsToPrefs()
                playEatSound()
                unityMessage = "อร่อยจัง!"
                currentStatusIconRes = R.drawable.ic_status_food
                updateWidget()
                delay(4000)
                if (catState == State.EATING) { catState = State.IDLE; unityMessage = null; currentStatusIconRes = null; updateWidget() }
            }
        }
    }

    // เจอแมงสาบ/งู: ตกใจ 800ms แล้วโดดหนีหลายรอบ
    private fun handleScaryBugItem(x: Int, y: Int, isSnake: Boolean) {
        cancelChase()
        actionJob?.cancel()
        actionJob = scope.launch {
            catState = State.ANGRY
            unityMessage = if (isSnake) "กรี๊ด! งูมา! ช่วยด้วยยย!" else "ยี้! แมลงสาบ น่ากลัวโว้ย!"
            currentStatusIconRes = R.drawable.ic_status_angry
            playMeowSound()
            triggerSquishEffect(1.2f)
            updateWidget()
            delay(800)

            activeItem = null
            boredom = (boredom - 20).coerceAtLeast(0)
            saveStatsToPrefs()

            // โดดหนีหลายรอบ รอบแรกโดดไปฝั่งตรงข้ามกับตัวเจ้าปัญหา
            startJumpAround(
                jumps = Random.nextInt(8, 13),
                message = if (isSnake) "ไปให้พ้นนน!" else "อย๊ากกก!",
                iconRes = R.drawable.ic_status_angry,
                fleeFromX = x
            )
        }
    }

    // ---------------- Chase (ชวนเล่นจับ) event ----------------

    private fun cancelChase() {
        chaseJob?.cancel(); chaseJob = null
        chaseActive = false
        chaseCaught = false
    }

    private fun startChaseEvent() {
        cancelChase()
        actionJob?.cancel(); jumpJob?.cancel(); itemFallJob?.cancel()
        activeItem = null
        chaseActive = true
        chaseHits = 0

        chaseJob = scope.launch {
            // 1) แมวนอนชวน
            catState = State.CHASE_INVITE
            unityMessage = chaseInviteMsgs.random()
            currentStatusIconRes = R.drawable.ic_status_heart
            playMeowSound()
            triggerSquishEffect(0.5f)
            updateWidget()
            delay(2500)

            // 2) เล่น 5 รอบ
            for (round in 1..CHASE_ROUNDS) {
                chaseCaught = false
                catState = State.CHASE_HOP
                unityMessage = "รอบ $round/$CHASE_ROUNDS จับเราให้ได้สิ~"
                currentStatusIconRes = R.drawable.ic_status_heart
                updateWidget()

                var hops = 0
                while (hops < 3 && !chaseCaught) {
                    chaseHopOnce()
                    hops++
                }

                if (chaseCaught) {
                    chaseHits++
                    unityMessage = chaseCaughtMsgs.random()
                    currentStatusIconRes = R.drawable.ic_status_heart
                    playMeowSound()
                    triggerSquishEffect(1f)
                } else {
                    unityMessage = chaseTauntMsgs.random()
                    currentStatusIconRes = R.drawable.ic_status_angry
                    playMeowSound()
                    triggerSquishEffect(0.6f)
                }
                updateWidget()
                delay(1500)
            }

            // 3) ครบรอบ แมวเหนื่อย
            catState = State.CHASE_INVITE
            unityMessage = when {
                chaseHits >= 4 -> "เหนื่อยแล้ววว... จับเก่งจริงๆ หอบๆ"
                chaseHits >= 1 -> "เหนื่อยแล้ว ไม่ไหวแล้วน้า~ หอบๆ"
                else -> "เหนื่อยแล้ว... แต่จับเราไม่ได้เลยนะ! หอบๆ"
            }
            currentStatusIconRes = R.drawable.ic_status_zzz
            energy = (energy - 30).coerceAtLeast(0)
            boredom = (boredom - 60).coerceAtLeast(0)
            hunger = (hunger + 15).coerceAtMost(100)
            saveStatsToPrefs()
            updateWidget()
            delay(4500)

            chaseActive = false
            catState = State.IDLE
            unityMessage = null
            currentStatusIconRes = null
            updateWidget()
        }
    }

    // โดด 1 ครั้ง แล้วหยุดพักตรงจุดที่ลง (ช่วงนี้จับง่ายสุด) ถ้าถูกจับจะหยุดทันที
    private suspend fun chaseHopOnce() {
        val (maxX, maxY) = getMaxBounds()
        val jumpHeight = (60 * resources.displayMetrics.density).toInt()
        val startX = catX
        val landX = if (maxX > 0) Random.nextInt(0, maxX + 1) else 0
        val landY = catY.coerceIn(0, maxY)

        for (s in 1..8) {
            if (chaseCaught) return
            val p = s / 8f
            catX = (startX + (landX - startX) * p).toInt().coerceIn(0, maxX)
            catY = (landY + (jumpHeight * kotlin.math.sin(p * Math.PI)).toInt()).coerceIn(0, maxY)
            updateWidget()
            delay(60)
        }
        catX = landX.coerceIn(0, maxX)
        catY = landY
        triggerSquishEffect(0.6f)
        updateWidget()
        delay(350)
    }

    // แตะโดนแมว (จับได้เฉพาะตอนแมวโดดอยู่)
    private fun handleChaseCatTap() {
        if (catState == State.CHASE_HOP) chaseCaught = true
    }

    // แตะพลาด (โดนพื้นหลัง) แมวเยาะเย้ยทันที
    private fun handleChaseMissTap() {
        if (catState != State.CHASE_HOP) return
        unityMessage = chaseTauntMsgs.random()
        currentStatusIconRes = R.drawable.ic_status_angry
        playMeowSound()
        updateWidget()
    }

    // ---------------- Animation loop ----------------

    private fun startAnimationLoop() {
        serviceJob = scope.launch {
            while (true) {
                if (catState == State.IDLE) {
                    delay(1000)
                    val rand = Random.nextInt(100)
                    if (rand > 65) {
                        catState = State.WALKING
                        val (maxX, maxY) = getMaxBounds()
                        targetX = if (maxX > 0) Random.nextInt(0, maxX + 1) else 0
                        targetY = if (maxY > 0) Random.nextInt(0, maxY + 1) else 0
                        playMeowSound()
                    } else if (rand in 5..35) {
                        val randomStatusState = pickWeightedIdleState()
                        if (randomStatusState == State.JUMPING) {
                            startJumpAround()
                        } else {
                            val statusInfo = statusConfigMap[randomStatusState]
                            performStateAction(randomStatusState, 4000L, statusInfo?.message, statusInfo?.iconRes)
                        }
                    } else if (rand in 36..40 && energy > 40 && hunger < 70) {
                        // สุ่มเริ่มเกมชวนเล่นจับเอง
                        startChaseEvent()
                    }
                } else if (catState == State.WALKING || catState == State.WALKING_TO_CENTER || catState == State.WALKING_TO_ITEM) {
                    // ขอบเขตคงที่ + clamp เป้าหมายและตำแหน่งจริงทุก tick กันแมวหลุดขอบ
                    val (maxX, maxY) = getMaxBounds()
                    targetX = targetX.coerceIn(0, maxX)
                    targetY = targetY.coerceIn(0, maxY)

                    val step = 5
                    if (catX < targetX) catX += step else if (catX > targetX) catX -= step
                    if (catY < targetY) catY += step else if (catY > targetY) catY -= step
                    catX = catX.coerceIn(0, maxX)
                    catY = catY.coerceIn(0, maxY)

                    if (kotlin.math.abs(catX - targetX) < step && kotlin.math.abs(catY - targetY) < step) {
                        if (catState == State.WALKING) catState = State.IDLE
                    }
                }

                tickStats()
                updateWidget()
                delay(100)
            }
        }
    }

    private fun tickStats() {
        if (isAppOpen) return
        statTickCounter++
        if (statTickCounter % 10 != 0) return

        when (catState) {
            State.IDLE -> { hunger = (hunger + 1).coerceAtMost(100); boredom = (boredom + 1).coerceAtMost(100); energy = (energy - 1).coerceAtLeast(0) }
            State.WALKING, State.WALKING_TO_CENTER, State.WALKING_TO_ITEM -> { hunger = (hunger + 1).coerceAtMost(100); energy = (energy - 2).coerceAtLeast(0) }
            State.PLAYING, State.WAND_PLAY, State.BOX_HIDDEN, State.BOX_EMERGE, State.ROLLING -> { boredom = (boredom - 2).coerceAtLeast(0); energy = (energy - 2).coerceAtLeast(0); hunger = (hunger + 2).coerceAtMost(100) }
            State.EATING -> { hunger = (hunger - 5).coerceAtLeast(0); energy = (energy + 2).coerceAtMost(100) }
            State.NAP -> { energy = (energy + 5).coerceAtMost(100); hunger = (hunger + 1).coerceAtMost(100) }
            else -> { energy = (energy - 1).coerceAtLeast(0); boredom = (boredom - 1).coerceAtLeast(0) }
        }

        saveStatsToPrefs()
        if (catState == State.IDLE) {
            if (energy <= 20) {
                val napInfo = statusConfigMap[State.NAP]
                performStateAction(State.NAP, 6000L, "ง่วงนอนจัง... Zzz", napInfo?.iconRes)
            } else if (hunger >= 70) {
                val hungryInfo = statusConfigMap[State.HUNGRY]
                performStateAction(State.HUNGRY, 0L, "หิวจังเลย!", hungryInfo?.iconRes)
            } else if (boredom >= 70) {
                val itchyInfo = statusConfigMap[State.ITCHY]
                performStateAction(State.ITCHY, 5000L, "เบื่อจัง เล่นกันนะ!", itchyInfo?.iconRes)
            }
        }
    }

    private fun getBitmapFromAsset(filePath: String, reqWidthPx: Int, reqHeightPx: Int): android.graphics.Bitmap? {
        val cacheKey = "${filePath}_${reqWidthPx}x$reqHeightPx"
        bitmapCache[cacheKey]?.let { return it }

        return try {
            val boundsOptions = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            assets.open(filePath).use { android.graphics.BitmapFactory.decodeStream(it, null, boundsOptions) }

            val decodeOptions = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(boundsOptions, reqWidthPx, reqHeightPx)
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            }
            val sampledBitmap = assets.open(filePath).use { android.graphics.BitmapFactory.decodeStream(it, null, decodeOptions) } ?: return null

            val finalBitmap = if (sampledBitmap.width != reqWidthPx || sampledBitmap.height != reqHeightPx) {
                android.graphics.Bitmap.createScaledBitmap(sampledBitmap, reqWidthPx, reqHeightPx, true).also {
                    if (it !== sampledBitmap) sampledBitmap.recycle()
                }
            } else sampledBitmap

            bitmapCache[cacheKey] = finalBitmap
            finalBitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun calculateInSampleSize(options: android.graphics.BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
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

    // ขนาดฐานของแมว (dp) ตามความกว้าง widget  <<< แก้ขนาดแมวรวมที่นี่ >>>
    private fun getBaseCatSizeDp(): Int {
        val appWidgetManager = AppWidgetManager.getInstance(this)
        val componentName = ComponentName(this, CatWidgetProvider::class.java)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)

        var currentWidthDp = 150
        if (appWidgetIds.isNotEmpty()) {
            val options = appWidgetManager.getAppWidgetOptions(appWidgetIds[0])
            val isPortrait = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
            val w = options.getInt(if (isPortrait) AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
            if (w > 0) currentWidthDp = w
        }

        return when {
            currentWidthDp < 200 -> 60    // widget เล็ก
            currentWidthDp > 350 -> 130   // widget ใหญ่
            else -> 90                    // widget กลาง
        }
    }

    // ขนาด (dp) ของแต่ละท่า โดยยังไม่คูณ squish  <<< แก้ขนาดรายท่าที่นี่ >>>
    private fun getCatSizeDpForState(state: State): Pair<Int, Int> {
        val baseCatSizeDp = getBaseCatSizeDp()
        return when (state) {
            State.IDLE, State.WALKING, State.WALKING_TO_CENTER, State.WALKING_TO_ITEM, State.NAP, State.ANGRY, State.SNUGGLE, State.HUNGRY, State.ITCHY, State.TRAIN_IDLE, State.WALK_COUNTING -> Pair(baseCatSizeDp, baseCatSizeDp)
            State.EATING -> Pair(baseCatSizeDp + 25, baseCatSizeDp + 25)
            State.STRETCH -> Pair(baseCatSizeDp + 15, baseCatSizeDp)
            State.PLAYING -> Pair(baseCatSizeDp + 5, baseCatSizeDp + 5)
            State.WAND_PLAY -> Pair(baseCatSizeDp + 30, baseCatSizeDp + 30)
            State.BOX_HIDDEN, State.BOX_EMERGE -> Pair(baseCatSizeDp + 20, baseCatSizeDp + 20)
            State.GROOMING -> Pair(baseCatSizeDp + 5, baseCatSizeDp + 5)
            State.JUMPING -> Pair(baseCatSizeDp + 10, baseCatSizeDp + 10)
            State.ROLLING -> Pair(baseCatSizeDp + 15, baseCatSizeDp + 5)
            State.CHASE_INVITE -> Pair(baseCatSizeDp + 15, baseCatSizeDp)
            State.CHASE_HOP -> Pair(baseCatSizeDp + 10, baseCatSizeDp + 10)
        }
    }

    private fun getCatSizePxForState(state: State): Pair<Int, Int> {
        val density = resources.displayMetrics.density
        val (widthDp, heightDp) = getCatSizeDpForState(state)
        val widthPx = (widthDp * density * squishScaleX).toInt().coerceAtLeast(1)
        val heightPx = (heightDp * density * squishScaleY).toInt().coerceAtLeast(1)
        return Pair(widthPx, heightPx)
    }

    // ขนาดที่ใช้คำนวณขอบเขต: ไม่ผูกกับ squish และไม่เล็กกว่าท่าเดิน/กระโดด (base+10)
    // เผื่อ squish ด้วย SAFETY_SCALE ทำให้ขอบเขตนิ่งระหว่างเดิน
    private val SAFETY_SCALE = 1.1f

    private fun getStableCatSizePx(): Pair<Int, Int> {
        val density = resources.displayMetrics.density
        val (curW, curH) = getCatSizeDpForState(catState)
        val (refW, refH) = getCatSizeDpForState(State.JUMPING)
        val widthPx = (maxOf(curW, refW) * SAFETY_SCALE * density).toInt().coerceAtLeast(1)
        val heightPx = (maxOf(curH, refH) * SAFETY_SCALE * density).toInt().coerceAtLeast(1)
        return widthPx to heightPx
    }

    private fun triggerSquishEffect(intensity: Float = 1f) {
        squishJob?.cancel()
        squishJob = scope.launch {
            val keyframes = listOf(
                (1f + 0.25f * intensity) to (1f - 0.20f * intensity),
                (1f - 0.18f * intensity) to (1f + 0.28f * intensity),
                (1f + 0.10f * intensity) to (1f - 0.08f * intensity),
                (1f - 0.05f * intensity) to (1f + 0.06f * intensity),
                1f to 1f
            )
            for ((sx, sy) in keyframes) {
                squishScaleX = sx
                squishScaleY = sy
                updateWidget()
                delay(55)
            }
            squishScaleX = 1f
            squishScaleY = 1f
        }
    }

    private fun updateWidget() {
        val appWidgetManager = AppWidgetManager.getInstance(this)
        val componentName = ComponentName(this, CatWidgetProvider::class.java)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)

        for (widgetId in appWidgetIds) {
            val views = RemoteViews(packageName, R.layout.cat_widget)
            val currentFrames = when (catState) {
                State.EATING -> eatFrames
                State.PLAYING -> playFrames
                State.WALKING, State.WALKING_TO_CENTER, State.WALKING_TO_ITEM -> walkFrames
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
                else -> idleFrames
            }

            currentFrameIndex = (currentFrameIndex + 1) % currentFrames.size
            val (catWidthPx, catHeightPx) = getCatSizePxForState(catState)
            val bitmap = getBitmapFromAsset(currentFrames[currentFrameIndex], catWidthPx, catHeightPx)
            if (bitmap != null) views.setImageViewBitmap(R.id.catImage, bitmap)

            val (maxX, maxY) = getMaxBounds(widgetId)
            catX = catX.coerceIn(0, maxX)
            catY = catY.coerceIn(0, maxY)
            views.setViewPadding(R.id.catContainer, catX, 0, 0, catY)

            val configInfo = statusConfigMap[catState]
            val iconRes = currentStatusIconRes ?: configInfo?.iconRes
            val displayMessage = unityMessage ?: configInfo?.message ?: randomMessage

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
                val statusIntent = makePendingIntent(widgetId, CatWidgetProvider.ACTION_TAP_STATUS)
                views.setOnClickPendingIntent(R.id.statusBubbleContainer, statusIntent)
            } else {
                views.setViewVisibility(R.id.statusBubbleContainer, View.GONE)
            }

            views.setImageViewResource(R.id.btnMute, if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
            views.setOnClickPendingIntent(R.id.btnMute, makePendingIntent(widgetId, CatWidgetProvider.ACTION_TOGGLE_MUTE))
            views.setOnClickPendingIntent(R.id.catImage, makePendingIntent(widgetId, CatWidgetProvider.ACTION_TAP_CAT))
            views.setOnClickPendingIntent(R.id.btnTest, makePendingIntent(widgetId, CatWidgetProvider.ACTION_TEST_COMMAND))
            views.setOnClickPendingIntent(R.id.catiBackground, makePendingIntent(widgetId, CatWidgetProvider.ACTION_TAP_WIDGET))

            try {
                views.setProgressBar(R.id.barHunger, 100, hunger, false)
                views.setProgressBar(R.id.barEnergy, 100, energy, false)
                views.setProgressBar(R.id.barBoredom, 100, boredom, false)
            } catch (e: Exception) {}

            try {
                views.setOnClickPendingIntent(R.id.btnTestWalk, makePendingIntent(widgetId, CatWidgetProvider.ACTION_TEST_WALKCOUNT))
                views.setOnClickPendingIntent(R.id.btnReset, makePendingIntent(widgetId, CatWidgetProvider.ACTION_RESET))
            } catch (e: Exception) {}

            // Item overlay logic
            try {
                if (activeItem != null && catState != State.BOX_HIDDEN && catState != State.BOX_EMERGE) {
                    val itemAssetPath = when (activeItem) {
                        ItemType.COCKROACH -> "toy/cockroach.png"
                        ItemType.SNAKE -> "toy/snake.png"
                        else -> null
                    }

                    val (itemMaxX, itemMaxY) = getItemBounds(widgetId)
                    itemX = itemX.coerceIn(0, itemMaxX)
                    itemY = itemY.coerceIn(0, itemMaxY)

                    if (itemAssetPath != null) {
                        val itemBitmap = getBitmapFromAsset(itemAssetPath, (80 * resources.displayMetrics.density).toInt(), (80 * resources.displayMetrics.density).toInt())
                        if (itemBitmap != null) {
                            views.setImageViewBitmap(R.id.itemImage, itemBitmap)
                            views.setViewPadding(R.id.itemImage, itemX, itemY, 0, 0)
                            views.setViewVisibility(R.id.itemImage, View.VISIBLE)
                        } else {
                            views.setViewVisibility(R.id.itemImage, View.GONE)
                        }
                    } else {
                        val itemIconRes = when (activeItem) {
                            ItemType.WAND -> R.drawable.ball
                            ItemType.BOX -> R.drawable.box
                            ItemType.FOOD -> R.drawable.ic_status_food
                            else -> null
                        }
                        if (itemIconRes != null) {
                            views.setImageViewResource(R.id.itemImage, itemIconRes)
                            views.setViewPadding(R.id.itemImage, itemX, itemY, 0, 0)
                            views.setViewVisibility(R.id.itemImage, View.VISIBLE)
                        } else {
                            views.setViewVisibility(R.id.itemImage, View.GONE)
                        }
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
        return PendingIntent.getBroadcast(this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
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

            // ใช้ค่า MIN ก่อน (ขนาดที่การันตีว่ามองเห็นได้เสมอ) ถ้าไม่มีค่อยใช้ MAX
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
        return WidgetBounds(widthPx = (widthDp * density).toInt().coerceAtLeast(1), heightPx = (heightDp * density).toInt().coerceAtLeast(1))
    }

    // ขอบเขตการเดิน/กระโดดของแมว  <<< ชนขอบขวา: ปรับ marginRightPx >>>
    private fun getMaxBounds(widgetId: Int? = null): Pair<Int, Int> {
        val bounds = getWidgetBounds(widgetId)
        val (catWidthPx, catHeightPx) = getStableCatSizePx()
        val density = resources.displayMetrics.density

        val systemInsetXPx = (16 * density).toInt()  // margin ระบบของ widget (ซ้าย+ขวา)
        val marginRightPx = (16 * density).toInt()   // เผื่อเพิ่มฝั่งขวา ยังชนก็เพิ่มเลขนี้
        val marginYPx = (8 * density).toInt()

        val usableWidthPx = bounds.widthPx - systemInsetXPx
        val maxX = (usableWidthPx - catWidthPx - marginRightPx).coerceAtLeast(0)
        val maxY = (bounds.heightPx - catHeightPx - marginYPx).coerceAtLeast(0)
        return maxX to maxY
    }

    private fun getItemBounds(widgetId: Int? = null): Pair<Int, Int> {
        val bounds = getWidgetBounds(widgetId)
        val density = resources.displayMetrics.density

        val itemSizePx = (60 * density).toInt().coerceAtLeast(1)
        val marginPx = (8 * density).toInt()

        val maxX = (bounds.widthPx - itemSizePx - marginPx).coerceAtLeast(0)

        // บังคับความสูงจำลองอย่างน้อย 350dp ให้ไอเทมมีระยะร่วงลงมาถึงพื้น
        val forcedHeightPx = maxOf(bounds.heightPx, (350 * density).toInt())
        val maxY = (forcedHeightPx - itemSizePx - (2 * marginPx)).coerceAtLeast(0)

        return maxX to maxY
    }

    private fun playClickSound() { if (!isMuted) try { clickPlayer?.start() } catch (e: Exception) {} }
    private fun playEatSound() { if (!isMuted) try { eatPlayer?.start() } catch (e: Exception) {} }
    private fun playMeowSound() {
        if (isMuted) return
        try {
            val player = MediaPlayer.create(this, meowSounds.random())
            player?.setOnCompletionListener { it.release() }
            player?.start()
        } catch (e: Exception) {}
    }

    private fun startWalkCountTest() {
        cancelChase()
        walkTestJob?.cancel(); actionJob?.cancel()
        currentSteps = 0; walkTargetSteps = 50
        actionJob = scope.launch {
            val (maxX, maxY) = getMaxBounds()
            targetX = maxX / 2
            targetY = (maxY * 0.85).toInt().coerceAtLeast(0)
            catState = State.WALKING_TO_CENTER

            while (catState == State.WALKING_TO_CENTER && (kotlin.math.abs(catX - targetX) >= 5 || kotlin.math.abs(catY - targetY) >= 5)) {
                delay(100)
            }

            if (catState == State.WALKING_TO_CENTER) {
                catX = targetX; catY = targetY
                catState = State.WALK_COUNTING
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
        catState = State.IDLE; unityMessage = null; currentStatusIconRes = null
        currentSteps = 0; walkTargetSteps = 0
        updateWidget()
    }

    // โดดไปมา (ใช้ทั้งโดดเล่นปกติ และโดดหนีแมงสาบ/งู)
    private fun startJumpAround(
        jumps: Int = 6,
        message: String = "โดดไปมา~",
        iconRes: Int? = null,
        fleeFromX: Int? = null
    ) {
        cancelChase()
        jumpJob?.cancel(); actionJob?.cancel()
        jumpJob = scope.launch {
            catState = State.JUMPING
            unityMessage = message
            currentStatusIconRes = iconRes
            updateWidget()

            val (maxX, maxY) = getMaxBounds()
            val jumpHeight = (80 * resources.displayMetrics.density).toInt()
            val jumpDurationMs = 300L

            for (i in 0 until jumps) {
                if (catState != State.JUMPING) return@launch
                val startX = catX
                val landX = when {
                    maxX <= 0 -> 0
                    // รอบแรก: หนีไปฝั่งตรงข้ามกับจุดที่ของตก
                    i == 0 && fleeFromX != null ->
                        if (fleeFromX < maxX / 2) Random.nextInt(maxX / 2, maxX + 1)
                        else Random.nextInt(0, maxX / 2 + 1)
                    else -> Random.nextInt(0, maxX + 1)
                }
                val landY = catY

                playMeowSound()
                for (s in 1..4) {
                    if (catState != State.JUMPING) return@launch
                    val progress = s.toFloat() / 4
                    catX = (startX + (landX - startX) * progress * 0.5f).toInt().coerceIn(0, maxX)
                    catY = (landY + (jumpHeight * kotlin.math.sin(progress * Math.PI)).toInt()).coerceIn(0, maxY + jumpHeight)
                    updateWidget()
                    delay(jumpDurationMs / 8)
                }
                for (s in 1..4) {
                    if (catState != State.JUMPING) return@launch
                    val progress = s.toFloat() / 4
                    catX = (startX + (landX - startX) * (0.5f + progress * 0.5f)).toInt().coerceIn(0, maxX)
                    catY = (landY + (jumpHeight * kotlin.math.sin((1 - progress) * Math.PI)).toInt()).coerceIn(0, maxY + jumpHeight)
                    updateWidget()
                    delay(jumpDurationMs / 8)
                }

                catX = landX.coerceIn(0, maxX)
                catY = landY.coerceIn(0, maxY)
                triggerSquishEffect(0.9f)
                updateWidget()
                delay(150) // พักระหว่างรอบ ลดเลขนี้ถ้าอยากให้โดดถี่ขึ้น
            }

            if (catState == State.JUMPING) {
                catState = State.IDLE
                unityMessage = null
                currentStatusIconRes = null
                updateWidget()
            }
        }
    }

    private fun stopJumpAround() {
        jumpJob?.cancel(); jumpJob = null
        if (catState == State.JUMPING) {
            catState = State.IDLE
            unityMessage = null
            currentStatusIconRes = null
            updateWidget()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(CHANNEL_ID, "Cat Widget Service Channel", NotificationManager.IMPORTANCE_LOW)
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
        eatPlayer?.release(); clickPlayer?.release()
    }
}