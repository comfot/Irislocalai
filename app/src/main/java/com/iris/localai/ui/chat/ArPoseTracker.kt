package com.iris.localai.ui.chat

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.tan

/**
 * 手机姿态来源：优先旋转矢量（含磁力计），没有就退回游戏旋转矢量。
 * 世界坐标沿用 Android 约定：X 东、Y 北、Z 上。这里只提供 3DoF 姿态，没有平移，
 * 因此配合固定相机高度可以做出“转动手机时物体留在原地”的桌面锚定，但不是 SLAM。
 */
class ArPoseTracker(context: Context) {

    private val manager = context.applicationContext
        .getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor = manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: manager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    val available: Boolean = sensor != null

    /** 设备坐标到世界坐标的 3x3 旋转矩阵；null 表示尚未收到传感器数据。 */
    var rotation: FloatArray? by mutableStateOf(null)
        private set

    private var lastUpdateAt = 0L

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val now = System.currentTimeMillis()
            // 30Hz 左右足够让网格跟手，也避免每帧重排 WebView。
            if (now - lastUpdateAt < 33) return
            lastUpdateAt = now
            val matrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(matrix, event.values)
            rotation = matrix
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start() {
        if (sensor == null) return
        manager?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        manager?.unregisterListener(listener)
    }
}

/** 屏幕像素坐标，和 Compose 解耦，方便给投影数学写单元测试。 */
data class ScreenPoint(val x: Float, val y: Float)

/**
 * 相机固定在 (0, 0, cameraHeight)，地平面 z=0，世界 Z 轴向上。
 *
 * [rotation] 是设备坐标到世界坐标的旋转（world = R * device），所以相机基向量取 R 的列：
 * right = R * (1,0,0)，up = R * (0,1,0)，forward = R * (0,0,-1)（后置相机朝向）。
 */
class ArWorldProjector(
    private val width: Float,
    private val height: Float,
    private val cameraHeight: Float,
    rotation: FloatArray,
    private val cameraX: Float = 0f,
    private val cameraY: Float = 0f,
) {

    private val right = floatArrayOf(rotation[0], rotation[3], rotation[6])
    private val up = floatArrayOf(rotation[1], rotation[4], rotation[7])
    private val forward = floatArrayOf(-rotation[2], -rotation[5], -rotation[8])
    private val focal = (width / 2f) / tan(Math.toRadians(FOV_DEGREES / 2.0)).toFloat()

    /** 世界点投影到屏幕；在相机背后时返回 null。 */
    fun project(point: FloatArray): ScreenPoint? {
        val vx = point[0] - cameraX
        val vy = point[1] - cameraY
        val vz = point[2] - cameraHeight
        val depth = vx * forward[0] + vy * forward[1] + vz * forward[2]
        if (depth <= MIN_DEPTH) return null
        val x = vx * right[0] + vy * right[1] + vz * right[2]
        val y = vx * up[0] + vy * up[1] + vz * up[2]
        return ScreenPoint(
            x = width / 2f + focal * x / depth,
            y = height / 2f - focal * y / depth,
        )
    }

    /** 屏幕点反投影到地平面 z=0；相机没有朝下看时返回 null。 */
    fun groundPoint(screenX: Float, screenY: Float): FloatArray? {
        val x = (screenX - width / 2f) / focal
        val y = -(screenY - height / 2f) / focal
        val dirX = right[0] * x + up[0] * y + forward[0]
        val dirY = right[1] * x + up[1] * y + forward[1]
        val dirZ = right[2] * x + up[2] * y + forward[2]
        if (dirZ > -MIN_DOWN) return null
        val t = -cameraHeight / dirZ
        if (t <= 0f) return null
        return floatArrayOf(cameraX + dirX * t, cameraY + dirY * t, 0f)
    }

    /** 把保存的屏幕比例 (0.5, 0.82) 还原成地面锚点。 */
    fun groundFromFraction(fractionX: Float, fractionY: Float): FloatArray? =
        groundPoint(
            screenX = width * (0.5f + fractionX),
            screenY = height * (0.82f - fractionY),
        )

    companion object {
        /** 反编译版 AR 相机使用的水平视场角。 */
        const val FOV_DEGREES = 85.0
        private const val MIN_DEPTH = 0.15f
        private const val MIN_DOWN = 0.02f
    }
}
