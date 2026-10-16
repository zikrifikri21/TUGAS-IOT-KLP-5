package com.berkacaki

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

private const val TAG = "BerkacaKi_BgCapture"

class BackgroundCaptureActivity : AppCompatActivity() {
    private lateinit var pin: String

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        Log.d(TAG, "Background capture dialog result: resultCode=${result.resultCode}")
        if (result.resultCode == Activity.RESULT_OK) {
            val intent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra("pin", pin)
                putExtra("resultCode", result.resultCode)
                putExtra("data", result.data)
            }
            startForegroundService(intent)
            Log.d(TAG, "ScreenCaptureService started from background activity with pin=$pin")

            val statusRef = getAppDatabase().getReference("sessions/$pin/status")
            statusRef.setValue("online")
                .addOnSuccessListener { Log.d(TAG, "Status 'online' saved under sessions/$pin/status") }
                .addOnFailureListener { e -> Log.e(TAG, "Failed to set status online: ${e.message}", e) }
            statusRef.onDisconnect().setValue("offline")
        } else {
            Log.w(TAG, "Screen capture permission rejected by user or cancelled")
        }
        // Finish this translucent activity immediately
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pin = intent.getStringExtra("pin") ?: run {
            Log.e(TAG, "No pin passed to BackgroundCaptureActivity, finishing")
            finish()
            return
        }

        Log.d(TAG, "BackgroundCaptureActivity created for pin=$pin")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        if (ScreenCaptureService.isRunning) {
            Log.d(TAG, "ScreenCaptureService is already running, finishing BackgroundCaptureActivity immediately")
            finish()
            return
        }

        requestScreenCaptureAfterKeyguard()
    }

    private fun requestScreenCaptureAfterKeyguard() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            requestScreenCapture()
            return
        }

        val keyguardManager = getSystemService(KeyguardManager::class.java)
        if (keyguardManager?.isKeyguardLocked != true) {
            requestScreenCapture()
            return
        }

        Log.d(TAG, "Device is locked, requesting keyguard dismissal...")
        keyguardManager.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {
                Log.d(TAG, "Keyguard dismissed successfully, requesting screen capture...")
                requestScreenCapture()
            }

            override fun onDismissError() {
                Log.w(TAG, "Failed to dismiss keyguard (requires manual unlock), but trying anyway...")
                requestScreenCapture()
            }

            override fun onDismissCancelled() {
                Log.w(TAG, "Keyguard dismissal cancelled by user, finishing")
                finish()
            }
        })
    }

    private fun requestScreenCapture() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        projectionLauncher.launch(captureIntent)
    }
}
