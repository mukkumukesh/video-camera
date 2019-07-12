package com.sample.videocamerasample.common

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Message
import android.os.SystemClock


abstract class CountDownTimer(millisInFuture: Long, countDownInterval: Long) {
    /**
     * Millis since epoch when alarm should stop.
     */
    private var mMillisInFuture: Long = millisInFuture

    /**
     * The interval in millis that the user receives callbacks
     */
    private var mCountdownInterval: Long = countDownInterval

    private var mStopTimeInFuture: Long = 0

    private var mPauseTime: Long = 0

    private var mCancelled = false

    private var mPaused = false

    /**
     * Cancel the countdown.
     *
     * Do not call it from inside CountDownTimer threads
     */
    fun cancel() {
        mHandler.removeMessages(mMSG)
        mCancelled = true
    }

    /**
     * Start the countdown.
     */
    @Synchronized
    fun start(): CountDownTimer {
        if (mMillisInFuture <= 0) {
            onFinish()
            return this
        }
        mStopTimeInFuture = SystemClock.elapsedRealtime() + mMillisInFuture
        mHandler.sendMessage(mHandler.obtainMessage(mMSG))
        mCancelled = false
        mPaused = false
        return this
    }

    /**
     * Pause the countdown.
     */
    fun pause(): Long {
        mPauseTime = mStopTimeInFuture - SystemClock.elapsedRealtime()
        mPaused = true
        return mPauseTime
    }

    /**
     * Resume the countdown.
     */
    fun resume(): Long {
        mStopTimeInFuture = mPauseTime + SystemClock.elapsedRealtime()
        mPaused = false
        mHandler.sendMessage(mHandler.obtainMessage(mMSG))
        return mPauseTime
    }

    /**
     * Callback fired on regular interval.
     * @param millisUntilFinished The amount of time until finished.
     */
    abstract fun onTick(millisUntilFinished: Long)

    /**
     * Callback fired when the time is up.
     */
    abstract fun onFinish()


    private val mMSG = 1


    // handles counting down
    private val mHandler = @SuppressLint("HandlerLeak")
    object : Handler() {

        override fun handleMessage(msg: Message) {
            synchronized(this@CountDownTimer) {
                if (!mPaused) {
                    val millisLeft = mStopTimeInFuture - SystemClock.elapsedRealtime()

                    if (millisLeft <= 0) {
                        onFinish()
                    } else if (millisLeft < mCountdownInterval) {
                        // no tick, just delay until done
                        sendMessageDelayed(obtainMessage(mMSG), millisLeft)
                    } else {
                        val lastTickStart = SystemClock.elapsedRealtime()
                        onTick(millisLeft)

                        // take into account user's onTick taking time to execute
                        var delay = lastTickStart + mCountdownInterval - SystemClock.elapsedRealtime()

                        // special case: user's onTick took more than interval to
                        // complete, skip to next interval
                        while (delay < 0) delay += mCountdownInterval

                        if (!mCancelled) {
                            sendMessageDelayed(obtainMessage(mMSG), delay)
                        }
                    }
                }
            }
        }
    }
}