package com.sample.videocamerasample.common

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.media.RingtoneManager
import android.os.Environment
import android.util.Size
import android.util.SparseIntArray
import android.view.Surface
import androidx.core.content.PermissionChecker
import androidx.fragment.app.FragmentActivity
import java.io.File
import java.util.concurrent.TimeUnit

const val FILE_PATH = "file_path"
const val REQUEST_VIDEO_PERMISSIONS = 1
val VIDEO_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
const val VIDEO_RECORDING_TIME: Long = 50 * 1000
const val SENSOR_ORIENTATION_DEFAULT_DEGREES = 90
const val SENSOR_ORIENTATION_INVERSE_DEGREES = 270
val DEFAULT_ORIENTATIONS = SparseIntArray().apply {
    append(Surface.ROTATION_0, 90)
    append(Surface.ROTATION_90, 0)
    append(Surface.ROTATION_180, 270)
    append(Surface.ROTATION_270, 180)
}
val INVERSE_ORIENTATIONS = SparseIntArray().apply {
    append(Surface.ROTATION_0, 270)
    append(Surface.ROTATION_90, 180)
    append(Surface.ROTATION_180, 90)
    append(Surface.ROTATION_270, 0)
}

object CameraUtils {

    private var mCameraManager: CameraManager? = null
    private var mCameraLensFacing: String = "0"

    /**
     * Initialize camera manager for further use case in util class
     */
    fun init(cameraManager: CameraManager?) {
        mCameraManager = cameraManager
    }

    /**
     * Set camera lens facing
     */
    fun setCameraLensFacing(cameraLensFacing: String) {
        mCameraLensFacing = cameraLensFacing
    }

    /**
     * Get camera lens facing
     */
    fun getCameraLensFacing(): String {
        return mCameraLensFacing
    }

    /*
     * @return true if a flash is available, false if not
     */
    fun isFlashAvailable(context: Context?): Boolean {
        return context?.packageManager?.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH) ?: false
    }

    /**
     * Get camera characteristics
     * @param id: Types of characteristics
     */
    fun getCameraCharacteristics(id: String): CameraCharacteristics? {
        return mCameraManager?.getCameraCharacteristics(id)
    }

    fun isFlashSupported(): Boolean {
        return getCameraCharacteristics(mCameraLensFacing)?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
    }

    /**
     * Get camera front facing id from list of camera supported
     * If camera facing null then default to back
     */
    fun getFrontFacingCameraId(): String? {
        for (camID in mCameraManager!!.cameraIdList) {
            val lensFacing = getCameraCharacteristics(camID)?.get(CameraCharacteristics.LENS_FACING)!!
            if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
                mCameraLensFacing = camID
                break
            }
        }
        return mCameraLensFacing
    }

    /**
     * Get camera back facing id from list of camera supported
     * If camera facing null then default to back
     */
    fun getBackFacingCameraId(): String? {
        for (camID in mCameraManager!!.cameraIdList) {
            val lensFacing = getCameraCharacteristics(camID)?.get(CameraCharacteristics.LENS_FACING)!!
            if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                mCameraLensFacing = camID
                break
            }
        }
        return mCameraLensFacing
    }

    /**
     * Get file path for video
     */
    fun getVideoFilePath(): String {
        val filename = "JS_${System.currentTimeMillis()}.mp4"
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), filename)
        return if (dir.exists()) {
            dir.absolutePath
        } else {
            dir.createNewFile()
            dir.absolutePath
        }
    }

    /**
     * Remove video file from storage
     */
    fun removeVideoFilePath(path: String?): Boolean {
        if (path == null) {
            return false
        }
        return File(path).delete()
    }

    /**
     * Play sound for timer
     */
    fun playTimerSound(context: Context?) {
        try {
            val notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val r = RingtoneManager.getRingtone(context, notification)
            r.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * In this sample, we choose a video size with 3x4 aspect ratio. Also, we don't use sizes
     * larger than 1080p, since MediaRecorder cannot handle such a high-resolution video.
     * @return The video size
     */
    fun getVideoSize(): Size {
        val map =
            getCameraCharacteristics(mCameraLensFacing)?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: throw RuntimeException("Cannot get available preview/video sizes")
        val choices: Array<Size> = map.getOutputSizes(MediaRecorder::class.java)
        return choices.firstOrNull {
            it.width == it.height * 4 / 3 && it.width <= 1080
        } ?: choices[choices.size - 1]
    }

    /**
     * chooses the smallest one whose
     * width and height are at least as large as the respective requested values, and whose aspect
     * ratio matches with the specified value.
     * @param width       The minimum desired width
     * @param height      The minimum desired height
     * @return The optimal [Size], or an arbitrary one if none were big enough
     **/
    fun chooseOptimalSize(
        width: Int,
        height: Int,
        orientation: Int
    ): Size {
        val map =
            getCameraCharacteristics(mCameraLensFacing)?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: throw RuntimeException("Cannot get available preview/video sizes")
        val choices: Array<Size> = map.getOutputSizes(MediaRecorder::class.java)
        for (size in choices) {
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                if ((size.width / 16) == (size.height / 9) && size.width <= 720) {
                    return size
                } else if ((size.width / 16) == (size.height / 9) && size.width <= 3840) {
                    return size
                } else if ((size.width / 16) == (size.height / 9) && size.width <= 5120) {//Retina 5K
                    return size
                } else if ((size.width / 16) == (size.height / 9) && size.width <= 7680) {//8K UHDTV Super Hi-Vision
                    return size
                }
            } else {
                if ((size.width / 16) == (size.height / 9) && ((size.width <= 1280) || (size.height <= 1920))) {
                    return size
                } else if ((size.width / 16) == (size.height / 9) && (size.width <= 2160)) {
                    return size
                } else if ((size.width / 16) == (size.height / 9) && (size.width <= 2880)) {//Retina 5K
                    return size
                } else if ((size.width / 18) == (size.height / 9) && ((size.width <= 3840) || (size.height <= 2160))) {
                    return size
                } else if ((size.width / 18.5) == (size.height / 9).toDouble() && ((size.width <= 3840) || (size.height <= 2160))) {
                    return size
                } else if ((width / 19) == (height / 9) && ((width <= 3840) || (height <= 2160))) {
                    return size
                } else if ((size.width / 19.5) == (size.height / 9).toDouble() && ((size.width <= 3840) || (size.height <= 2160))) {
                    return size
                } else if ((size.width / 16) == (size.height / 9) && (size.width <= 4320)) {//8K UHDTV Super Hi-Vision
                    return size
                } else if ((size.width / 16) == (size.height / 9) && (size.width in 1280..4480)) {
                    return size
                }
            }
        }
        return choices[0]
    }

    fun hasPermissionsGranted(
        permissions: Array<String>,
        activity: FragmentActivity?
    ) =
        permissions.none {
            PermissionChecker.checkSelfPermission(
                (activity as FragmentActivity),
                it
            ) != PackageManager.PERMISSION_GRANTED
        }

    fun prepareTimerText(millisLeft: Long): String {
        return String.format(
            "%02d :%02d ",
            TimeUnit.MILLISECONDS.toMinutes(millisLeft),
            TimeUnit.MILLISECONDS.toSeconds(millisLeft)
                    - TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(millisLeft))
        )
    }
}