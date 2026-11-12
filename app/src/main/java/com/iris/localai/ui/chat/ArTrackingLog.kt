package com.iris.localai.ui.chat

import android.content.Context
import java.io.BufferedWriter
import java.io.File

/**
 * 把 AR 视觉跟踪的逐帧调试数据写成 CSV，便于用真机数据校准单应分解和解的符号。
 * 文件写入 `Android/data/<pkg>/files/tracking/ar-tracking-<时间戳>.csv`。
 */
class ArTrackingLog(private val context: Context) {

    private var writer: BufferedWriter? = null
    private var file: File? = null

    val active: Boolean get() = writer != null

    fun start(): File? {
        if (writer != null) return file
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(root, "tracking").apply { mkdirs() }
        val target = File(dir, "ar-tracking-${System.currentTimeMillis()}.csv")
        val handle = target.bufferedWriter()
        handle.appendLine(CSV_HEADER)
        handle.flush()
        writer = handle
        file = target
        return target
    }

    fun append(row: String) {
        val handle = writer ?: return
        synchronized(this) {
            runCatching {
                handle.appendLine(row)
                handle.flush()
            }
        }
    }

    fun stop(): File? {
        synchronized(this) {
            runCatching { writer?.close() }
            writer = null
        }
        return file.also { file = null }
    }

    companion object {
        const val CSV_HEADER =
            "timestamp_ms,width,height,rotation_deg,fov_deg,cam_h_set,cam_h_est,cam_x,cam_y," +
                "imu_r0,imu_r1,imu_r2,imu_r3,imu_r4,imu_r5,imu_r6,imu_r7,imu_r8," +
                "tracked,inliers,scale,img_rot_deg," +
                "affine_a,affine_b,affine_tx,affine_c,affine_d,affine_ty," +
                "h00,h01,h02,h10,h11,h12,h20,h21,h22," +
                "dx,dy,dh,points,note"
    }
}
