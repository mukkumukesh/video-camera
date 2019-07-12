package com.sample.videocamerasample

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.sample.videocamerasample.common.CameraUtils


class VideoCameraActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_camera)
        savedInstanceState ?: supportFragmentManager.beginTransaction()
            .replace(
                R.id.videoFrame,
                VideoCameraFragment.newInstance(),
                VideoCameraFragment.TAG
            )
            .commit()
    }

    /**
     * Get file path for video
     */
    private fun getVideoFilePath(): String {
        val filename = "${System.currentTimeMillis()}.mp4"
        val dir = this.getExternalFilesDir(null)
        return if (dir == null) {
            filename
        } else {
            "${dir.absolutePath}/$filename"
        }
    }
}
