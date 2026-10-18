package com.tundra.tundraxr.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Log
import androidx.xr.runtime.math.Pose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "TundraRegion"

/** Cuts the framed region out of a full world-camera photo, as a new upright JPEG beside it. */
object RegionCrop {

    suspend fun crop(photo: File, frame: RegionFrame, head: Pose, k: CameraIntrinsics): File? = withContext(Dispatchers.IO) {
        runCatching {
            val raw = BitmapFactory.decodeFile(photo.path) ?: return@runCatching null
            val upright = upright(raw, exifRotation(photo))
            val rect = RegionMath.crop(frame, head, k, upright.width, upright.height) ?: return@runCatching null
            val x = (rect.left * upright.width).toInt()
            val y = (rect.top * upright.height).toInt()
            val w = ((rect.right - rect.left) * upright.width).toInt().coerceIn(1, upright.width - x)
            val h = ((rect.bottom - rect.top) * upright.height).toInt().coerceIn(1, upright.height - y)
            val out = File(photo.parentFile, photo.nameWithoutExtension + "_region.jpg")
            out.outputStream().use { Bitmap.createBitmap(upright, x, y, w, h).compress(Bitmap.CompressFormat.JPEG, 92, it) }
            Log.i(TAG, "Region ${w}x$h of ${upright.width}x${upright.height} calibrated=${k.calibrated}")
            out
        }.onFailure { Log.w(TAG, "Crop failed", it) }.getOrNull()
    }

    private fun exifRotation(file: File) = when (
        ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    ) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }

    private fun upright(bitmap: Bitmap, degrees: Int): Bitmap =
        if (degrees == 0) bitmap
        else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
}
