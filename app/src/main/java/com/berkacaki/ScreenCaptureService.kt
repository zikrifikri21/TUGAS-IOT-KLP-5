package com.berkacaki

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import java.io.ByteArrayOutputStream

private const val TAG = "BerkacaKi_Source"

class ScreenCaptureService : Service() {
    companion object {
        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private lateinit var mediaProjection: MediaProjection
    private lateinit var virtualDisplay: VirtualDisplay
    private lateinit var imageReader: ImageReader
    private lateinit var pin: String
    private val db = getAppDatabase()
    private var lastSendTime = 0L
    private val sendIntervalMs = 400L // ~2.5 fps, cukup untuk demo "realtime"

    private lateinit var connectivityManager: ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isCurrentlyOnline = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand dipanggil")
        isRunning = true
        pin = intent?.getStringExtra("pin") ?: run {
            Log.e(TAG, "Intent tidak membawa extra 'pin', service dihentikan")
            return START_NOT_STICKY
        }
        Log.d(TAG, "PIN diterima service: $pin")
        val resultCode = intent.getIntExtra("resultCode", -1)
        val data = intent.getParcelableExtra<Intent>("data")!!

        ServiceCompat.startForeground(
            this,
            1,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, data)
            ?: run {
                Log.e(TAG, "getMediaProjection() mengembalikan null, resultCode=$resultCode. Service dihentikan")
                stopSelf()
                return START_NOT_STICKY
            }
        mediaProjection = projection
        Log.d(TAG, "MediaProjection berhasil dibuat")

        // Wajib: daftarkan callback sebelum createVirtualDisplay (Android 14+)
        mediaProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))

        val metrics = resources.displayMetrics
        val width = 480
        val height = (width * metrics.heightPixels / metrics.widthPixels)

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        val display = mediaProjection.createVirtualDisplay(
            "ScreenMirror", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, null
        ) ?: run {
            Log.e(TAG, "createVirtualDisplay() gagal (null). Service dihentikan")
            stopSelf()
            return START_NOT_STICKY
        }
        virtualDisplay = display
        Log.d(TAG, "VirtualDisplay dibuat: ${width}x${height}")

        Log.d(TAG, "Mendaftarkan onImageAvailableListener")
        imageReader.setOnImageAvailableListener({ reader ->
            Log.d(TAG, "onImageAvailable dipanggil")
            val now = System.currentTimeMillis()
            if (now - lastSendTime < sendIntervalMs) {
                reader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            lastSendTime = now
            val image = reader.acquireLatestImage() ?: run {
                Log.w(TAG, "acquireLatestImage() null, skip frame ini")
                return@setOnImageAvailableListener
            }
            try {
                val bitmap = imageToBitmap(image)
                sendFrame(bitmap)
            } catch (e: Exception) {
                Log.e(TAG, "Exception saat proses/kirim frame: ${e.message}", e)
            } finally {
                image.close()
            }
        }, Handler(Looper.getMainLooper()))

        writeOnlineStatus(reason = "capture dimulai")
        listenForDisconnectRequests()
        registerNetworkCallback()

        return START_NOT_STICKY
    }

    private var disconnectListener: ValueEventListener? = null

    private fun listenForDisconnectRequests() {
        if (!::pin.isInitialized) return
        val disconnectRef = db.getReference("sessions/$pin/disconnect_request")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val disconnectRequested = snapshot.getValue(Boolean::class.java) ?: false
                if (disconnectRequested) {
                    Log.d(TAG, "Menerima permintaan disconnect dari Viewer! Menghentikan ScreenCaptureService...")
                    disconnectRef.setValue(false)
                    db.getReference("sessions/$pin/status").setValue("disconnected")
                    stopSelf()
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Error listen disconnect_request: ${error.message}")
            }
        }
        disconnectRef.addValueEventListener(listener)
        disconnectListener = listener
    }

    /**
     * Tulis status "online" ke Firebase dan pasang ulang onDisconnect("offline").
     * Dipanggil saat capture pertama kali start, DAN setiap kali koneksi internet
     * kembali (via NetworkCallback) -- supaya Viewer otomatis lihat status "online"
     * lagi tanpa perlu aksi manual apapun, walau sempat putus sebentar.
     */
    private fun writeOnlineStatus(reason: String) {
        if (!::pin.isInitialized) return
        val statusRef = db.getReference("sessions/$pin/status")
        statusRef.setValue("online")
            .addOnSuccessListener { Log.d(TAG, "Status 'online' tersimpan ($reason)") }
            .addOnFailureListener { e -> Log.e(TAG, "Gagal set status online ($reason): ${e.message}", e) }
        statusRef.onDisconnect().setValue("offline")
        isCurrentlyOnline = true
    }

    private fun registerNetworkCallback() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                Log.d(TAG, "NetworkCallback.onAvailable -- internet kembali tersedia (WiFi/data seluler)")
                if (!isCurrentlyOnline) {
                    writeOnlineStatus(reason = "network onAvailable")
                }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                Log.d(TAG, "NetworkCallback.onLost -- koneksi terputus")
                isCurrentlyOnline = false
            }
        }
        networkCallback = callback
        connectivityManager.registerNetworkCallback(request, callback)
        Log.d(TAG, "NetworkCallback terdaftar, memantau perubahan WiFi/data seluler")
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        return Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
    }

    private var frameCount = 0

    private fun sendFrame(bitmap: Bitmap) {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 40, stream)
        val base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        frameCount++
        // Log tiap 10 frame saja biar tidak banjir logcat, tapi tetap kelihatan jalan
        val shouldLog = frameCount % 10 == 1

        db.getReference("sessions/$pin/frame").setValue(base64)
            .addOnSuccessListener {
                if (shouldLog) Log.d(TAG, "Frame #$frameCount terkirim ke sessions/$pin/frame (${base64.length} char)")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "GAGAL kirim frame #$frameCount ke sessions/$pin/frame: ${e.message}", e)
            }
        db.getReference("sessions/$pin/timestamp").setValue(ServerValue.TIMESTAMP)
            .addOnFailureListener { e ->
                Log.e(TAG, "Gagal update timestamp: ${e.message}", e)
            }
    }

    private fun buildNotification(): Notification {
        val channelId = "mirror_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Screen Mirror", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Sedang mem-broadcast layar")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        Log.d(TAG, "ScreenCaptureService ditutup")
        isRunning = false
        super.onDestroy()
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
                Log.d(TAG, "NetworkCallback berhasil di-unregister")
            } catch (e: Exception) {
                Log.w(TAG, "Gagal unregister NetworkCallback (kemungkinan sudah ke-unregister): ${e.message}")
            }
        }
        disconnectListener?.let {
            if (::pin.isInitialized) {
                try {
                    db.getReference("sessions/$pin/disconnect_request").removeEventListener(it)
                } catch (e: Exception) {
                    Log.w(TAG, "Gagal remove disconnectListener: ${e.message}")
                }
            }
        }
        if (::pin.isInitialized) {
            db.getReference("sessions/$pin/frame").removeValue()
        }
        if (::virtualDisplay.isInitialized) virtualDisplay.release()
        if (::mediaProjection.isInitialized) mediaProjection.stop()
    }
}