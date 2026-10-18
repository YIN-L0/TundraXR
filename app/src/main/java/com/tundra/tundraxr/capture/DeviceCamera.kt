package com.tundra.tundraxr.capture

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

private const val TAG = "TundraCamera"

/**
 * The headset's world-facing camera. Passthrough is the viewfinder, so there is no preview:
 * the engineer just looks at the device. Photos and video land in app storage, ready to upload.
 */
class DeviceCamera(private val context: Context) {

    private var provider: ProcessCameraProvider? = null
    private var owner: LifecycleOwner? = null
    private var camera: Camera? = null
    private var selector = CameraSelector.DEFAULT_BACK_CAMERA
    private val imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    private val videoCapture = VideoCapture.withOutput(
        Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
            .build(),
    )
    /** False when the camera can't run photo and video at once; we then rebind per action. */
    private var concurrent = true
    private var recording: Recording? = null
    private var recordingDone: CompletableDeferred<File?>? = null

    var available = false
        private set

    private val mediaDir get() = File(context.filesDir, "captures").apply { mkdirs() }

    fun hasPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    suspend fun bind(lifecycleOwner: LifecycleOwner): Boolean {
        if (!hasPermission()) return false
        owner = lifecycleOwner
        val p: ProcessCameraProvider = runCatching { cameraProvider() }.getOrElse {
            Log.w(TAG, "No camera provider", it); return false
        }
        provider = p
        selector = listOf(CameraSelector.DEFAULT_BACK_CAMERA, CameraSelector.DEFAULT_FRONT_CAMERA)
            .firstOrNull { runCatching { p.hasCamera(it) }.getOrDefault(false) } ?: return false
        concurrent = bindUseCases(imageCapture, videoCapture)
        available = concurrent || bindUseCases(imageCapture)
        Log.i(TAG, "Camera ready=$available concurrent=$concurrent")
        return available
    }

    suspend fun takePhoto(): File? {
        if (!available) return null
        if (!concurrent && recording == null) bindUseCases(imageCapture)
        val file = File(mediaDir, "photo_${System.currentTimeMillis()}.jpg")
        return suspendCancellableCoroutine { cont ->
            imageCapture.takePicture(
                ImageCapture.OutputFileOptions.Builder(file).build(),
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) = cont.resume(file)
                    override fun onError(e: ImageCaptureException) {
                        Log.w(TAG, "Photo failed", e)
                        cont.resume(null)
                    }
                },
            )
        }
    }

    @SuppressLint("MissingPermission") // audio is only enabled after checking RECORD_AUDIO
    fun startRecording(): Boolean {
        if (!available || recording != null) return false
        if (!concurrent && !bindUseCases(videoCapture)) return false
        val file = File(mediaDir, "video_${System.currentTimeMillis()}.mp4")
        val done = CompletableDeferred<File?>()
        recordingDone = done
        val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        recording = videoCapture.output
            .prepareRecording(context, FileOutputOptions.Builder(file).build())
            .apply { if (hasMic) withAudioEnabled() }
            .start(ContextCompat.getMainExecutor(context)) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    if (event.hasError()) Log.w(TAG, "Recording finalized with error ${event.error}", event.cause)
                    // ERROR_SOURCE_INACTIVE etc. can still leave a playable file; keep it if it has data.
                    done.complete(file.takeIf { it.exists() && it.length() > 0 })
                }
            }
        return true
    }

    suspend fun stopRecording(): File? {
        val r = recording ?: return null
        recording = null
        r.stop()
        return recordingDone?.await().also { recordingDone = null }
    }

    /**
     * How the world camera maps directions to photo pixels, from Camera2's calibration. Falls
     * back to focal length over sensor size (centred, uncalibrated) when the device reports no
     * intrinsics; null when it reports neither, and region crops can't be placed.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    fun intrinsics(): CameraIntrinsics? {
        val info = camera?.let { Camera2CameraInfo.from(it.cameraInfo) } ?: return null
        val active = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        val rotation = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val w = active.width().toFloat()
        val h = active.height().toFloat()
        val k = info.getCameraCharacteristic(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
        if (k != null && k[0] > 0f && k[1] > 0f) {
            // Calibration is relative to the pre-correction array; photos cover the active one.
            val pre = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
            val dx = pre?.let { active.left - it.left } ?: 0
            val dy = pre?.let { active.top - it.top } ?: 0
            return RegionMath.upright(k[0] / w, k[1] / h, (k[2] - dx) / w, (k[3] - dy) / h, active.width(), active.height(), rotation, calibrated = true)
        }
        val focal = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val sensor = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        if (focal == null || sensor == null || focal <= 0f || sensor.width <= 0f) {
            Log.w(TAG, "Camera reports no intrinsics or focal length")
            return null
        }
        return RegionMath.upright(focal / sensor.width, focal / sensor.height, 0.5f, 0.5f, active.width(), active.height(), rotation, calibrated = false)
    }

    private suspend fun cameraProvider(): ProcessCameraProvider = suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            { runCatching { future.get() }.fold(cont::resume) { cont.resumeWith(Result.failure(it)) } },
            ContextCompat.getMainExecutor(context),
        )
    }

    private fun bindUseCases(vararg useCases: UseCase): Boolean {
        val p = provider ?: return false
        val o = owner ?: return false
        return runCatching {
            p.unbindAll()
            camera = p.bindToLifecycle(o, selector, *useCases)
        }.onFailure { Log.w(TAG, "Bind ${useCases.joinToString { it.javaClass.simpleName }} failed", it) }.isSuccess
    }
}
