package com.example.widgettesting

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.widgettesting.CatWidgetService.State
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatAnimationTest {
    private val service = CatWidgetService().apply {
        field("isMuted").setBoolean(this, true)
    }

    private fun field(name: String) = CatWidgetService::class.java.getDeclaredField(name).apply {
        isAccessible = true
    }

    private fun state(state: State) {
        CatWidgetService::class.java.getDeclaredMethod("setCatState", State::class.java).apply {
            isAccessible = true
        }.invoke(service, state)
    }

    @Suppress("UNCHECKED_CAST")
    private fun frames() = CatWidgetService::class.java.getDeclaredMethod("getFramesForState").apply {
        isAccessible = true
    }.invoke(service) as Array<String>

    private fun advance(elapsed: Long = 180L) {
        val now = field("lastFrameAdvanceTime").getLong(service) + elapsed
        CatWidgetService::class.java.getDeclaredMethod("advanceAnimationFrame", Long::class.javaPrimitiveType).apply {
            isAccessible = true
        }.invoke(service, now)
    }

    private fun frame() = frames()[field("currentFrameIndex").getInt(service)]

    @Test
    fun walkingPlaysBothTransitionsOnce() {
        state(State.WALKING)
        repeat(3) { index ->
            assertEquals("cati_idle_to_walk_new/frame_0$index.png", frame())
            advance()
        }
        assertTrue(frame().startsWith("cati_walk_new/"))
        state(State.IDLE)
        repeat(3) { index ->
            assertEquals("cati_walk_to_idle_new/frame_0$index.png", frame())
            advance()
        }
        assertTrue(frame().startsWith("cati_idle_new/"))
    }

    @Test
    fun walkingBetweenTargetsDoesNotRestartTransition() {
        state(State.WALKING)
        repeat(3) { advance() }
        state(State.WALKING_TO_ITEM)
        assertTrue(frame().startsWith("cati_walk_new/"))
    }

    @Test
    fun actionInterruptsPendingTransition() {
        state(State.WALKING)
        state(State.IDLE)
        state(State.GROOMING)
        assertEquals("cati_groom_new/frame_00.png", frame())
        assertFalse(field("isStoppingWalk").getBoolean(service))
    }

    @Test
    fun napStaysAsleepAfterLyingDown() {
        state(State.NAP)
        repeat(12) { advance() }
        assertEquals("cati_nap_new/frame_08.png", frame())
        repeat(12) {
            assertTrue(field("currentFrameIndex").getInt(service) in 8..11)
            advance()
        }
    }

    @Test
    fun updatesWithinFrameIntervalDoNotSpeedUpPlayback() {
        state(State.PLAYING)
        repeat(20) { advance(0) }
        assertEquals("cati_playball_new/frame_00.png", frame())
        advance()
        assertEquals("cati_playball_new/frame_01.png", frame())
        state(State.WAND_PLAY)
        assertEquals("cati_playball_new/frame_00.png", frame())
    }

    @Test
    fun suppliedSequencesDecodeAtFixedSizeAndGroundedFramesShareFloor() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val directories = mapOf(
            "cati_groom_new" to 18, "cati_stretch_new" to 12,
            "cati_roll_new" to 12, "cati_jump_new" to 12,
            "cati_nap_new" to 12, "cati_playball_new" to 8,
            "cati_idle_to_walk_new" to 3, "cati_walk_to_idle_new" to 3
        )
        for ((directory, count) in directories) {
            val paths = assets.list(directory)!!.sorted()
            assertEquals(directory, count, paths.size)
            var size: Pair<Int, Int>? = null
            for (path in paths) {
                val bitmap = assets.open("$directory/$path").use { BitmapFactory.decodeStream(it) }!!
                val actualSize = bitmap.width to bitmap.height
                if (size == null) size = actualSize
                assertEquals(path, size, actualSize)
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val bottom = pixels.indexOfLast { (it ushr 24) > 32 } / bitmap.width + 1
                assertTrue(path, bottom > 0)
                if (directory != "cati_jump_new") assertEquals(path, bitmap.height - 4, bottom)
                bitmap.recycle()
            }
        }
    }
}
