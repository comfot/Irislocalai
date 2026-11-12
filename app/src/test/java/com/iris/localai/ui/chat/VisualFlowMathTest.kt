package com.iris.localai.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class VisualFlowMathTest {

    @Test
    fun `moving right shifts the scene left`() {
        val delta = VisualFlowMath.groundDelta(
            flowX = -10f,
            flowY = 0f,
            focal = 131f,
            depth = 1.35f,
            rightX = 1f,
            rightY = 0f,
            upX = 0f,
            upY = 1f,
        )
        assertEquals(0.072f, delta[0], 0.01f)
        assertEquals(0f, delta[1], 0.001f)
    }

    @Test
    fun `moving up shifts the scene down`() {
        val delta = VisualFlowMath.groundDelta(
            flowX = 0f,
            flowY = 10f,
            focal = 131f,
            depth = 1.35f,
            rightX = 1f,
            rightY = 0f,
            upX = 0f,
            upY = 1f,
        )
        assertEquals(0f, delta[0], 0.001f)
        assertEquals(0.072f, delta[1], 0.01f)
    }

    @Test
    fun `scale up lowers the camera height`() {
        val up = VisualFlowMath.heightDelta(1.05f, 1.35f)
        assertEquals(-0.064f, up, 0.01f)
        val down = VisualFlowMath.heightDelta(0.95f, 1.35f)
        assertEquals(0.071f, down, 0.01f)
    }

    @Test
    fun `similarity fit recovers known transform and rejects outliers`() {
        val scale = 1.02
        val rotation = 0.03
        val cos = kotlin.math.cos(rotation)
        val sin = kotlin.math.sin(rotation)
        val source = ArrayList<ScreenPoint>()
        val target = ArrayList<ScreenPoint>()
        for (index in 0 until 30) {
            val x = 20f + (index % 6) * 40f
            val y = 30f + (index / 6) * 50f
            source.add(ScreenPoint(x, y))
            val px = scale * (cos * x - sin * y) + 2.0
            val py = scale * (sin * x + cos * y) - 1.0
            target.add(ScreenPoint(px.toFloat(), py.toFloat()))
        }
        for (index in 0 until 3) {
            target[index * 7] = ScreenPoint(target[index * 7].x + 40f, target[index * 7].y - 30f)
        }
        val fit = VisualFlowMath.fitSimilarity(source, target)
        assertNotNull(fit)
        assertEquals(scale.toFloat(), fit!!.scale, 0.01f)
        assertEquals(rotation.toFloat(), fit.rotation, 0.005f)
        assertEquals(2f, fit.tx.toFloat(), 0.1f)
        assertEquals(-1f, fit.ty.toFloat(), 0.1f)
        assertEquals(27, fit.inliers)
    }
}
