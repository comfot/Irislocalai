package com.iris.localai.ui.chat

import android.content.Context
import android.opengl.Matrix
import android.util.Log
import com.menthaar.sdk.MenthaAR
import java.io.File
import kotlin.math.max
import kotlin.math.sqrt

/** 一次 SLAM 位姿快照；矩阵为列主序 4x4，与 OpenGL 一致。 */
data class SlamPose(
    val model: FloatArray,
    val view: FloatArray,
    val projection: FloatArray,
    val tracked: Boolean,
    val hasPlane: Boolean,
    val mapId: Int,
    val timestampMs: Long,
)

/** 世界相机绑定：SLAM 的 world->camera 视图矩阵与投影矩阵。 */
data class ArWorldPose(
    val model: FloatArray,
    val view: FloatArray,
    val projection: FloatArray,
    val modelHeightMeters: Float,
    val yaw: Float,
    val cameraPosition: FloatArray,
    val cameraRotation: FloatArray,
    val anchorPosition: FloatArray,
    val anchorRotation: FloatArray,
    val fovY: Float,
    /** 定锚点时确定的竖直方向（地图坐标）：平面法向可能是竖面，不能直接当上方向。 */
    val worldUp: FloatArray,
)

private fun rotationQuaternion(
    m00: Float, m01: Float, m02: Float,
    m10: Float, m11: Float, m12: Float,
    m20: Float, m21: Float, m22: Float,
): FloatArray {
    val trace = m00 + m11 + m22
    return if (trace > 0f) {
        val s = kotlin.math.sqrt(trace + 1f) * 2f
        floatArrayOf((m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, 0.25f * s)
    } else if (m00 > m11 && m00 > m22) {
        val s = kotlin.math.sqrt(1f + m00 - m11 - m22) * 2f
        floatArrayOf(0.25f * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s)
    } else if (m11 > m22) {
        val s = kotlin.math.sqrt(1f + m11 - m00 - m22) * 2f
        floatArrayOf((m01 + m10) / s, 0.25f * s, (m12 + m21) / s, (m02 - m20) / s)
    } else {
        val s = kotlin.math.sqrt(1f + m22 - m00 - m11) * 2f
        floatArrayOf((m02 + m20) / s, (m12 + m21) / s, 0.25f * s, (m10 - m01) / s)
    }
}

fun cameraQuaternion(view: FloatArray): FloatArray = rotationQuaternion(
    view[0], view[1], view[2],
    view[4], view[5], view[6],
    view[8], view[9], view[10],
)

fun modelQuaternion(model: FloatArray): FloatArray = rotationQuaternion(
    model[0], model[4], model[8],
    model[1], model[5], model[9],
    model[2], model[6], model[10],
)

fun deviceQuaternion(rotation: FloatArray): FloatArray = rotationQuaternion(
    rotation[0], rotation[1], rotation[2],
    rotation[3], rotation[4], rotation[5],
    rotation[6], rotation[7], rotation[8],
)

/** 四元数乘法，参数与返回值均为 [x, y, z, w]。 */
fun quaternionMultiply(a: FloatArray, b: FloatArray): FloatArray {
    val ax = a[0]; val ay = a[1]; val az = a[2]; val aw = a[3]
    val bx = b[0]; val by = b[1]; val bz = b[2]; val bw = b[3]
    return floatArrayOf(
        aw * bx + ax * bw + ay * bz - az * by,
        aw * by - ax * bz + ay * bw + az * bx,
        aw * bz + ax * by - ay * bx + az * bw,
        aw * bw - ax * bx - ay * by - az * bz,
    )
}

fun quaternionInverse(q: FloatArray): FloatArray =
    floatArrayOf(-q[0], -q[1], -q[2], q[3])

/**
 * 把相机世界旋转增量写回视图矩阵的旋转块（平移不变）。
 * 世界旋转 R 对应的视图旋转是 R⁻¹，因此新视图旋转 = 旧视图旋转 · R⁻¹。
 */
fun viewRotatedBy(view: FloatArray, worldDelta: FloatArray): FloatArray {
    val raw = quaternionMultiply(cameraQuaternion(view), quaternionInverse(worldDelta))
    val length = sqrt(raw[0] * raw[0] + raw[1] * raw[1] + raw[2] * raw[2] + raw[3] * raw[3])
        .coerceAtLeast(0.0001f)
    val x = raw[0] / length
    val y = raw[1] / length
    val z = raw[2] / length
    val w = raw[3] / length
    val result = view.copyOf()
    result[0] = 1f - 2f * (y * y + z * z)
    result[1] = 2f * (x * y + z * w)
    result[2] = 2f * (x * z - y * w)
    result[4] = 2f * (x * y - z * w)
    result[5] = 1f - 2f * (x * x + z * z)
    result[6] = 2f * (y * z + x * w)
    result[8] = 2f * (x * z + y * w)
    result[9] = 2f * (y * z - x * w)
    result[10] = 1f - 2f * (x * x + y * y)
    return result
}

fun slamCameraPosition(view: FloatArray): FloatArray {
    val tx = view[12]
    val ty = view[13]
    val tz = view[14]
    return floatArrayOf(
        -(view[0] * tx + view[1] * ty + view[2] * tz),
        -(view[4] * tx + view[5] * ty + view[6] * tz),
        -(view[8] * tx + view[9] * ty + view[10] * tz),
    )
}

/**
 * 把 SLAM 投影转到屏幕坐标系。
 * MenthaAR 返回的投影可能按相机原始横向帧给出，也可能已按显示方向旋转：
 * 方形像素下 projection[0]/projection[5] ≈ 帧高/帧宽，小于 1 就是横向帧。
 * 判断出方向后先转到竖向 NDC，再按预览中心裁剪比例缩放，覆盖层才能贴在画面上。
 */
fun slamDisplayProjection(
    projection: FloatArray,
    previewWidth: Int,
    previewHeight: Int,
    screenWidth: Float,
    screenHeight: Float,
): FloatArray {
    if (previewWidth <= 0 || previewHeight <= 0 || screenWidth <= 0f || screenHeight <= 0f) {
        return projection.copyOf()
    }
    val landscape = projection[0] < projection[5]
    val displayWidth = (if (landscape) previewHeight else previewWidth).toFloat()
    val displayHeight = (if (landscape) previewWidth else previewHeight).toFloat()
    val cover = max(screenWidth / displayWidth, screenHeight / displayHeight)
    val ndc = FloatArray(16)
    ndc[0] = 1f
    ndc[5] = 1f
    ndc[10] = 1f
    ndc[15] = 1f
    if (landscape) {
        // 顺时针 90°：NDC 的 (x, y) → (y, -x)。
        ndc[0] = 0f; ndc[4] = 1f
        ndc[1] = -1f; ndc[5] = 0f
    }
    val kx = displayWidth * cover / screenWidth
    val ky = displayHeight * cover / screenHeight
    ndc[0] *= kx; ndc[4] *= kx
    ndc[1] *= ky; ndc[5] *= ky
    val result = FloatArray(16)
    Matrix.multiplyMM(result, 0, ndc, 0, projection, 0)
    return result
}

/** 用 MenthaAR 的 model/view/projection 投影模型空间点；失败或不在地面时返回 null。 */
class SlamProjector(
    private val width: Float,
    private val height: Float,
    pose: SlamPose,
    projection: FloatArray = pose.projection,
) {
    private val mvp = FloatArray(16)
    private val temp = FloatArray(16)

    init {
        Matrix.multiplyMM(temp, 0, pose.view, 0, pose.model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, temp, 0)
    }

    fun project(x: Float, y: Float, z: Float): ScreenPoint? {
        val clipX = mvp[0] * x + mvp[4] * y + mvp[8] * z + mvp[12]
        val clipY = mvp[1] * x + mvp[5] * y + mvp[9] * z + mvp[13]
        val clipW = mvp[3] * x + mvp[7] * y + mvp[11] * z + mvp[15]
        if (clipW <= 0.0001f) return null
        val ndcX = clipX / clipW
        val ndcY = clipY / clipW
        if (ndcX.isNaN() || ndcY.isNaN()) return null
        return ScreenPoint(
            x = (ndcX + 1f) * 0.5f * width,
            y = (1f - ndcY) * 0.5f * height,
        )
    }
}

/**
 * 实验性 6DoF 定位：把相机 Y 平面送进 MenthaAR（ORB-SLAM2），读取位姿矩阵。
 * 引擎在独立进程运行，应用侧只持有 SDK。
 */
class ArSlamTracker(context: Context) {

    private val appContext = context.applicationContext
    private val engine = MenthaAR(appContext)
    private val result = MenthaAR.Result()
    private var started = false
    private var lastRotation = -1
    private var lastNotifyAt = 0L
    private var lastPlaneCheckAt = 0L
    private var hasPlane = false
    private var resourceDir: File? = null
    private var anchorMapPath: File? = null
    private var lastReloadAt = 0L
    private var submittedFrames = 0
    private var projectionLogged = false
    private var lastSubmitLogAt = 0L
    private var lastNullFrameLogAt = 0L
    private var lastPoseLogAt = 0L

    var onPose: ((SlamPose) -> Unit)? = null

    val isAvailable: Boolean get() = started

    fun start() {
        if (started) return
        started = true
        runCatching {
            val dir = appContext.getExternalFilesDir("MenthaAR")
                ?: File(appContext.filesDir, "MenthaAR")
            dir.mkdirs()
            resourceDir = dir
            anchorMapPath = File(dir, "ar_anchor_map.bin")
            engine.setResourceDirectory(dir)
            engine.setPoseAssistEnabled(true)
            engine.setListener(object : MenthaAR.Listener {
                override fun onReady() {
                    Log.i(TAG, "SLAM SDK ready")
                }

                override fun onTrackingResult() {
                    readPose()
                }
            })
            engine.start()
            Log.i(TAG, "SLAM SDK start，资源目录=$dir")
        }.onFailure {
            Log.w(TAG, "SLAM 启动失败", it)
            started = false
        }
    }

    /** 保存当前地图与平面/AR 锚点，供切图后恢复。 */
    fun saveAnchorMap() {
        val path = anchorMapPath ?: return
        if (!started) return
        runCatching { engine.saveMap(path.absolutePath) }
            .onFailure { Log.w(TAG, "保存锚点地图失败", it) }
    }

    /** 切到新地图后把最初保存的地图/锚点重新加载回来。 */
    fun reloadAnchorMap(targetMapId: Int): Boolean {
        val path = anchorMapPath ?: return false
        if (!started || !path.isFile) return false
        val now = System.currentTimeMillis()
        if (now - lastReloadAt < 3000L) return false
        lastReloadAt = now
        return runCatching {
            // 加载到当前新地图 ID，而不是 0，避免 Tracking 立刻切回旧地图 ID。
            engine.loadMapWithId(path.absolutePath, targetMapId, false)
            Log.i(TAG, "切图后把锚点地图加载到地图$targetMapId：${path.absolutePath}")
            true
        }.getOrElse {
            Log.w(TAG, "重新加载锚点地图失败", it)
            false
        }
    }

    fun stop() {
        if (!started) return
        started = false
        onPose = null
        runCatching { engine.stop() }
    }

    fun submitFrame(gray: ByteArray, width: Int, height: Int, rotationDegrees: Int) {
        if (!started || width <= 0 || height <= 0) return
        runCatching {
            if (rotationDegrees != lastRotation) {
                lastRotation = rotationDegrees
                engine.setCameraRotation(rotationDegrees)
            }
            val frame = engine.beginFrame(width, height)
            if (frame == null) {
                val now = System.currentTimeMillis()
                if (now - lastNullFrameLogAt > 3000L) {
                    lastNullFrameLogAt = now
                    Log.w(TAG, "beginFrame 返回 null（服务未就绪/背压/共享内存失败） ${width}x$height")
                }
                return
            }
            val count = minOf(gray.size, frame.gray.capacity())
            frame.gray.clear()
            frame.gray.put(gray, 0, count)
            val submitted = engine.submitFrame(frame)
            submittedFrames++
            val now = System.currentTimeMillis()
            if (submittedFrames <= 3 || now - lastSubmitLogAt > 3000L) {
                lastSubmitLogAt = now
                Log.d(TAG, "submitFrame #$submittedFrames ${width}x$height accepted=$submitted")
            }
        }.onFailure {
            Log.w(TAG, "submitFrame 异常", it)
        }
    }

    private fun readPose() {
        if (!engine.readResult(result)) return
        if (!projectionLogged && result.projection[0] != 0f) {
            projectionLogged = true
            // fx/fy 的比值决定投影是竖向还是横向帧，AR 覆盖层据此决定是否旋转 90°。
            Log.i(
                TAG,
                "projection fx=${result.projection[0]} fy=${result.projection[5]} " +
                    "landscape=${result.projection[0] < result.projection[5]}",
            )
        }
        val now = System.currentTimeMillis()
        if (now - lastPlaneCheckAt > 1000L) {
            lastPlaneCheckAt = now
            hasPlane = runCatching { engine.getMapStats().hasPlane }.getOrDefault(false)
            if (!hasPlane) runCatching { engine.detectPlane() }
        }
        val pose = SlamPose(
            model = result.model.copyOf(),
            view = result.view.copyOf(),
            projection = result.projection.copyOf(),
            tracked = result.isTracked && result.drawable,
            hasPlane = hasPlane,
            mapId = result.mapId,
            timestampMs = now,
        )
        if (now - lastPoseLogAt > 2000L) {
            lastPoseLogAt = now
            Log.d(
                TAG,
                "pose frame=${result.frameId} state=${result.trackingState} " +
                    "drawable=${result.drawable} hasPlane=$hasPlane",
            )
        }
        if (now - lastNotifyAt < 33L) return
        lastNotifyAt = now
        onPose?.invoke(pose)
    }

    private companion object {
        const val TAG = "ArSlamTracker"
    }
}
