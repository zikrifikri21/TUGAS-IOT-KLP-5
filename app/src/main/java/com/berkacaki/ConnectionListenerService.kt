package com.berkacaki

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener

private const val TAG = "BerkacaKi_Listener"

class ConnectionListenerService : Service() {

    private val db = getAppDatabase()
    private var connectRequestListener: ValueEventListener? = null
    private var firebaseConnectedListener: ValueEventListener? = null
    private lateinit var pin: String
    private var lastLaunchTime = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "ConnectionListenerService started")

        val prefs = getAppPreferences()
        pin = prefs.getString("saved_pin", null) ?: run {
            Log.e(TAG, "No saved PIN found, stopping service")
            stopSelf()
            return START_NOT_STICKY
        }

        // Start as a foreground service
        startForegroundCompat()

        // Set status to ready (so the viewer knows we are active in background)
        db.getReference("sessions/$pin/status").setValue("ready")
        db.getReference("sessions/$pin/status").onDisconnect().setValue("offline")

        // Listen for Firebase connection status changes to automatically reconnect/restore status
        listenToFirebaseConnection()

        // Listen for connection requests from the viewer
        listenForConnectRequests()

        return START_STICKY
    }

    private fun startForegroundCompat() {
        val channelId = "listener_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Layanan Latar Belakang BerkacaKi",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mendengarkan permintaan sambungan mirroring secara pasif"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }

        val openIntent = Intent(this, SourceActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("BerkacaKi Siap Menerima Sambungan")
            .setContentText("Menunggu viewer untuk menghubungkan...")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setAutoCancel(false)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                2,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                2,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
            )
        } else {
            startForeground(2, notification)
        }
    }

    private fun listenToFirebaseConnection() {
        if (firebaseConnectedListener != null) return
        val connectedRef = db.getReference(".info/connected")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) ?: false
                Log.d(TAG, "Firebase connection status: $connected")
                if (connected) {
                    val status = if (ScreenCaptureService.isRunning) "online" else "ready"
                    db.getReference("sessions/$pin/status").setValue(status)
                    db.getReference("sessions/$pin/status").onDisconnect().setValue("offline")
                    Log.d(TAG, "Successfully restored session status to '$status' after reconnection")
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Connection listener cancelled: ${error.message}")
            }
        }
        connectedRef.addValueEventListener(listener)
        firebaseConnectedListener = listener
    }

    private fun listenForConnectRequests() {
        if (connectRequestListener != null) return

        val requestRef = db.getReference("sessions/$pin/connect_request")
        
        // Ensure any old request is cleared on start
        requestRef.setValue(false)

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val requested = snapshot.getValue(Boolean::class.java) ?: false
                Log.d(TAG, "connect_request status changed: $requested")
                if (requested) {
                    val now = System.currentTimeMillis()
                    if (now - lastLaunchTime < 5000L) {
                        Log.d(TAG, "Ignore duplicate/spammed connection request")
                        requestRef.setValue(false)
                        return
                    }

                    if (ScreenCaptureService.isRunning) {
                        Log.d(TAG, "ScreenCaptureService is already running, skipping popup and ensuring online status")
                        requestRef.setValue(false)
                        db.getReference("sessions/$pin/status").setValue("online")
                        return
                    }

                    lastLaunchTime = now
                    Log.d(TAG, "Viewer requested connection! Launching BackgroundCaptureActivity...")
                    
                    // Reset the connect_request flag in firebase to avoid duplicate triggers
                    requestRef.setValue(false)

                    // Launch BackgroundCaptureActivity
                    val activityIntent = Intent(this@ConnectionListenerService, BackgroundCaptureActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra("pin", pin)
                    }
                    startActivity(activityIntent)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Error listening for connect requests: ${error.message}")
            }
        }

        requestRef.addValueEventListener(listener)
        connectRequestListener = listener
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "ConnectionListenerService destroyed")
        connectRequestListener?.let {
            db.getReference("sessions/$pin/connect_request").removeEventListener(it)
        }
        firebaseConnectedListener?.let {
            db.getReference(".info/connected").removeEventListener(it)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
