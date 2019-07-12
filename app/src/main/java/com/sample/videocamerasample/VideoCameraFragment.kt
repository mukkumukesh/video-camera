package com.sample.videocamerasample

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.MediaRecorder
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import android.util.Size
import android.view.*
import android.widget.Toast
import androidx.core.animation.addListener
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.sample.videocamerasample.common.*
import com.sample.videocamerasample.common.CameraUtils.chooseOptimalSize
import com.sample.videocamerasample.common.CameraUtils.getCameraCharacteristics
import com.sample.videocamerasample.common.CameraUtils.getCameraLensFacing
import com.sample.videocamerasample.common.CameraUtils.getVideoFilePath
import com.sample.videocamerasample.common.CameraUtils.getVideoSize
import com.sample.videocamerasample.common.CameraUtils.hasPermissionsGranted
import com.sample.videocamerasample.common.CameraUtils.isFlashAvailable
import com.sample.videocamerasample.common.CameraUtils.isFlashSupported
import com.sample.videocamerasample.common.CameraUtils.prepareTimerText
import com.sample.videocamerasample.common.CountDownTimer
import kotlinx.android.synthetic.main.fragment_video_camera.*
import java.io.IOException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

private const val FRAGMENT_DIALOG = "dialog"

open class VideoCameraFragment : Fragment(), View.OnClickListener,
    ActivityCompat.OnRequestPermissionsResultCallback {

    // Flash torch
    private var isTorchOn: Boolean = false

    // Camera Manager
    private var cameraManager: CameraManager? = null

    private var isPaused: Boolean = false
    private var countDownTimer: CountDownTimer? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private lateinit var previewSize: Size
    private lateinit var videoSize: Size
    private var isRecordingVideo = false

    /**
     * An additional thread for running tasks that shouldn't block the UI.
     */
    private var backgroundThread: HandlerThread? = null

    /**
     * A [Handler] for running tasks in the background.
     */
    private var backgroundHandler: Handler? = null

    /**
     * A [Semaphore] to prevent the app from exiting before closing the camera.
     */
    private val cameraOpenCloseLock = Semaphore(1)

    /**
     * [CaptureRequest.Builder] for the camera preview
     */
    private lateinit var previewRequestBuilder: CaptureRequest.Builder

    /**
     * Orientation of the sensor
     */
    private var sensorOrientation = 0

    /**
     * Output file for video file path
     */
    private var absolutePath: String? = null
    private var mediaRecorder: MediaRecorder? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            absolutePath = it.getString(FILE_PATH)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_video_camera, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        textureView.also {
            val displayMetrics = DisplayMetrics()
            activity!!.windowManager.defaultDisplay.getMetrics(displayMetrics)
            it.setDeviceDimension(displayMetrics)
        }
        btnRecordOn.also { it.setOnClickListener(this) }
        btnRecordOff.also { it.setOnClickListener(this) }
        btnFlashLight.also { it.setOnClickListener(this) }
        btnSwitchCamera.also { it.setOnClickListener(this) }
        btnPlayPauseTimer.also { it.setOnClickListener(this) }
        cameraManager = activity?.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        CameraUtils.init(cameraManager)
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        prepareTextureViewToOpenCamera()
    }

    override fun onPause() {
        if (isRecordingVideo) {
            CameraUtils.removeVideoFilePath(absolutePath).also { absolutePath = null }
            if (activity != null) showToast("Video Removed:")
        }
        isTorchOn = false
        turnOffTorch()
        stopRecordingVideo()
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    /**
     * Set view visibility
     * @param view: View which want to set visibility status
     * @param visibility: View visibility status ( VISIBLE, GONE, INVISIBLE etc..)
     */
    private fun setViewVisibility(view: View, visibility: Int) {
        view.visibility = visibility
    }

    /**
     * Set video play pause btn visibility
     */
    private fun handleBtnPlayPauseTimer() {
        if (isRecordingVideo) {
            setViewVisibility(btnPlayPauseTimer, View.VISIBLE)
            if (!isPaused) {
                btnPlayPauseTimer.setImageResource(R.drawable.ic_pause_timer)
            } else {
                btnPlayPauseTimer.setImageResource(R.drawable.ic_play_timer)
            }
        } else {
            setViewVisibility(btnPlayPauseTimer, View.GONE)
        }
    }

    /**
     * Switch camera button handling
     * If recording is in progress then btnSwitchCamera visible otherwise gone
     * Also trigger the play pause timer function to handle timer view
     */
    private fun handleBtnSwitchCamera() {
        if (!isRecordingVideo) {
            setViewVisibility(btnSwitchCamera, View.VISIBLE)
        } else {
            setViewVisibility(btnSwitchCamera, View.GONE)
        }
        if (getCameraLensFacing() == "0") {
            btnSwitchCamera.setImageResource(R.drawable.ic_camera_toggle)
        } else {
            btnSwitchCamera.setImageResource(R.drawable.ic_camera_toggle)
        }
        handleBtnPlayPauseTimer()
    }

    /**
     * Starts a background thread and its [Handler].
     */
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground")
        backgroundThread?.start()
        backgroundHandler = Handler(backgroundThread?.looper)
    }

    /**
     * Stops the background thread and its [Handler].
     */
    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, e.toString())
        }
    }

    /*
    When the screen is turned off and turned back on, the SurfaceTexture is already
    available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
    a camera and start preview from here (otherwise, we wait until the surface is ready in
    the SurfaceTextureListener).
    */
    private fun prepareTextureViewToOpenCamera() {
        if (textureView.isAvailable) {
            openCamera(textureView.width, textureView.height)
        } else {
            textureView.surfaceTextureListener = surfaceTextureListener
        }
    }

    @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
    @SuppressLint("MissingPermission")
    private fun openCamera(width: Int, height: Int) {
        if (!hasPermissionsGranted(VIDEO_PERMISSIONS, activity)) {
            requestVideoPermissions()
            return
        }
        if (activity == null || activity!!.isFinishing) return
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            setupFlashButton()
            val characteristics = getCameraCharacteristics(getCameraLensFacing())
            sensorOrientation = characteristics!!.get(CameraCharacteristics.SENSOR_ORIENTATION)
            // Video resolution
            videoSize = getVideoSize()
            // Video preview with optimal size
            previewSize = chooseOptimalSize(width, height, resources.configuration.orientation)
            // Set aspect ratio for texture view
            textureView.setAspectRatio(previewSize.width, previewSize.height)
            configureTransform(width, height)
            mediaRecorder = MediaRecorder()
            cameraManager!!.openCamera(getCameraLensFacing(), stateCallback, null)
        } catch (e: CameraAccessException) {
            showToast("Cannot access the camera.")
            activity!!.finish()
        } catch (e: NullPointerException) {
            ErrorDialog.newInstance(getString(R.string.camera_error))
                .show(childFragmentManager, FRAGMENT_DIALOG)
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera opening.")
        }
    }

    /**
     * Gets whether you should show UI with rationale for requesting permissions.
     * @param permissions The permissions your app wants to request.
     * @return Whether you can show permission rationale UI.
     */
    private fun shouldShowRequestPermissionRationale(permissions: Array<String>) =
        permissions.any { shouldShowRequestPermissionRationale(it) }

    /**
     * Requests permissions needed for recording video.
     */
    private fun requestVideoPermissions() {
        if (shouldShowRequestPermissionRationale(VIDEO_PERMISSIONS)) {
            ConfirmationDialog().show(childFragmentManager, FRAGMENT_DIALOG)
        } else {
            requestPermissions(
                VIDEO_PERMISSIONS,
                REQUEST_VIDEO_PERMISSIONS
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_VIDEO_PERMISSIONS) {
            if (grantResults.size == VIDEO_PERMISSIONS.size) {
                for (result in grantResults) {
                    if (result != PackageManager.PERMISSION_GRANTED) {
                        ErrorDialog.newInstance(getString(R.string.permission_request))
                            .show(childFragmentManager, FRAGMENT_DIALOG)
                        break
                    }
                }
            } else {
                ErrorDialog.newInstance(getString(R.string.permission_request))
                    .show(childFragmentManager, FRAGMENT_DIALOG)
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    /**
     * [TextureView.SurfaceTextureListener] handles several lifecycle events on a
     * [TextureView].
     */
    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {

        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            openCamera(width, height)
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            configureTransform(width, height)
        }

        override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture) = true

        override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit

    }

    /**
     * [CameraDevice.StateCallback] is called when [CameraDevice] changes its status.
     */
    private val stateCallback = object : CameraDevice.StateCallback() {

        override fun onOpened(cameraDevice: CameraDevice) {
            cameraOpenCloseLock.release()
            this@VideoCameraFragment.cameraDevice = cameraDevice
            startPreview()
            configureTransform(textureView.width, textureView.height)
            showRecordingTime(prepareTimerText(VIDEO_RECORDING_TIME))
        }

        override fun onDisconnected(cameraDevice: CameraDevice) {
            cameraOpenCloseLock.release()
            cameraDevice.close()
            this@VideoCameraFragment.cameraDevice = null
        }

        override fun onError(cameraDevice: CameraDevice, error: Int) {
            cameraOpenCloseLock.release()
            cameraDevice.close()
            this@VideoCameraFragment.cameraDevice = null
            activity?.finish()
        }

    }

    override fun onClick(view: View) {
        enableView(false)
        when (view.id) {
            R.id.btnFlashLight -> {
                switchTorchMode()
            }
            R.id.btnRecordOn -> {
                startRecordingVideo()
            }
            R.id.btnRecordOff -> {
                stopRecordingVideo()
            }
            R.id.btnSwitchCamera -> switchCamera()
            R.id.btnPlayPauseTimer -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    isPaused = !isPaused
                    if (isRecordingVideo && isPaused) {
                        mediaRecorder?.pause()
                        countDownTimer?.pause()
                    } else {
                        mediaRecorder?.resume()
                        countDownTimer?.resume()
                    }
                    handleBtnPlayPauseTimer()
                }
                enableView(true)
            }
        }
    }

    private fun enableView(enable: Boolean) {
        btnRecordOn.isEnabled = enable
        btnRecordOff.isEnabled = enable
        btnSwitchCamera.isEnabled = enable
        btnPlayPauseTimer.isEnabled = enable
    }

    /**
     * Switch camera if available otherwise nothing else
     */
    private fun switchCamera() {
        closeCamera()
        animate()
    }

    private fun animate() {
        val fadeOut = ObjectAnimator.ofFloat(textureView, "alpha", 1f, 0.3f)
        fadeOut.duration = 600
        fadeOut.addListener({
            changeLensFacingMode()
        }, {
            cameraControlCotainer.alpha = 0f
            txvTimer.alpha = 0f
        })
        val fadeIn = ObjectAnimator.ofFloat(textureView, "alpha", 0.3f, 1f)
        fadeIn.duration = 600
        val animator = AnimatorSet()
        animator.addListener({
            cameraControlCotainer.alpha = 1.0f
            txvTimer.alpha = 1.0f
            enableView(true)
        })
        animator.playSequentially(fadeOut, fadeIn)
        animator.start()
    }

    /**
     * Toggle to new camera facing mode if available
     */
    private fun changeLensFacingMode() {
        if (getCameraLensFacing() == "0") {
            CameraUtils.getFrontFacingCameraId()
        } else {
            CameraUtils.getBackFacingCameraId()
        }
        prepareTextureViewToOpenCamera()
    }

    private fun setupFlashButton() {
        if (isFlashAvailable(activity) && isFlashSupported()) {
            setViewVisibility(btnFlashLight, View.VISIBLE)
            if (isTorchOn) {
                btnFlashLight.setImageResource(R.drawable.ic_flash_on)
            } else {
                btnFlashLight.setImageResource(R.drawable.ic_flash_off)
            }
        } else {
            setViewVisibility(btnFlashLight, View.GONE)
        }
    }

    /**
     * Switch torch mode (On, Off)
     */
    private fun switchTorchMode() {
        try {
            if (getCameraLensFacing() == "0") {
                if (isFlashAvailable(activity) && isFlashSupported()) {
                    isTorchOn = if (isTorchOn) {
                        turnOffTorch()
                    } else {
                        turnOnTorch()
                    }
                }
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
        enableView(true)
    }

    private fun turnOnTorch(): Boolean {
        previewRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
        captureSession?.setRepeatingRequest(previewRequestBuilder.build(), null, null)
        btnFlashLight.setImageResource(R.drawable.ic_flash_on)
        return true
    }

    private fun turnOffTorch(): Boolean {
        previewRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
        captureSession?.setRepeatingRequest(previewRequestBuilder.build(), null, null)
        btnFlashLight.setImageResource(R.drawable.ic_flash_off)
        return false
    }

    /**
     * Close the [CameraDevice].
     */
    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            closePreviewSession()
            cameraDevice?.close()
            cameraDevice = null
            mediaRecorder?.release()
            mediaRecorder = null
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    /**
     * Start the camera preview.
     */
    private fun startPreview() {
        if (cameraDevice == null || !textureView.isAvailable) return

        try {
            closePreviewSession()
            val texture = textureView.surfaceTexture
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)

            val previewSurface = Surface(texture)
            previewRequestBuilder.addTarget(previewSurface)

            cameraDevice?.createCaptureSession(
                listOf(previewSurface),
                object : CameraCaptureSession.StateCallback() {

                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        updatePreview()
                        updateViewOnMainThread()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (activity != null) showToast("Failed")
                        updateViewOnMainThread()
                    }
                }, backgroundHandler
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
            updateViewOnMainThread()
        }

    }

    private fun updateViewOnMainThread() {
        Handler(Looper.getMainLooper()).post { enableView(true) }
    }

    /**
     * Update the camera preview. [startPreview] needs to be called in advance.
     */
    private fun updatePreview() {
        if (cameraDevice == null) return

        try {
            setUpCaptureRequestBuilder(previewRequestBuilder)
            HandlerThread("CameraPreview").start()
            captureSession?.setRepeatingRequest(
                previewRequestBuilder.build(),
                null, backgroundHandler
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }

    }

    private fun setUpCaptureRequestBuilder(builder: CaptureRequest.Builder?) {
        builder?.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
    }

    /**
     * Configures the necessary [android.graphics.Matrix] transformation to `textureView`.
     * This method should not to be called until the camera preview size is determined in
     * openCamera, or until the size of `textureView` is fixed.
     *
     * @param viewWidth  The width of `textureView`
     * @param viewHeight The height of `textureView`
     */
    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        activity ?: return
        val rotation = (activity as FragmentActivity).windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = Math.max(
                viewHeight.toFloat() / previewSize.height,
                viewWidth.toFloat() / previewSize.width
            )
            with(matrix) {
                postScale(scale, scale, centerX, centerY)
                postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
            }
        }
        textureView.setTransform(matrix)
    }

    @Throws(IOException::class)
    private fun setUpMediaRecorder() {
        val cameraActivity = activity ?: return

        if (absolutePath.isNullOrEmpty()) {
            absolutePath = getVideoFilePath()
        }

        val rotation = cameraActivity.windowManager.defaultDisplay.rotation
        when (sensorOrientation) {
            SENSOR_ORIENTATION_DEFAULT_DEGREES ->
                mediaRecorder?.setOrientationHint(DEFAULT_ORIENTATIONS.get(rotation))
            SENSOR_ORIENTATION_INVERSE_DEGREES ->
                mediaRecorder?.setOrientationHint(INVERSE_ORIENTATIONS.get(rotation))
        }

        mediaRecorder?.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(absolutePath)
            setVideoEncodingBitRate(10000000)
            setVideoFrameRate(30)
            setVideoSize(videoSize.width, videoSize.height)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            prepare()
        }
    }

    private fun startRecordingVideo() {
        if (cameraDevice == null || !textureView.isAvailable) return

        try {
            closePreviewSession()
            setUpMediaRecorder()
            val texture = textureView.surfaceTexture.apply {
                setDefaultBufferSize(previewSize.width, previewSize.height)
            }

            // Set up Surface for camera preview and MediaRecorder
            val previewSurface = Surface(texture)
            val recorderSurface = mediaRecorder!!.surface
            val surfaces = ArrayList<Surface>().apply {
                add(previewSurface)
                add(recorderSurface)
            }
            previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(previewSurface)
                addTarget(recorderSurface)
            }

            // Start a capture session
            // Once the session starts, we can update the UI and start recording
            cameraDevice?.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {

                    override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                        captureSession = cameraCaptureSession
                        updatePreview()
                        activity?.runOnUiThread {
                            isRecordingVideo = true
                            recordingBtnUpdate()
                            handleBtnSwitchCamera()
                            mediaRecorder?.start()
                            startRecordingTimer()
                            enableView(true)
                        }
                    }

                    override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                        enableView(true)
                    }
                }, backgroundHandler
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        } catch (e: IllegalStateException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        }

    }

    private fun recordingBtnUpdate() {
        if (isRecordingVideo) {
            setViewVisibility(btnRecordOn, View.GONE)
            setViewVisibility(btnRecordOff, View.VISIBLE)
        } else {
            setViewVisibility(btnRecordOn, View.VISIBLE)
            setViewVisibility(btnRecordOff, View.GONE)
        }
    }

    private fun closePreviewSession() {
        captureSession?.close()
        captureSession = null
    }

    private fun stopRecordingVideo() {
        switchTorchMode()
        stopRecordingTimer()
        if (isRecordingVideo) {
            isRecordingVideo = false
            recordingBtnUpdate()
            handleBtnSwitchCamera()
            try {
                mediaRecorder?.apply {
                    stop()
                    reset()
                }
            } catch (e: Exception) {
                if (CameraUtils.removeVideoFilePath(absolutePath)) {
                    absolutePath = null
                    if (activity != null) showToast("Video Removed:")
                }
            }
            if (absolutePath != null)
                if (activity != null) showToast("Video saved: $absolutePath")
            startPreview()
        }
    }

    private fun startRecordingTimer() {
        setupCountDown()
        setViewVisibility(txvTimer, View.VISIBLE)
        countDownTimer?.start()
    }

    private fun stopRecordingTimer() {
        countDownTimer?.cancel()
        showRecordingTime(prepareTimerText(VIDEO_RECORDING_TIME))
    }

    private fun showToast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()

    // function setup countdown timer for video recording
    private fun setupCountDown() {
        countDownTimer = object : CountDownTimer(VIDEO_RECORDING_TIME, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                showRecordingTime(prepareTimerText(millisUntilFinished))
                val sec = TimeUnit.MILLISECONDS.toSeconds(millisUntilFinished) - TimeUnit.MINUTES.toSeconds(
                    TimeUnit.MILLISECONDS.toMinutes(millisUntilFinished)
                )
                if (sec < 3)
                    CameraUtils.playTimerSound(activity)
            }

            override fun onFinish() {
                stopRecordingVideo()
            }
        }
    }

    // Set recording time
    private fun showRecordingTime(duration: String) {
        if (txvTimer.visibility == View.INVISIBLE
            || txvTimer.visibility == View.GONE
        ) {
            setViewVisibility(txvTimer, View.VISIBLE)
        }
        txvTimer.text = duration
    }

    companion object {
        const val TAG = "VideoCameraFagment"
        fun newInstance(path: String = ""): VideoCameraFragment =
            VideoCameraFragment().apply {
                arguments = Bundle().apply {
                    putString(FILE_PATH, path)
                }
            }
    }
}
