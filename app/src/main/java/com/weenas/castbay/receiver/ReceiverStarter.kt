package com.weenas.castbay.receiver

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.weenas.castbay.BuildConfig
import com.weenas.castbay.service.AirPlayService
import com.weenas.castbay.util.Diagnostics
import com.weenas.castbay.util.Log

/**
 * Starts the receiver from the background (a boot, a car waking, a network job) unless it is
 * already up, and records how that went on the Diagnostics screen: Android 12 and later refuse
 * some background starts of a foreground service.
 */
object ReceiverStarter {
    private const val TAG = "CastBayStart"

    fun start(context: Context, why: String) {
        if (AirPlayService.isReceiving) {
            Diagnostics.record("start", "$why: already receiving")
            return
        }
        // A debug build beside the release app waits to be opened for a test.
        if (BuildConfig.DEBUG) {
            Diagnostics.record("start", "$why: debug build, not started")
            return
        }
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AirPlayService::class.java).setAction(AirPlayService.ACTION_START)
            )
            Diagnostics.record("start", "$why: starting the receiver")
            Log.i(TAG, "Starting the receiver ($why)")
        } catch (error: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException (Android 12+). Android 15+ refusing
            // media playback at boot comes later, in the service (AirPlayService.enterForeground).
            Diagnostics.record("start", "$why: not allowed (${error.javaClass.simpleName}: ${error.message?.take(80)})")
            Log.w(TAG, "Not allowed to start the receiver ($why)", error)
        }
    }
}
