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
    private var catY = 0
    private var targetX = 0
    private var targetY = 0

    // ---- ทิศทางการหันหน้าของแมว (ซ้าย = true, ขวา = false) ----
    private var isFacingLeft = false

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
    private var chaseStarted = false
    private var chaseHits = 0
    private val CHASE_ROUNDS = 5

    private val chaseInviteMsgs = listOf("มาเล่นกับเราสิ~", "เบื่อจังเลย เล่นกันมั้ย?", "จับเราให้ได้สิ~")
    private val chaseFleeMsgs = listOf("โดนละ! หนีดีกว่า~", "จับได้แล้วเหรอ! ไปล่ะนะ~", "เก่งนี่... แต่จับไม่ทันหรอก!")
    private val chaseTauntMsgs = listOf("จับไม่ได้หรอก~ เบ้ๆ", "ช้าจัง~ ๆ", "พลาดละสิ! ฮิๆ", "อยู่นี่นะ~ จับไม่โดน")
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

    // ---- 3-Layer Behavior Architecture: Emotion System ----
    enum class Emotion {
        CONTENT, // Normal/Balanced
        HAPPY,   // High energy, low hunger, low boredom
        BORED,   // High boredom
        HUNGRY,  // High hunger
        TIRED    // Low energy
    }

    private var currentEmotion: Emotion = Emotion.CONTENT
    private var lastEmotionChangeTime: Long = 0L
    private val MIN_EMOTION_DURATION_MS = 15_000L // Minimum duration to prevent rapid emotion flips

    private var lastChaseTime: Long = 0L
    private val CHASE_COOLDOWN_MS = 60_000L // 60s cooldown for chase event

    private var lastSpecialItemTime: Long = 0L
    private val SPECIAL_ITEM_COOLDOWN_MS = 30_000L // 30s cooldown for scary bugs/boxes

    private fun updateEmotion(): Emotion {
        val now = System.currentTimeMillis()
        val targetEmotion = when {
            // Priority 1: TIRED (Low energy) with Hysteresis
            energy <= 25 || (currentEmotion == Emotion.TIRED && energy < 40) -> Emotion.TIRED
            // Priority 2: HUNGRY (High hunger) with Hysteresis
            hunger >= 65 || (currentEmotion == Emotion.HUNGRY && hunger > 50) -> Emotion.HUNGRY
            // Priority 3: BORED (High boredom) with Hysteresis
            boredom >= 65 || (currentEmotion == Emotion.BORED && boredom > 50) -> Emotion.BORED
            // Priority 4: HAPPY (High energy, low hunger, low boredom)
            energy >= 60 && hunger <= 35 && boredom <= 35 -> Emotion.HAPPY
            // Default: CONTENT / NORMAL
            else -> Emotion.CONTENT
        }

        if (targetEmotion != currentEmotion && (now - lastEmotionChangeTime >= MIN_EMOTION_DURATION_MS)) {
            currentEmotion = targetEmotion
            lastEmotionChangeTime = now
            Log.d(TAG, "Cat Emotion updated to: $currentEmotion (Hunger: $hunger, Energy: $energy, Boredom: $boredom)")
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
                    State.ROLLING to 10
                )
                pickWeightedState(weights)
            }
            Emotion.HAPPY -> {
                val weights = listOf(
                    State.PLAYING to 35,
                    State.ROLLING to 25,
                    State.JUMPING to 15,
                    State.STRETCH to 15,
                    State.WALKING to 10
                )
                pickWeightedState(weights)
            }
            Emotion.BORED -> {
                val canChase = (now - lastChaseTime >= CHASE_COOLDOWN_MS)
                val weights = mutableListOf(
                    State.WALKING to 40,
                    State.ITCHY to 30,
                    State.PLAYING to 20
                )
                if (canChase) {
                    weights.add(State.CHASE_INVITE to 10)
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

    private val idleFrames = arrayOf(
        "blackcat_idle/idle1.png",
    )


    private val walkFrames = arrayOf(
        "blackcat_walk/walk1.png",
        "blackcat_walk/walk2.png",
        "blackcat_walk/walk3.png",
        "blackcat_walk/walk4.png",
        "blackcat_walk/walk5.png"
    )
    private val eatFrames = arrayOf(
        "blackcat_eat/eat1.png",
        "blackcat_eat/eat2.png",
        "blackcat_eat/eat3.png",
        "blackcat_eat/eat4.png"
    )
    private val playFrames = arrayOf(
        "blackcat_play/play1.png",
        "blackcat_play/play2.png",
        "blackcat_play/play3.png",

    )
    private val stretchFrames = arrayOf(
        "blackcat_stresh/stresh1.png",
        "blackcat_stresh/stresh2.png",
        "blackcat_stresh/stresh3.png"
    )
    private val angryFrames = arrayOf(
        "blackcat_angry/angry3.png",
        "blackcat_angry/angry4.png"
    )
    private val napFrames = arrayOf(
        "blackcat_nap/nap1.png",
        "blackcat_nap/nap2.png",
        "blackcat_nap/nap3.png"
    )
    private val snuggleFrames = arrayOf("blackcat_nap/nap3.png")
    private val groomingFrames = arrayOf(
        "blackcat_grooming/grooming1.png",
        "blackcat_grooming/grooming2.png",
        "blackcat_grooming/grooming3.png",
        "blackcat_grooming/grooming4.png"
    )
    private val hungryFrames = arrayOf(
        "blackcat_hungry/hungry1.png",
        "blackcat_hungry/hungry2.png",
        "blackcat_hungry/hungry3.png"
    )
    private val jumpFrames = arrayOf(
        "blackcat_jump/jump1.png",
        "blackcat_jump/jump2.png",
        "blackcat_jump/jump3.png",
        "blackcat_jump/jump4.png",
        )
    private val rollFrames = arrayOf(
        "blackcat_roll/roll1.png",
        "blackcat_roll/roll2.png",
        "blackcat_roll/roll3.png",
        "blackcat_roll/roll4.png"
    )
    private val wandPlayFrames = arrayOf(
        "catwand/catwand_01.gif", "catwand/catwand_02.gif",
        "catwand/catwand_03.gif", "catwand/catwand_04.gif"
    )
    private val boxHiddenFrames = arrayOf("blackcat_anim/nap.png")
    private val boxEmergeFrames = arrayOf(
        "catboxemerge/catboxemerge_1.gif", "catboxemerge/catboxemerge_2.gif",
        "catboxemerge/catboxemerge_3.gif", "catboxemerge/catboxemerge_4.gif"
    )
    private val trainIdleFrames = arrayOf("cattrain/cattrain_1.gif", "cattrain/cattrain_2.gif")

    private var lastSoundState: State? = null

    private fun setCatState(newState: State){
        if(catState == newState) return

        catState = newState

        when(catState){
            State.JUMPING,
            State.PLAYING,
            State.STRETCH,
            State.ROLLING,
            State.GROOMING,
            State.NAP,
            State.EATING,
            State.CHASE_INVITE -> {
                playMeowSound()
            }

            else -> {}
        }
    }

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
                    if (chaseActive) {
                        handleChaseMissTap()
                    } else {
                        val (maxX, maxY) = getItemBounds()
                        if (maxX > 0 && maxY > 0) {
                            val randomX = Random.nextInt(0, maxX + 1)
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

                CatWidgetProvider.ACTION_TEST_CHASE -> {
                    playClickSound()
                    if(chaseActive){
                        cancelChase()
                        setCatState(State.IDLE)
                        unityMessage = null
                        currentStatusIconRes = null
                        updateWidget()
                    }else{
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

            if(catState != State.BOX_HIDDEN){
                performStateAction(targetState, finalDuration, message ?: configInfo?.message, configInfo?.iconRes)
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

        if (reaction.state == State.JUMPING) {
            startJumpAround()
        } else {
            performStateAction(reaction.state, reaction.duration, reaction.message, reaction.iconRes)
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

        if (chaseActive) return

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
        triggerSquishEffect(0.2f)
        actionJob = scope.launch {

            setCatState(actionState)
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

                    setCatState(State.IDLE)
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
        setCatState(State.IDLE)
        catX = 0
        catY = 0
        unityMessage = null
        currentStatusIconRes = null
        activeItem = null
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

    private fun spawnRandomItem(x: Int, y: Int) {
        if (catState in listOf(State.EATING, State.STRETCH, State.NAP, State.SNUGGLE, State.GROOMING)) return

        val now = System.currentTimeMillis()
        val canSpecial = (now - lastSpecialItemTime >= SPECIAL_ITEM_COOLDOWN_MS)

        // If on special item cooldown, stick to standard items (FOOD, WAND)
        val candidateItems = if (canSpecial) {
            itemTypes
        } else {
            arrayOf(ItemType.WAND, ItemType.FOOD)
        }

        val item = candidateItems.random()
        if (item in listOf(ItemType.BOX, ItemType.COCKROACH, ItemType.SNAKE)) {
            lastSpecialItemTime = now
        }

        val (maxX, maxY) = getItemBounds(itemType = item)
        val safeX = x.coerceIn(0, maxX)
        val safeY = y.coerceIn(0, maxY)

        playClickSound()
        actionJob?.cancel()
        itemFallJob?.cancel()

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

            val startY = (-300 * density).toInt()

            itemX = safeLandX
            itemY = startY
            updateWidget()

            var currentY = startY.toFloat()
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
            val (itemMaxX, itemMaxY) = getItemBounds()
            val bounds = getWidgetBounds() // 🌟 เพิ่มบรรทัดนี้เพื่อดึงขนาด Widget ครับ
            val density = resources.displayMetrics.density

            // 🎲 สุ่มว่าแมวจะเล่นบอลหรือไม่เล่น (เช่น โอกาสเล่น 65%)
            val willPlay = Random.nextInt(100) < 65

            // ให้แมวหันหน้าไปทางลูกบอลก่อน
            targetX = x.coerceIn(0, maxX)
            if (targetX < catX) isFacingLeft = true else if (targetX > catX) isFacingLeft = false
            updateWidget()

            if (!willPlay) {
                // ---- กรณีแมวสุ่มเลือก "ไม่เล่น" (เมินบอล) ----
                unityMessage = listOf("เมิน... ⚽", "ไม่อยากเล่นอ่ะ~", "เฉยๆ อะ ⚽", "ไม่เห็นน่าสนใจเลย~").random()
                currentStatusIconRes = null
                setCatState(State.IDLE)
                updateWidget()

                delay(2000)

                // บอลหายไปแล้วแมวกลับสู่สภาพปกติ
                activeItem = null
                unityMessage = null
                setCatState(State.IDLE)
                updateWidget()
                return@launch
            }

            // ---- กรณีแมวเลือก "เล่นบอล" ----
            unityMessage = listOf("บอลลล! ⚽", "มาเล่นกัน~!", "จะจับให้ได้เลย! ⚽").random()
            playMeowSound()
            updateWidget()
            delay(600)

            // 1) ลูกบอลกระเด้งไปมาทั่วแมพหลายๆ รอบ & แมวมันก็จะกระโดดไปตะครุบ ตามมม
            val bounceRounds = Random.nextInt(4, 7) // กระเด้ง 4 - 6 รอบ
            var currentBallX = x.coerceIn(0, itemMaxX)
            var currentBallY = y.coerceIn(0, itemMaxY)

            for (round in 1..bounceRounds) {
                // สุ่มจุดหมายปลายทางใหม่ของลูกบอลทั่วแมพ
                val nextBallX = Random.nextInt(0, maxOf(1, itemMaxX + 1))
                val minLandY = (itemMaxY * 0.5f).toInt()
                val nextBallY = Random.nextInt(minLandY, maxOf(minLandY + 1, itemMaxY + 1))

                // ตำแหน่งแมวเป้าหมาย (ให้แมวกระโดดไปหาตำแหน่งบอล)
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

                    // บอลกระเด้งเป็นส่วนโค้ง
                    itemX = (startBallX + (nextBallX - startBallX) * t).toInt()
                    val ballArc = (60 * density * sinT).toInt()
                    itemY = (startBallY + (nextBallY - startBallY) * t - ballArc).toInt().coerceIn(-100, itemMaxY)

                    // แมวกระโดดตามเป็นส่วนโค้ง
                    catX = (startCatX + (nextCatX - startCatX) * t).toInt().coerceIn(0, maxX)
                    val catArc = (40 * density * sinT).toInt()
                    catY = (startCatY + (catArc * sinT)).toInt().coerceIn(0, maxY)

                    updateWidget()
                    delay(35)
                }

                currentBallX = nextBallX
                currentBallY = nextBallY
                catX = nextCatX
                catY = 0 // แมวลงสู่พื้น
                itemX = currentBallX
                itemY = currentBallY
                updateWidget()

                playClickSound()
                triggerSquishEffect(0.2f)
                delay(120)
            }

            // 2) พอตะครุบโดน -> จับๆ บอล (ตะครุบจับบอลกับพื้น)
            catX = currentBallX.coerceIn(0, maxX)
            catY = 0
            itemX = currentBallX
            itemY = currentBallY

            setCatState(State.WAND_PLAY)
            unityMessage = listOf("จับได้แล้ว! ⚽✨", "แปะๆๆ 🐾", "ตะครุบโดนแล้ว!").random()
            updateWidget()

            // เล่นเอฟเฟกต์ย่อตัวจับบอล 2-3 ครั้ง
            for (i in 1..3) {
                triggerSquishEffect(0.3f)
                playClickSound()
                delay(250)
            }

            // 3) เตะบอลขึ้นฟ้า แล้วบอลปลิวหายไปสูงๆ! (ขนาดแมวเท่าเดิมไม่เปลี่ยน)
            // 3) เตะบอลขึ้นฟ้า แล้วบอลปลิวหายไปสูงๆ พร้อมค่อยๆ เล็กลง!
            unityMessage = listOf("เตะขึ้นฟ้าเลย! 🚀⚽", "ยิงงงง! ⚽💥", "ลอยไปนู่นแล้ว!").random()
            playMeowSound()

            val startKickY = itemY
            val kickSteps = 15 // เพิ่มก้าวให้ลื่นไหลขึ้น

            // เป้าหมาย Y คือลอยพ้นขอบบนสุดของ Widget (-100dp)
            val targetY = (-100 * density).toInt()

            for (step in 1..kickSteps) {
                val t = step.toFloat() / kickSteps

                // ⚽ 1. ลูกบอลพุ่งขึ้นไปบนสุดของ Widget (จากตำแหน่งเดิม -> พ้นขอบบน)
                itemY = (startKickY + (targetY - startKickY) * t).toInt()

                // ⚽ 2. ลูกบอลค่อยๆ เล็กลงจาก 100% เหลือ 20% (ย่อขนาดตามระยะทาง)
                itemScale = 1.0f - (0.8f * t)

                // 🐱 แมวย่อกระโดดเตะในก้าวแรกๆ
                if (step == 2) triggerSquishEffect(0.3f)

                updateWidget()
                delay(25) // ความเร็วการพุ่ง
            }

            // 🛑 ล้างค่าไอเทมและคืนค่า Scale กลับเป็นปกติ
            activeItem = null
            itemScale = 1.0f
            catY = 0

            // อัปเดตสเตตัส
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
            val bounds = getWidgetBounds()
            val density = resources.displayMetrics.density
            val boxSizePx = getItemSizePxForType(ItemType.BOX)
            val (catWidthPx, catHeightPx) = getCatSizePxForState(State.WALKING_TO_ITEM)

            // จัดกึ่งกลางแนวนอนให้ตรงกับกล่อง (เหมือนเดิม)
            targetX = (x + (boxSizePx / 2) - (catWidthPx / 2)).coerceIn(0, maxX)

            // 🔧 แปลงตำแหน่งกล่อง (paddingTop) -> ตำแหน่งแมว (paddingBottom)
            // ให้ "ขอบล่างของกล่อง" ตรงกับ "ขอบล่างของแมว"
            targetY = (bounds.heightPx - (y + boxSizePx)).coerceIn(0, maxY)

            if (targetX < catX) isFacingLeft = true else if (targetX > catX) isFacingLeft = false

            setCatState(State.WALKING_TO_ITEM)
            while (
                catState == State.WALKING_TO_ITEM &&
                (kotlin.math.abs(catX - targetX) >= 5 || kotlin.math.abs(catY - targetY) >= 5)
            ) {
                delay(100)
            }

            if (catState != State.WALKING_TO_ITEM) return@launch
            catX = targetX
            catY = targetY
            updateWidget()

            // ---- 1) กระโดดเล็กน้อยเข้ากล่อง ----
            val hopHeight = (30 * density).toInt()
            val baseY = catY
            for (s in 1..6) {
                if (catState != State.WALKING_TO_ITEM) return@launch
                val p = s / 6f
                catY = (baseY + (hopHeight * kotlin.math.sin(p * Math.PI)).toInt()).coerceIn(0, maxY)
                updateWidget()
                delay(35)
            }
            catY = baseY

            // ---- 2) ย่อตัวเล็กแล้วหายเข้ากล่อง ----
            setCatState(State.BOX_HIDDEN)
            squishScaleX = 0f
            squishScaleY = 0f
            boredom = (boredom - 60).coerceAtLeast(0)
            energy = (energy - 15).coerceAtLeast(0)
            saveStatsToPrefs()
            updateWidget()
            delay(500)
            if (catState != State.BOX_HIDDEN) return@launch

            // ---- 3) กล่องเริ่มสั่น ----
            val shakeBaseX = itemX
            repeat(6) { i ->
                if (catState != State.BOX_HIDDEN) return@launch
                itemX = shakeBaseX + if (i % 2 == 0) (4 * density).toInt() else -(4 * density).toInt()
                updateWidget()
                delay(80)
            }
            itemX = shakeBaseX
            updateWidget()
            delay(150)
            if (catState != State.BOX_HIDDEN) return@launch

            // ---- 4) โผล่ออกมาตัวใหญ่ ----
            squishScaleX = 1f
            squishScaleY = 1f
            updateWidget()
            setCatState(State.BOX_EMERGE)

            val jumpArcHeight = (80 * density).toInt()
            val steps = 8
            delay(50)
            for (s in 1..steps) {
                if (catState != State.BOX_EMERGE) return@launch
                val p = s / steps.toFloat()
                catY = (baseY + (jumpArcHeight * kotlin.math.sin(p * Math.PI)).toInt()).coerceIn(0, maxY)
                updateWidget()
                delay(40)
            }
            catY = baseY
            triggerSquishEffect(0.3f)

            // ---- 5) กล่องหายทันทีที่แมวโผล่ออกมาสุด ----
            activeItem = null
            updateWidget()

            delay(600)
            if (catState == State.BOX_EMERGE) {
                setCatState(State.IDLE)
                updateWidget()
            }

            isJumpscareActive = true
            playMeowSound() // หรือเล่นเสียงตกใจ/ขู่
            updateWidget()
            delay(600)

            // 🛑 ปิด Jumpscare และลบกล่องออก
            isJumpscareActive = false
            activeItem = null
            triggerSquishEffect(0.3f)
            updateWidget()
        }finally{
            isJumpscareActive = false
            activeItem = null
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
            val (maxX, maxY) = getMaxBounds()
            targetX = x.coerceIn(0, maxX); targetY = y.coerceIn(0, maxY)

            if (targetX < catX) isFacingLeft = true else if (targetX > catX) isFacingLeft = false


            setCatState(State.WALKING_TO_ITEM)
            while (catState == State.WALKING_TO_ITEM && (kotlin.math.abs(catX - targetX) >= 5 || kotlin.math.abs(catY - targetY) >= 5)) {
                delay(100)
            }
            if (catState == State.WALKING_TO_ITEM) {
                catX = targetX; catY = targetY
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
                if (catState == State.EATING) { setCatState(State.IDLE); unityMessage = null; currentStatusIconRes = null; updateWidget() }
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
        chaseStarted = false
        chaseHits = 0
        lastChaseTime = System.currentTimeMillis()

        chaseJob = scope.launch {
            // 1) ชวนเล่น รอผู้เล่นแตะแมวเพื่อเริ่ม

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

            // 2) เริ่มเล่น กระโดดหนี ทำ CHASE_ROUNDS รอบ
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

            // 3) เล่นครบแล้ว -> ร่อน/เดินกลับลงมาตั้งหลักที่พื้นด้านล่างสุด
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

            // 4) จบ event
            chaseActive = false
            val afterState = if (Random.nextBoolean()) State.NAP else State.ROLLING
            val afterInfo = statusConfigMap[afterState]

            setCatState(afterState)
            unityMessage = afterInfo?.message
            currentStatusIconRes = afterInfo?.iconRes
            when (afterState) {
                State.NAP -> { energy = (energy + 60).coerceAtMost(100); hunger = (hunger + 10).coerceAtMost(100) }
                State.ROLLING -> { boredom = (boredom - 60).coerceAtLeast(0); energy = (energy - 15).coerceAtLeast(0); hunger = (hunger + 15).coerceAtMost(100) }
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

    // ฟังก์ชันกระโดดทั่ว Widget สุ่มทั้งซ้าย-ขวา (X) และ บน-ล่าง (Y) พร้อมแอนิเมชัน
    private suspend fun chaseHopOnceFast() {
        val (maxX, maxY) = getMaxBounds()
        val jumpArcHeight = (30 * resources.displayMetrics.density).toInt()
        val startX = catX
        val startY = catY

        // สุ่มจุดลงจอดทั่ว Widget
        val landX = if (maxX > 0) Random.nextInt(0, maxX + 1) else 0
        val landY = if (maxY > 0) Random.nextInt(0, maxY + 1) else 0

        Log.d(TAG, "=== ChaseHopStart ===")
        Log.d(TAG, "Bounds: maxX=$maxX, maxY=$maxY")
        Log.d(TAG, "From: ($startX, $startY) -> TargetLand: ($landX, $landY)")

        if (landX < startX) isFacingLeft = true else if (landX > startX) isFacingLeft = false

        val steps = 8
        for (s in 1..steps) {
            if (chaseCaught) return
            val p = s / steps.toFloat()

            catX = (startX + (landX - startX) * p).toInt().coerceIn(0, maxX)

            val linearY = (startY + (landY - startY) * p).toInt()
            val arcY = (jumpArcHeight * kotlin.math.sin(p * Math.PI)).toInt()

            catY = (linearY + arcY).coerceIn(0, maxY)

            Log.d(TAG, "Step $s/$steps: catX=$catX, catY=$catY (linearY=$linearY, arcY=$arcY)")

            updateWidget()
            delay(30)
        }

        catX = landX.coerceIn(0, maxX)
        catY = landY.coerceIn(0, maxY)
        Log.d(TAG, "Landed at: catX=$catX, catY=$catY")
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

        Log.d(TAG, "=== FleeHop ===")
        Log.d(TAG, "From: ($startX, $startY) -> Land: ($landX, $landY) [maxY=$maxY]")

        if (landX < startX) isFacingLeft = true else if (landX > startX) isFacingLeft = false

        val steps = 6
        for (s in 1..steps) {
            if (!chaseActive) return
            val p = s / steps.toFloat()
            catX = (startX + (landX - startX) * p).toInt().coerceIn(0, maxX)

            val linearY = (startY + (landY - startY) * p).toInt()
            val arcY = (jumpArcHeight * kotlin.math.sin(p * Math.PI)).toInt()
            catY = (linearY + arcY).coerceIn(0, maxY)

            Log.d(TAG, "Flee Step $s: catX=$catX, catY=$catY")

            updateWidget()
            delay(25)
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
                delay(30)
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

    // ---------------- Animation loop ----------------

    private fun startAnimationLoop() {
        serviceJob = scope.launch {
            while (true) {
                updateEmotion()

                if (catState == State.IDLE) {
                    delay(1000)
                    val rand = Random.nextInt(100)
                    if (rand < 60) {
                        setCatState(State.WALKING)
                        val (maxX, maxY) = getMaxBounds()
                        targetX = if (maxX > 0) Random.nextInt(0, maxX + 1) else 0
                        targetY = 0
                        if (targetX < catX) isFacingLeft = true else if (targetX > catX) isFacingLeft = false
                    } else {
                        val chosenState = pickActionForEmotion(currentEmotion)
                        if (chosenState == State.CHASE_INVITE) {
                            startChaseEvent()
                        } else if (chosenState == State.JUMPING) {
                            startJumpAround()
                        } else if (chosenState != State.IDLE && chosenState != State.WALKING) {
                            val statusInfo = statusConfigMap[chosenState]
                            performStateAction(chosenState, statusInfo?.targetDuration ?: 4000L, statusInfo?.message, statusInfo?.iconRes)
                        }
                    }
                } else if (catState == State.WALKING || catState == State.WALKING_TO_CENTER || catState == State.WALKING_TO_ITEM) {
                    val (maxX, maxY) = getMaxBounds()
                    targetX = targetX.coerceIn(0, maxX)
                    targetY = targetY.coerceIn(0, maxY)

                    if (targetX < catX) {
                        isFacingLeft = true
                    } else if (targetX > catX) {
                        isFacingLeft = false
                    }

                    // เดินไปหากล่องให้รีบกว่าปกติ (step ใหญ่ขึ้น)
                    val step = if (catState == State.WALKING_TO_ITEM && activeItem == ItemType.BOX) 12 else 5

                    if (catX < targetX) catX += step else if (catX > targetX) catX -= step
                    if (catY < targetY) catY += step else if (catY > targetY) catY -= step
                    catX = catX.coerceIn(0, maxX)
                    catY = catY.coerceIn(0, maxY)

                    if (kotlin.math.abs(catX - targetX) < step && kotlin.math.abs(catY - targetY) < step) {
                        if (catState == State.WALKING) setCatState(State.IDLE)
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
    }

    private fun getBitmapFromAsset(
        filePath: String,
        reqWidthPx: Int,
        reqHeightPx: Int,
        flipHorizontal: Boolean = false
    ): android.graphics.Bitmap? {
        val cacheKey = "${filePath}_${reqWidthPx}x${reqHeightPx}_flip_$flipHorizontal"
        bitmapCache[cacheKey]?.let { return it }

        return try {
            val boundsOptions = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            assets.open(filePath).use { android.graphics.BitmapFactory.decodeStream(it, null, boundsOptions) }

            val decodeOptions = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(boundsOptions, reqWidthPx, reqHeightPx)
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            }
            val sampledBitmap = assets.open(filePath).use { android.graphics.BitmapFactory.decodeStream(it, null, decodeOptions) } ?: return null

            val matrix = android.graphics.Matrix()
            if (flipHorizontal) {
                matrix.postScale(-1f, 1f, sampledBitmap.width / 2f, sampledBitmap.height / 2f)
            }

            val scaledBitmap = android.graphics.Bitmap.createScaledBitmap(sampledBitmap, reqWidthPx, reqHeightPx, true)

            val finalBitmap = if (flipHorizontal) {
                android.graphics.Bitmap.createBitmap(scaledBitmap, 0, 0, scaledBitmap.width, scaledBitmap.height, matrix, true).also {
                    if (it !== scaledBitmap) scaledBitmap.recycle()
                }
            } else {
                scaledBitmap
            }

            if (sampledBitmap !== scaledBitmap && sampledBitmap !== finalBitmap) {
                sampledBitmap.recycle()
            }

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

    private fun getItemSizeDpForType(type: ItemType?): Int{
        return when(type){

            ItemType.WAND      -> 35
            ItemType.BOX       -> 20
            ItemType.FOOD      -> 45
            ItemType.COCKROACH -> 55
            ItemType.SNAKE     -> 70
            else               -> 60
        }
    }


    private fun getItemSizePxForType(type: ItemType?): Int {
        val density = resources.displayMetrics.density
        return (getItemSizeDpForType(type) * density).toInt().coerceAtLeast(1)
    }

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
            currentWidthDp < 200 -> 60
            currentWidthDp > 350 -> 130
            else -> 90
        }
    }

    private fun getCatSizeDpForState(state: State): Pair<Int, Int> {
        val baseCatSizeDp = getBaseCatSizeDp()
        return when (state) {
            State.IDLE -> Pair((baseCatSizeDp * 1f).toInt(), (baseCatSizeDp * 1.4f).toInt())
            State.NAP  -> Pair((baseCatSizeDp * 1f).toInt(), (baseCatSizeDp * 1.4f).toInt())

            State.WALKING, State.WALKING_TO_CENTER, State.WALKING_TO_ITEM,
            State.ANGRY, State.SNUGGLE, State.HUNGRY, State.ITCHY,
            State.TRAIN_IDLE, State.WALK_COUNTING -> Pair(baseCatSizeDp, baseCatSizeDp)

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

    private val SAFETY_SCALE = 1.1f

    private fun getStableCatSizePx(): Pair<Int, Int> {
        val density = resources.displayMetrics.density
        val (curW, curH) = getCatSizeDpForState(catState)
        val (idleW, idleH) = getCatSizeDpForState(State.IDLE)
        val (refW, refH) = getCatSizeDpForState(State.JUMPING)

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
                delay(40)
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

            val bitmap = getBitmapFromAsset(
                currentFrames[currentFrameIndex],
                catWidthPx,
                catHeightPx,
                flipHorizontal = isFacingLeft
            )
            if (bitmap != null) views.setImageViewBitmap(R.id.catImage, bitmap)

            val (maxX, maxY) = getMaxBounds(widgetId)
            catX = catX.coerceIn(0, maxX)
            catY = catY.coerceIn(0, maxY)

            // catX คือระยะจากขอบซ้าย
            // catY คือระยะความสูงจากขอบล่าง (ยิ่ง catY มาก แมวจะยิ่งลอยสูงขึ้น)
            views.setViewPadding(R.id.catContainer, catX, 0, 0, catY)

            val isBoxThere = (catState == State.BOX_HIDDEN || catState == State.BOX_EMERGE)

            val configInfo = statusConfigMap[catState]
            val iconRes = if(isBoxThere) null else currentStatusIconRes ?: configInfo?.iconRes
            val displayMessage = if(isBoxThere) null else unityMessage ?: configInfo?.message ?: randomMessage

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
            // เพิ่มส่วนนี้ลงใน updateWidget()
            if (isJumpscareActive) {
                // 1. ใส่รูปหน้าแมว Jumpscare (เปลี่ยน R.drawable.cat_jumpscare_face เป็นรูปของคุณ)
                views.setImageViewResource(R.id.imgJumpscare, R.drawable.cat_jumpscare_face)
                views.setViewVisibility(R.id.imgJumpscare, View.VISIBLE)

                // 2. ซ่อน Status และไอคอนอื่นๆ ชั่วคราวเพื่อความสมจริง
                views.setViewVisibility(R.id.statusBubbleContainer, View.GONE)
                views.setViewVisibility(R.id.itemImage, View.GONE)
            } else {
                views.setViewVisibility(R.id.imgJumpscare, View.GONE)
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
                views.setOnClickPendingIntent(R.id.btnTestChase, makePendingIntent(widgetId,CatWidgetProvider.ACTION_TEST_CHASE))
            } catch (e: Exception) {}

            // Item overlay logic
            // Item overlay logic
            // Item overlay logic
            try {
                if (activeItem != null) {
                    val (itemMaxX, itemMaxY) = getItemBounds(widgetId)

                    // ⚠️ ไม่ดัก coerceIn ล็อกขอบบนของ itemY เพื่อให้บอลลอยทะลุขอบบนได้
                    itemX = itemX.coerceIn(0, itemMaxX)

                    val itemAssetPath = when (activeItem) {
                        ItemType.COCKROACH -> "toy/cockroach.png"
                        ItemType.SNAKE -> "toy/snake.png"
                        else -> null
                    }

                    if (itemAssetPath != null) {
                        val baseSize = (80 * resources.displayMetrics.density * itemScale).toInt().coerceAtLeast(1)
                        val itemBitmap = getBitmapFromAsset(itemAssetPath, baseSize, baseSize)
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
                            // 🌟 หากมี itemScale ให้สร้าง Bitmap และย่อขนาดย่อ/ขยายตาม itemScale
                            val baseSizePx = (getItemSizePxForType(activeItem) * itemScale).toInt().coerceAtLeast(1)
                            val drawable = androidx.core.content.ContextCompat.getDrawable(this, itemIconRes)

                            if (drawable != null && itemScale < 0.99f) {
                                val bitmap = android.graphics.Bitmap.createBitmap(baseSizePx, baseSizePx, android.graphics.Bitmap.Config.ARGB_8888)
                                val canvas = android.graphics.Canvas(bitmap)
                                drawable.setBounds(0, 0, canvas.width, canvas.height)
                                drawable.draw(canvas)

                                views.setImageViewBitmap(R.id.itemImage, bitmap)
                            } else {
                                views.setImageViewResource(R.id.itemImage, itemIconRes)
                            }

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
        val (catWidthPx, catHeightPx) = getStableCatSizePx()
        val density = resources.displayMetrics.density

        val systemInsetXPx = (16 * density).toInt()
        val marginRightPx = (16 * density).toInt()
        val marginYPx = (8 * density).toInt()

        val usableWidthPx = bounds.widthPx - systemInsetXPx
        val maxX = (usableWidthPx - catWidthPx - marginRightPx).coerceAtLeast(0)

        // 🌟 แก้ไขจุดสำคัญ: บังคับให้ maxY มีขนาดขั้นต่ำอย่างน้อย 150dp เพื่อให้แมวกระโดดลอยสูงได้จริง
        val minJumpHeightPx = (150 * density).toInt()
        val calculatedMaxY = bounds.heightPx - catHeightPx - marginYPx
        val maxY = maxOf(calculatedMaxY, minJumpHeightPx)

        return maxX to maxY
    }

    private fun getItemBounds(widgetId: Int? = null, itemType: ItemType? = activeItem): Pair<Int, Int> {
        val bounds = getWidgetBounds(widgetId)
        val density = resources.displayMetrics.density

        val itemSizePx = getItemSizePxForType(itemType)
        val marginPx = (8 * density).toInt()

        val maxX = (bounds.widthPx - itemSizePx - marginPx).coerceAtLeast(0)
        val maxY = (bounds.heightPx - itemSizePx - (2 * marginPx))

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

            if (targetX < catX) isFacingLeft = true else if (targetX > catX) isFacingLeft = false


            setCatState(State.WALKING_TO_CENTER)

            while (catState == State.WALKING_TO_CENTER && (kotlin.math.abs(catX - targetX) >= 5 || kotlin.math.abs(catY - targetY) >= 5)) {
                delay(100)
            }

            if (catState == State.WALKING_TO_CENTER) {
                catX = targetX; catY = targetY

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
        jumps: Int = 6,
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
            val jumpHeight = (80 * resources.displayMetrics.density).toInt()
            val jumpDurationMs = 300L

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


                for (s in 1..4) {
                    if (catState != State.JUMPING) return@launch
                    val progress = s.toFloat() / 4
                    catX = (startX + (landX - startX) * progress * 0.5f).toInt().coerceIn(0, maxX)
                    catY = (landY + (jumpHeight * kotlin.math.sin(progress * Math.PI)).toInt()).coerceIn(0, maxY)
                    updateWidget()
                    delay(jumpDurationMs / 8)
                }
                for (s in 1..4) {
                    if (catState != State.JUMPING) return@launch
                    val progress = s.toFloat() / 4
                    catX = (startX + (landX - startX) * (0.5f + progress * 0.5f)).toInt().coerceIn(0, maxX)
                    catY = (landY + (jumpHeight * kotlin.math.sin((1 - progress) * Math.PI)).toInt()).coerceIn(0, maxY)
                    updateWidget()
                    delay(jumpDurationMs / 8)
                }

                catX = landX.coerceIn(0, maxX)
                catY = landY.coerceIn(0, maxY)
                triggerSquishEffect(0.2f)
                updateWidget()
                delay(150)
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