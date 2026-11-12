package com.iris.localai.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ArWorldProjectorTest {

    private val width = 1080f
    private val height = 2340f
    private val cameraHeight = 1.35f

    /** 手机平放、屏幕朝上：设备轴与世界轴一致，后置相机垂直向下。 */
    private val flat = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f,
    )

    /** 绕世界 X 轴倾斜 -30°，模拟手机抬起看向桌面。 */
    private val tilted = floatArrayOf(
        1f, 0f, 0f,
        0f, 0.8660254f, 0.5f,
        0f, -0.5f, 0.8660254f,
    )

    @Test
    fun `flat camera round trips ground points`() {
        val projector = ArWorldProjector(width, height, cameraHeight, flat)
        val center = projector.project(floatArrayOf(0f, 0f, 0f))
        assertNotNull(center)
        assertEquals(width / 2f, center!!.x, 0.5f)
        assertEquals(height / 2f, center.y, 0.5f)

        val screen = ScreenPoint(width * 0.62f, height * 0.74f)
        val ground = projector.groundPoint(screen.x, screen.y)
        assertNotNull(ground)
        val back = projector.project(ground!!)
        assertNotNull(back)
        assertEquals(screen.x, back!!.x, 1f)
        assertEquals(screen.y, back.y, 1f)
    }

    @Test
    fun `tilted camera round trips ground points`() {
        val projector = ArWorldProjector(width, height, cameraHeight, tilted)
        for (fraction in listOf(0.3f, 0.5f, 0.7f)) {
            val screen = ScreenPoint(width * fraction, height * 0.8f)
            val ground = projector.groundPoint(screen.x, screen.y)
            assertNotNull(ground)
            val back = projector.project(ground!!)
            assertNotNull(back)
            assertEquals(screen.x, back!!.x, 1f)
            assertEquals(screen.y, back.y, 1f)
        }
    }

    @Test
    fun `camera facing up has no ground plane`() {
        val up = floatArrayOf(
            1f, 0f, 0f,
            0f, -1f, 0f,
            0f, 0f, -1f,
        )
        val projector = ArWorldProjector(width, height, cameraHeight, up)
        assertNull(projector.groundPoint(width / 2f, height * 0.8f))
    }

    @Test
    fun `saved placement fraction anchors on ground`() {
        val projector = ArWorldProjector(width, height, cameraHeight, flat)
        val anchor = projector.groundFromFraction(0.1f, 0.05f)
        assertNotNull(anchor)
        assertEquals(0f, anchor!![2], 0.001f)
        val back = projector.project(anchor)
        assertNotNull(back)
        assertEquals(width * 0.6f, back!!.x, 1f)
        assertEquals(height * 0.77f, back.y, 1f)
    }

    @Test
    fun `camera position offsets ground and projection`() {
        val base = ArWorldProjector(width, height, cameraHeight, flat)
        val moved = ArWorldProjector(width, height, cameraHeight, flat, cameraX = 1.2f, cameraY = -0.7f)
        val screen = ScreenPoint(width * 0.4f, height * 0.82f)
        val groundBase = base.groundPoint(screen.x, screen.y)
        val groundMoved = moved.groundPoint(screen.x, screen.y)
        assertNotNull(groundBase)
        assertNotNull(groundMoved)
        assertEquals(groundBase!![0] + 1.2f, groundMoved!![0], 0.01f)
        assertEquals(groundBase[1] - 0.7f, groundMoved[1], 0.01f)
        val back = moved.project(groundMoved)
        assertNotNull(back)
        assertEquals(screen.x, back!!.x, 1f)
        assertEquals(screen.y, back.y, 1f)
    }
}
