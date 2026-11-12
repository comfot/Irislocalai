package com.iris.localai.ui.chat

import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.calib3d.Calib3d
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.acos
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.tan

data class VisualMotion(val dx: Float, val dy: Float, val dHeight: Float)

/** 供「导出跟踪日志」使用的单帧调试数据。 */
data class FlowDebug(
    val tracked: Int,
    val inliers: Int,
    val scale: Float,
    val imageRotation: Float,
    val affine: DoubleArray?,
    val homography: DoubleArray?,
    val dx: Float,
    val dy: Float,
    val dHeight: Float,
    val points: FloatArray,
    val note: String = "",
)

/**
 * 稀疏光流位移跟踪（实验性）。
 *
 * 输入灰度帧与 IMU 姿态，输出相机在地面上的世界位移。只做帧间平移估计：
 * - 姿态变化过大时跳过该帧，避免把旋转当成平移；
 * - 相机接近水平（看不到地面）时不估计；
 * - 位移有增益与单帧上限，漂移时用「重新定位」清零。
 */
class VisualFlowTracker {

    val available: Boolean by lazy {
        runCatching { OpenCVLoader.initLocal() }.getOrDefault(false)
    }

    private var previous: Mat? = null
    private var previousPoints: MatOfPoint2f? = null
    private var previousRotation: FloatArray? = null
    private var yuvMat: Mat? = null
    private var rotatedMat: Mat? = null

    /** 打开后额外计算单应并保存 [lastDebug]，用于导出校准数据。 */
    var captureDebug: Boolean = false
    var lastDebug: FlowDebug? = null
        private set

    fun reset() {
        previous?.release()
        previous = null
        previousPoints?.release()
        previousPoints = null
        previousRotation = null
        yuvMat?.release()
        yuvMat = null
        rotatedMat?.release()
        rotatedMat = null
    }

    /** 直接处理 Camera2 的 Y 平面，按 [rotationDegrees] 顺时针旋转到屏幕方向。 */
    fun updateYuv(
        yPlane: ByteArray,
        width: Int,
        height: Int,
        rotationDegrees: Int,
        rotation: FloatArray,
        cameraHeight: Float,
        horizontalFov: Float,
    ): VisualMotion? {
        if (!available || width <= 0 || height <= 0) return null
        val source = yuvMat?.takeIf { it.cols() == width && it.rows() == height }
            ?: Mat(height, width, CvType.CV_8UC1).also {
                yuvMat?.release()
                yuvMat = it
            }
        source.put(0, 0, yPlane)
        // 旋转后的图像宽度对应传感器高度，焦距必须在旋转前按原始宽度算。
        val focalPx = (width / 2f) /
            tan(Math.toRadians(horizontalFov / 2.0)).toFloat()
        val normalized = ((rotationDegrees % 360) + 360) % 360
        val oriented = if (normalized == 0) {
            source
        } else {
            val code = when (normalized) {
                90 -> Core.ROTATE_90_CLOCKWISE
                180 -> Core.ROTATE_180
                else -> Core.ROTATE_90_COUNTERCLOCKWISE
            }
            val swap = normalized == 90 || normalized == 270
            val targetCols = if (swap) source.rows() else source.cols()
            val targetRows = if (swap) source.cols() else source.rows()
            val target = rotatedMat
                ?.takeIf { it.cols() == targetCols && it.rows() == targetRows }
                ?: Mat().also {
                    rotatedMat?.release()
                    rotatedMat = it
                }
            Core.rotate(source, target, code)
            target
        }
        return update(oriented, rotation, cameraHeight, focalPx)
    }

