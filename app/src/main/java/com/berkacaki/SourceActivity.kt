package com.berkacaki

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener

private const val TAG = "BerkacaKi_Source"

class SourceActivity : AppCompatActivity() {
    private lateinit var pin: String

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        Log.d(TAG, "Hasil izin screen capture: resultCode=${result.resultCode}")
        if (result.resultCode == Activity.RESULT_OK) {
            val intent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra("pin", pin)
                putExtra("resultCode", result.resultCode)
                putExtra("data", result.data)
            }
            startForegroundService(intent)
            Log.d(TAG, "ScreenCaptureService dimulai dengan pin=$pin")

            val statusRef = getAppDatabase().getReference("sessions/$pin/status")
            statusRef.setValue("online")
                .addOnSuccessListener { Log.d(TAG, "Status 'online' tersimpan di sessions/$pin/status") }
                .addOnFailureListener { e -> Log.e(TAG, "Gagal set status online: ${e.message}", e) }
            statusRef.onDisconnect().setValue("offline")
            updateBroadcastUI(isBroadcasting = true)
        } else {
            Log.w(TAG, "Izin screen capture ditolak user")
            findViewById<TextView>(R.id.tvPin).text = "Izin ditolak, coba lagi"
            updateBroadcastUI(isBroadcasting = false, reason = "Izin Ditolak")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        setContentView(R.layout.activity_source)

        val prefs = getAppPreferences()
        pin = prefs.getString("saved_pin", null) ?: run {
            val newPin = (100000..999999).random().toString()
            prefs.edit().putString("saved_pin", newPin).apply()
            Log.d(TAG, "PIN baru digenerate: $newPin")
            newPin
        }
        Log.d(TAG, "Menggunakan pin=$pin")
        findViewById<TextView>(R.id.tvPin).text = pin

        // Ensure ConnectionListenerService is running
        val serviceIntent = Intent(this, ConnectionListenerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val fromBoot = intent.getBooleanExtra("from_boot", false)
        Log.d(TAG, "SourceActivity dibuka, fromBoot=$fromBoot")

        if (!fromBoot && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = getSystemService(NotificationManager::class.java)
            if (!nm.canUseFullScreenIntent()) {
                val settingsIntent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(settingsIntent)
            }
        }

        // Overlay permission configuration
        findViewById<Button>(R.id.btnOverlayPermission).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(this)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                }
            }
        }

        // Accessibility permission configuration
        findViewById<Button>(R.id.btnAccessibilityPermission).setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        // Hide App Icon switch configuration
        val swHideIcon = findViewById<Switch>(R.id.swHideIcon)
        val aliasName = ComponentName(this, "com.berkacaki.LauncherAlias")
        try {
            val currentEnabledSetting = packageManager.getComponentEnabledSetting(aliasName)
            swHideIcon.isChecked = currentEnabledSetting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } catch (e: Exception) {
            Log.e(TAG, "Error checking LauncherAlias state: ${e.message}")
        }

        swHideIcon.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                packageManager.setComponentEnabledSetting(
                    aliasName,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
                Log.d(TAG, "Ikon aplikasi disembunyikan (LauncherAlias disabled)")
            } else {
                packageManager.setComponentEnabledSetting(
                    aliasName,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                Log.d(TAG, "Ikon aplikasi ditampilkan kembali (LauncherAlias enabled)")
            }
        }

        findViewById<Button>(R.id.btnStopBroadcast).setOnClickListener {
            stopScreenBroadcasting()
        }

        findViewById<Button>(R.id.btnStartBroadcast).setOnClickListener {
            startScreenBroadcasting()
        }

        listenToSessionStatus()
        updateBroadcastUI(ScreenCaptureService.isRunning)

        if (!ScreenCaptureService.isRunning) {
            requestScreenCaptureAfterKeyguard()
        } else {
            Log.d(TAG, "ScreenCaptureService is already running, skipping initial screen capture request")
        }
    }

    override fun onResume() {
        super.onResume()
        updateOverlayPermissionUI()
        updateAccessibilityPermissionUI()
        updateBroadcastUI(ScreenCaptureService.isRunning)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedId = ComponentName(this, MirrorAutoAcceptService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        return enabledServices.contains(expectedId) || enabledServices.contains(packageName)
    }

    private fun updateAccessibilityPermissionUI() {
        val btnAccess = findViewById<Button>(R.id.btnAccessibilityPermission)
        if (isAccessibilityServiceEnabled()) {
            btnAccess.text = "Klik Otomatis Sudah Aktif"
            btnAccess.isEnabled = false
        } else {
            btnAccess.text = "Aktifkan Klik Otomatis"
            btnAccess.isEnabled = true
        }
    }

    private fun updateOverlayPermissionUI() {
        val btnOverlay = findViewById<Button>(R.id.btnOverlayPermission)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(this)) {
                btnOverlay.text = "Izin Sudah Aktif"
                btnOverlay.isEnabled = false
            } else {
                btnOverlay.text = "Berikan Izin Hamparan"
                btnOverlay.isEnabled = true
            }
        } else {
            btnOverlay.text = "Izin Sudah Aktif"
            btnOverlay.isEnabled = false
        }
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

        Log.d(TAG, "Layar masih terkunci, minta sistem lepas keyguard dulu sebelum minta izin capture")
        keyguardManager.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {
                Log.d(TAG, "Keyguard berhasil dilepas, lanjut minta izin capture")
                requestScreenCapture()
            }

            override fun onDismissError() {
                Log.w(TAG, "Gagal melepas keyguard otomatis (device pakai PIN/pola/biometrik), tetap coba minta izin capture")
                requestScreenCapture()
            }

            override fun onDismissCancelled() {
                Log.w(TAG, "User membatalkan proses unlock, izin capture belum diminta")
                findViewById<TextView>(R.id.tvPin).text = "Unlock device dulu untuk lanjut mirroring"
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

    private var sourceStatusListener: ValueEventListener? = null

    private fun listenToSessionStatus() {
        val statusRef = getAppDatabase().getReference("sessions/$pin/status")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val status = snapshot.getValue(String::class.java)
                Log.d(TAG, "Source status update dari Firebase: $status")
                when (status) {
                    "online" -> updateBroadcastUI(isBroadcasting = true)
                    "disconnected" -> updateBroadcastUI(isBroadcasting = false, reason = "Diputus")
                    "offline" -> updateBroadcastUI(isBroadcasting = false, reason = "Offline")
                    else -> updateBroadcastUI(ScreenCaptureService.isRunning)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Gagal listen status sesi: ${error.message}")
            }
        }
        statusRef.addValueEventListener(listener)
        sourceStatusListener = listener
    }

    private fun stopScreenBroadcasting() {
        Log.d(TAG, "User menekan tombol Hentikan Siaran di Source")
        val stopIntent = Intent(this, ScreenCaptureService::class.java)
        stopService(stopIntent)
        val statusRef = getAppDatabase().getReference("sessions/$pin/status")
        statusRef.setValue("disconnected")
        getAppDatabase().getReference("sessions/$pin/frame").removeValue()
        updateBroadcastUI(isBroadcasting = false, reason = "Dihentikan oleh Anda")
        Toast.makeText(this, "Siaran layar dihentikan", Toast.LENGTH_SHORT).show()
    }

    private fun startScreenBroadcasting() {
        Log.d(TAG, "User menekan tombol Mulai Siaran Layar di Source")
        getAppDatabase().getReference("sessions/$pin/disconnect_request").setValue(false)
        requestScreenCaptureAfterKeyguard()
    }

    private fun updateBroadcastUI(isBroadcasting: Boolean, reason: String? = null) {
        val btnStop = findViewById<Button>(R.id.btnStopBroadcast) ?: return
        val btnStart = findViewById<Button>(R.id.btnStartBroadcast) ?: return
        val tvBroadcast = findViewById<TextView>(R.id.tvBroadcastStatus) ?: return

        if (isBroadcasting) {
            tvBroadcast.text = "Status: Sedang Menyiarkan Layar (Aktif)"
            tvBroadcast.setTextColor(Color.parseColor("#2E7D32"))
            btnStop.visibility = View.VISIBLE
            btnStart.visibility = View.GONE
        } else {
            val label = if (reason != null) "Status: Siaran $reason" else "Status: Siaran Berhenti / Siap"
            tvBroadcast.text = label
            tvBroadcast.setTextColor(Color.parseColor("#C62828"))
            btnStop.visibility = View.GONE
            btnStart.visibility = View.VISIBLE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sourceStatusListener?.let {
            try {
                getAppDatabase().getReference("sessions/$pin/status").removeEventListener(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error remove sourceStatusListener: ${e.message}")
            }
        }
    }
}
