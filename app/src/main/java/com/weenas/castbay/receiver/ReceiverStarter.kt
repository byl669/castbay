package com.weenas.castbay.receiver

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.weenas.castbay.service.AirPlayService
import com.weenas.castbay.service.ReceiverSettingsStore
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
        // Off in Settings (a debug build beside the release app starts with it off, so the two
        // don't fight over the ports): the app brings the receiver up when it is opened instead.
        if (!ReceiverSettingsStore(context).load().startOnBoot) {
            Diagnostics.record("start", "$why: start at boot is off")
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