    /** 返回相机世界位移与高度变化；无法可靠估计时返回 null。 */
    fun update(
        gray: Mat,
        rotation: FloatArray,
        cameraHeight: Float,
        focalPx: Float,
    ): VisualMotion? {
        if (!available) return null
        lastDebug = null
        val prev = previous
        val prevPoints = previousPoints
        if (prev == null || prevPoints == null || prevPoints.empty()) {
            store(gray, rotation)
            return null
        }
        val lastRotation = previousRotation
        if (lastRotation != null && rotationAngle(lastRotation, rotation) > MAX_ROTATION_RAD) {
            store(gray, rotation)
            return null
        }

        val nextPoints = MatOfPoint2f()
        val status = MatOfByte()
        val error = MatOfFloat()
        try {
            Video.calcOpticalFlowPyrLK(
                prev,
                gray,
                prevPoints,
                nextPoints,
                status,
                error,
                Size(21.0, 21.0),
                3,
            )
            val from = prevPoints.toArray()
            val to = nextPoints.toArray()
            val statuses = status.toArray()
            val errors = error.toArray()
            val flowX = ArrayList<Float>()
            val flowY = ArrayList<Float>()
            val sourcePoints = ArrayList<Point>()
            val targetPoints = ArrayList<Point>()
            for (index in from.indices) {
                if (statuses.getOrNull(index)?.toInt() != 1) continue
                if ((errors.getOrNull(index) ?: Float.MAX_VALUE) > MAX_ERROR) continue
                val dx = (to[index].x - from[index].x).toFloat()
                val dy = (to[index].y - from[index].y).toFloat()
                if (abs(dx) > MAX_FLOW || abs(dy) > MAX_FLOW) continue
                flowX.add(dx)
                flowY.add(dy)
                sourcePoints.add(from[index])
                targetPoints.add(to[index])
            }
            if (flowX.size < MIN_INLIERS) {
                store(gray, rotation)
                return null
            }
            val focal = focalPx.coerceAtLeast(1f)

            // RANSAC 仿射：平移、图像旋转与尺度一起解，尺度用于更新相机高度。
            val sourceMat = MatOfPoint2f(*sourcePoints.toTypedArray())
            val targetMat = MatOfPoint2f(*targetPoints.toTypedArray())
            val debugHomography = if (captureDebug) {
                runCatching {
                    Calib3d.findHomography(sourceMat, targetMat, Calib3d.RANSAC, 3.0)
                }.getOrNull()
            } else {
                null
            }
            val debugPoints = if (captureDebug) pointPreview(sourcePoints, targetPoints) else FloatArray(0)
            val sourceScreen = sourcePoints.map { ScreenPoint(it.x.toFloat(), it.y.toFloat()) }
            val targetScreen = targetPoints.map { ScreenPoint(it.x.toFloat(), it.y.toFloat()) }
            val fit = VisualFlowMath.fitSimilarity(sourceScreen, targetScreen, 3f)
            var note = ""
            if (fit == null) {
                note = "fit-null"
            } else if (fit.inliers < MIN_INLIERS) {
                note = "inliers=${fit.inliers}"
            } else {
                val scale = fit.scale
                val imageRotation = fit.rotation
                if (scale !in 0.94f..1.06f) {
                    note = String.format(java.util.Locale.US, "scale=%.3f", scale)
                } else if (abs(imageRotation) > MAX_IMAGE_ROTATION) {
                    note = String.format(java.util.Locale.US, "rot=%.3f", imageRotation)
                } else {
                    val delta = flowToGround(
                        medianX = fit.tx.toFloat(),
                        medianY = fit.ty.toFloat(),
                        focal = focal,
                        rotation = rotation,
                        cameraHeight = cameraHeight,
                    )
                    if (delta == null) {
                        note = "no-ground"
                    } else {
                        val dHeight = VisualFlowMath.heightDelta(scale, cameraHeight)
                        if (captureDebug) {
                            lastDebug = FlowDebug(
                                tracked = flowX.size,
                                inliers = fit.inliers,
                                scale = scale,
                                imageRotation = imageRotation,
                                affine = doubleArrayOf(fit.a, fit.b, fit.tx, fit.c, fit.d, fit.ty),
                                homography = debugHomography?.get(0, 0),
                                dx = delta[0],
                                dy = delta[1],
                                dHeight = dHeight,
                                points = debugPoints,
                                note = "ok",
                            )
                        }
                        sourceMat.release()
                        targetMat.release()
                        debugHomography?.release()
                        store(gray, rotation)
                        return VisualMotion(delta[0], delta[1], dHeight)
                    }
                }
            }
            sourceMat.release()
            targetMat.release()
            debugHomography?.release()

            val medianX = flowX.sorted()[flowX.size / 2]
            val medianY = flowY.sorted()[flowY.size / 2]
            val delta = flowToGround(
                medianX = medianX,
                medianY = medianY,
                focal = focal,
                rotation = rotation,
                cameraHeight = cameraHeight,
            )
            store(gray, rotation)
            if (captureDebug) {
                lastDebug = FlowDebug(
                    tracked = flowX.size,
                    inliers = fit?.inliers ?: 0,
                    scale = fit?.scale ?: 1f,
                    imageRotation = fit?.rotation ?: 0f,
                    affine = fit?.let { doubleArrayOf(it.a, it.b, it.tx, it.c, it.d, it.ty) },
                    homography = null,
                    dx = delta?.get(0) ?: 0f,
                    dy = delta?.get(1) ?: 0f,
                    dHeight = 0f,
                    points = debugPoints,
                    note = note.ifEmpty { "median" },
                )
            }
            return delta?.let { VisualMotion(it[0], it[1], 0f) }
        } catch (_: Throwable) {
            reset()
            return null
        } finally {
            nextPoints.release()
            status.release()
            error.release()
        }
    }

