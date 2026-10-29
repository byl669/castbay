package com.weenas.castbay.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import com.weenas.castbay.MainActivity
import com.weenas.castbay.R
import com.weenas.castbay.util.Log

/**
 * Brings the app's screen up when a picture starts (mirroring, video) while the app is not on
 * screen: the TV on its home screen or in another app. Without it the sender shows "casting" and
 * the TV stays as it was. Android 10 and later only let an app start a screen from the background
 * with "Display over other apps" granted, so without it the person is told to open the app.
 */
object ScreenLauncher {
    private const val TAG = "CastBayScreen"

    /** MainActivity is on screen (between onStart and onStop). */
    @Volatile var visible = false

    /** Whether Android lets this app start its screen from the background. */
    fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(context)

    /** Opens the screen if it is not showing; must run on the main thread (the toast). */
    fun show(context: Context) {
        if (visible) return
        if (!allowed(context)) {
            Log.i(TAG, "Not on screen and not allowed to open it (Display over other apps)")
            Toast.makeText(context, R.string.open_on_cast_toast, Toast.LENGTH_LONG).show()
            return
        }
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            Log.i(TAG, "Opened the screen for a cast")
        } catch (e: Exception) {
            Log.w(TAG, "Could not open the screen: ${e.message}")
        }
    }
}
