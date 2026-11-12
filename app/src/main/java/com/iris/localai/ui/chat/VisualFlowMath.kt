package com.iris.localai.ui.chat

import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 光流到位移的换算与符号约定（与 OpenCV 解耦，方便单测）。
 *
 * 图像坐标 x 向右、y 向下；相机右向量/上向量投影到地面后作为位移基。
 * 相机向前/向右移动时，画面特征向相反方向移动，因此 x 方向取负；
 * 图像 y 向下与相机 up 相反，所以 y 方向取正。
 */
object VisualFlowMath {

    private const val GAIN = 0.7f

    fun groundDelta(
        flowX: Float,
        flowY: Float,
        focal: Float,
        depth: Float,
        rightX: Float,
        rightY: Float,
        upX: Float,
        upY: Float,
    ): FloatArray {
        val scale = depth / focal
        val rightLength = hypot(rightX, rightY).coerceAtLeast(0.05f)
        val upLength = hypot(upX, upY).coerceAtLeast(0.05f)
        return floatArrayOf(
            (-flowX * scale * rightX / rightLength + flowY * scale * upX / upLength) * GAIN,
            (-flowX * scale * rightY / rightLength + flowY * scale * upY / upLength) * GAIN,
        )
    }

    /** 画面尺度变大（scale > 1）表示相机靠近地面，高度应减小。 */
    fun heightDelta(scale: Float, cameraHeight: Float): Float {
        if (scale <= 0.01f) return 0f
        return (cameraHeight / scale - cameraHeight).coerceIn(-0.08f, 0.08f)
    }

    data class Similarity(
        val a: Double,
        val b: Double,
        val tx: Double,
        val c: Double,
        val d: Double,
        val ty: Double,
        val inliers: Int,
        val total: Int,
    ) {
        val scale: Float get() = hypot(a, c).toFloat()
        val rotation: Float get() = atan2(c, a).toFloat()
    }

    /**
     * 带一轮剔除的相似变换拟合：先对全部点做最小二乘，再保留残差小于
     * [threshold] 的点重拟合一次。比依赖 RANSAC 的 estimateAffinePartial2D
     * 更适合这里已经由光流状态过滤过的稀疏点。
     */
    fun fitSimilarity(
        source: List<ScreenPoint>,
        target: List<ScreenPoint>,
        threshold: Float = 3f,
    ): Similarity? {
        val count = minOf(source.size, target.size)
        if (count < 3) return null
        val random = kotlin.random.Random(0x51A7)
        var best: Similarity? = null
        var bestInliers = 0
        var bestMask: BooleanArray? = null
        repeat(40) {
            val first = random.nextInt(count)
            var second = random.nextInt(count)
            if (second == first) second = (second + 1) % count
            var third = random.nextInt(count)
            while (third == first || third == second) third = (third + 1) % count
            val candidate = fitOnce(source, target, listOf(first, second, third)) ?: return@repeat
            val mask = BooleanArray(count)
            var inliers = 0
            for (index in 0 until count) {
                if (residual(candidate, source[index], target[index]) <= threshold) {
                    mask[index] = true
                    inliers++
                }
            }
            if (inliers > bestInliers) {
                bestInliers = inliers
                best = candidate
                bestMask = mask
            }
        }
        val params = best ?: return null
        val mask = bestMask ?: return params.copy(inliers = 0, total = count)
        if (bestInliers < 3) return params.copy(inliers = bestInliers, total = count)
        val refined = fitOnce(
            source,
            target,
            (0 until count).filter { mask[it] },
        ) ?: params
        var inliers = 0
        for (index in 0 until count) {
            if (residual(refined, source[index], target[index]) <= threshold) inliers++
        }
        return refined.copy(inliers = inliers, total = count)
    }

    private fun residual(
        similarity: Similarity,
        source: ScreenPoint,
        target: ScreenPoint,
    ): Double {
        val x = source.x.toDouble()
        val y = source.y.toDouble()
        val px = similarity.a * x - similarity.b * y + similarity.tx
        val py = similarity.b * x + similarity.a * y + similarity.ty
        return hypot(px - target.x, py - target.y)
    }

    private fun fitOnce(
        source: List<ScreenPoint>,
        target: List<ScreenPoint>,
        indices: Iterable<Int>,
    ): Similarity? {
        val matrix = Array(4) { DoubleArray(4) }
        val vector = DoubleArray(4)
        var used = 0
        for (index in indices) {
            val x = source[index].x.toDouble()
            val y = source[index].y.toDouble()
            val px = target[index].x.toDouble()
            val py = target[index].y.toDouble()
            accumulate(matrix, vector, doubleArrayOf(x, -y, 1.0, 0.0), px)
            accumulate(matrix, vector, doubleArrayOf(y, x, 0.0, 1.0), py)
            used++
        }
        if (used < 3) return null
        val solution = solve4(matrix, vector) ?: return null
        val a = solution[0]
        val b = solution[1]
        val tx = solution[2]
        val ty = solution[3]
        return Similarity(a, b, tx, b, a, ty, inliers = 0, total = used)
    }

    private fun accumulate(matrix: Array<DoubleArray>, vector: DoubleArray, row: DoubleArray, value: Double) {
        for (i in 0 until 4) {
            vector[i] += row[i] * value
            for (j in 0 until 4) matrix[i][j] += row[i] * row[j]
        }
    }

    private fun solve4(matrix: Array<DoubleArray>, vector: DoubleArray): DoubleArray? {
        val augmented = Array(4) { index -> DoubleArray(5) { column ->
            if (column < 4) matrix[index][column] else vector[index]
        } }
        for (column in 0 until 4) {
            var pivot = column
            for (row in column + 1 until 4) {
                if (kotlin.math.abs(augmented[row][column]) > kotlin.math.abs(augmented[pivot][column])) {
                    pivot = row
                }
            }
            if (kotlin.math.abs(augmented[pivot][column]) < 1e-9) return null
            val swap = augmented[column]
            augmented[column] = augmented[pivot]
            augmented[pivot] = swap
            val divisor = augmented[column][column]
            for (j in column..4) augmented[column][j] /= divisor
            for (row in 0 until 4) {
                if (row == column) continue
                val factor = augmented[row][column]
                if (factor == 0.0) continue
                for (j in column..4) augmented[row][j] -= factor * augmented[column][j]
            }
        }
        return DoubleArray(4) { augmented[it][4] }
    }
}
