package com.sample.videocamerasample

import android.content.Context
import android.util.AttributeSet
import android.util.DisplayMetrics
import android.view.TextureView

/**
 * A [TextureView] that can be adjusted to a specified aspect ratio.
 */
class AutoFitTextureView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyle: Int = 0) :
    TextureView(context, attrs, defStyle) {

    private var mResolutionWidth = 0
    private var mResolutionHeight = 0
    private var deviceWidth: Int = 0
    private var deviceHeight: Int = 0

    fun setDeviceDimension(displayMetrics: DisplayMetrics) {
        deviceHeight = displayMetrics.heightPixels
        deviceWidth = displayMetrics.widthPixels
    }

    /**
     * Sets the aspect ratio for this view. The size of the view will be measured based on the ratio
     * calculated from the parameters. Note that the actual sizes of parameters don't matter, that
     * is, calling setAspectRatio(2, 3) and setAspectRatio(4, 6) make the same result.
     *
     * @param width  Relative horizontal size
     * @param height Relative vertical size
     */
    fun setAspectRatio(width: Int, height: Int) {
        if (width < 0 || height < 0) {
            throw IllegalArgumentException("Size cannot be negative.")
        }
        mResolutionWidth = width
        mResolutionHeight = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (0 == mResolutionWidth || 0 == mResolutionHeight) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        } else {
            if (mResolutionWidth > mResolutionHeight) {
                val h = ((deviceWidth * mResolutionWidth) / mResolutionHeight)
                if (h < deviceHeight) {
                    val w = deviceHeight * mResolutionHeight / mResolutionWidth
                    setMeasuredDimension(w, deviceHeight)
                } else {
                    setMeasuredDimension(deviceWidth, h)
                }
            }
        }
    }
}