    private fun pointPreview(source: List<Point>, target: List<Point>): FloatArray {
        val count = minOf(40, source.size, target.size)
        val out = FloatArray(count * 4)
        for (index in 0 until count) {
            out[index * 4] = source[index].x.toFloat()
            out[index * 4 + 1] = source[index].y.toFloat()
            out[index * 4 + 2] = target[index].x.toFloat()
            out[index * 4 + 3] = target[index].y.toFloat()
        }
        return out
    }

    private fun store(gray: Mat, rotation: FloatArray) {
        previous?.release()
        previous = gray.clone()
        previousRotation = rotation.copyOf()
        previousPoints?.release()
        previousPoints = detect(gray)
    }

    private fun detect(gray: Mat): MatOfPoint2f {
        val corners = MatOfPoint()
        Imgproc.goodFeaturesToTrack(
            gray,
            corners,
            MAX_FEATURES,
            FEATURE_QUALITY,
            MIN_DISTANCE,
            Mat(),
            3,
            false,
            0.04,
        )
        val points = MatOfPoint2f(*corners.toArray())
        corners.release()
        return points
    }

    private fun flowToGround(
        medianX: Float,
        medianY: Float,
        focal: Float,
        rotation: FloatArray,
        cameraHeight: Float,
    ): FloatArray? {
        // 世界 = R * 设备；相机基向量取 R 的列，forward = -Z 设备。
        val rightX = rotation[0]
        val rightY = rotation[3]
        val upX = rotation[1]
        val upY = rotation[4]
        val forwardZ = -rotation[8]
        if (forwardZ > -MIN_DOWN) return null
        val depth = cameraHeight / -forwardZ
        if (depth <= 0.2f || depth > 6f) return null
        val delta = VisualFlowMath.groundDelta(
            flowX = medianX,
            flowY = medianY,
            focal = focal,
            depth = depth,
            rightX = rightX,
            rightY = rightY,
            upX = upX,
            upY = upY,
        )
        val length = hypot(delta[0], delta[1])
        if (length > MAX_STEP) {
            delta[0] *= MAX_STEP / length
            delta[1] *= MAX_STEP / length
        }
        return delta
    }

    private fun rotationAngle(a: FloatArray, b: FloatArray): Float {
        var trace = 0f
        for (index in 0 until 9) trace += a[index] * b[index]
        val cosine = ((trace - 1f) / 2f).coerceIn(-1f, 1f)
        return acos(cosine)
    }

    private companion object {
        const val MAX_FEATURES = 120
        const val FEATURE_QUALITY = 0.01
        const val MIN_DISTANCE = 8.0
        const val MIN_INLIERS = 12
        const val MAX_ERROR = 30f
        const val MAX_FLOW = 40f
        const val MAX_ROTATION_RAD = 0.035f
        const val MIN_DOWN = 0.35f
        const val MAX_STEP = 0.25f
        const val MAX_IMAGE_ROTATION = 0.035f
    }
}
