package com.berkacaki

import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener

private const val TAG = "BerkacaKi_Viewer"
private const val KEY_SAVED_PINS = "viewer_saved_pins" // disimpan sebagai "pin1,pin2,...", terbaru di depan
private const val MAX_SAVED_PINS = 5
private const val CONNECT_TIMEOUT_MS = 8000L

private val COLOR_CONNECTING = Color.parseColor("#F57C00") // oranye
private val COLOR_ONLINE = Color.parseColor("#2E7D32")     // hijau
private val COLOR_OFFLINE = Color.parseColor("#757575")    // abu-abu
private val COLOR_ERROR = Color.parseColor("#C62828")      // merah

class ViewerActivity : AppCompatActivity() {

    private lateinit var etPin: EditText
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var imgMirror: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var llSavedPins: LinearLayout
    private lateinit var tvSavedPinsLabel: TextView

    private val prefs by lazy { getSharedPreferences("berkacaki_prefs", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null

    private var frameRef: DatabaseReference? = null
    private var statusRef: DatabaseReference? = null
    private var frameListener: ValueEventListener? = null
    private var statusListener: ValueEventListener? = null

    private var currentPin: String? = null
    private var receivedAnyData = false
    private var pinAlreadySaved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)
        Log.d(TAG, "onCreate")

        etPin = findViewById(R.id.etPin)
        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        imgMirror = findViewById(R.id.imgMirror)
        tvStatus = findViewById(R.id.tvStatus)
        llSavedPins = findViewById(R.id.llSavedPins)
        tvSavedPinsLabel = findViewById(R.id.tvSavedPinsLabel)

        renderSavedPins()

        btnConnect.setOnClickListener {
            val pin = etPin.text.toString().trim()
            Log.d(TAG, "Tombol connect diklik, pin='$pin' length=${pin.length}")
            if (pin.length == 6) {
                connectWithPin(pin)
            } else {
                Toast.makeText(this, "PIN harus 6 digit", Toast.LENGTH_SHORT).show()
            }
        }

        btnDisconnect.setOnClickListener {
            disconnect()
        }
    }

    // ---------- Daftar PIN tersimpan ----------

    private fun loadSavedPins(): List<String> {
        val raw = prefs.getString(KEY_SAVED_PINS, "") ?: ""
        return raw.split(",").filter { it.length == 6 }
    }

    private fun saveRecentPin(pin: String) {
        val updated = loadSavedPins().toMutableList()
        updated.remove(pin)
        updated.add(0, pin)
        val trimmed = updated.take(MAX_SAVED_PINS)
        prefs.edit().putString(KEY_SAVED_PINS, trimmed.joinToString(",")).apply()
        renderSavedPins()
    }

    private fun renderSavedPins() {
        llSavedPins.removeAllViews()
        val pins = loadSavedPins()
        val hasPins = pins.isNotEmpty()
        tvSavedPinsLabel.visibility = if (hasPins) View.VISIBLE else View.GONE
        llSavedPins.visibility = if (hasPins) View.VISIBLE else View.GONE

        val inflater = LayoutInflater.from(this)
        for (pin in pins) {
            val row = inflater.inflate(R.layout.item_saved_pin, llSavedPins, false)
            row.findViewById<TextView>(R.id.tvItemPin).text = pin
            row.setOnClickListener {
                etPin.setText(pin)
                connectWithPin(pin)
            }
            llSavedPins.addView(row)
        }
    }

    // ---------- Koneksi ke sesi ----------

    private fun connectWithPin(pin: String) {
        detachListeners()
        currentPin = pin
        receivedAnyData = false
        pinAlreadySaved = false
        imgMirror.setImageDrawable(null)
        btnDisconnect.visibility = View.VISIBLE
        setStatus("Menghubungkan ke PIN $pin...", COLOR_CONNECTING)

        // Reset flag disconnect jika sebelumnya ada
        getAppDatabase().getReference("sessions/$pin/disconnect_request").setValue(false)

        // Kirim permintaan koneksi ke source device agar otomatis mulai share screen jika belum aktif
        getAppDatabase().getReference("sessions/$pin/connect_request").setValue(true)
            .addOnFailureListener { e ->
                Log.e(TAG, "Gagal mengirim connect_request ke sessions/$pin/connect_request: ${e.message}")
            }

        val fRef = getAppDatabase().getReference("sessions/$pin/frame")
        val fListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (currentPin != pin) return // hasil listener lama yang telat, abaikan
                val base64 = snapshot.getValue(String::class.java)
                if (base64 == null) {
                    Log.w(TAG, "Snapshot frame ada tapi kosong (source belum kirim frame)")
                    return
                }
                markDataReceived(pin)
                try {
                    val bytes = Base64.decode(base64, Base64.NO_WRAP)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap == null) {
                        Log.e(TAG, "Gagal decode bitmap dari ${bytes.size} bytes")
                        return
                    }
                    imgMirror.setImageBitmap(bitmap)
                } catch (e: Exception) {
                    Log.e(TAG, "Error decode frame: ${e.message}", e)
                }
            }
            override fun onCancelled(error: DatabaseError) {
                if (currentPin != pin) return
                Log.e(TAG, "Listener frame dibatalkan: ${error.message}")
                setStatus("Tidak dapat terhubung: ${error.message}", COLOR_ERROR)
                btnDisconnect.visibility = View.GONE
            }
        }
        fRef.addValueEventListener(fListener)
        frameRef = fRef
        frameListener = fListener

        val sRef = getAppDatabase().getReference("sessions/$pin/status")
        val sListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (currentPin != pin) return
                val status = snapshot.getValue(String::class.java)
                if (status == null) {
                    // Path status belum pernah ada sama sekali -> PIN kemungkinan salah/belum pernah dipakai
                    if (!receivedAnyData) {
                        setStatus("PIN tidak ditemukan / source belum pernah aktif", COLOR_ERROR)
                        btnDisconnect.visibility = View.GONE
                    }
                    return
                }
                markDataReceived(pin)
                when (status) {
                    "online" -> setStatus("Online - menerima layar", COLOR_ONLINE)
                    "disconnected" -> {
                        setStatus("Source memutuskan siaran layar", COLOR_OFFLINE)
                        imgMirror.setImageDrawable(null)
                        btnDisconnect.visibility = View.GONE
                    }
                    else -> setStatus("Offline - source sedang tidak aktif", COLOR_OFFLINE)
                }
            }
            override fun onCancelled(error: DatabaseError) {
                if (currentPin != pin) return
                Log.e(TAG, "Listener status dibatalkan: ${error.message}")
                setStatus("Tidak dapat terhubung: ${error.message}", COLOR_ERROR)
                btnDisconnect.visibility = View.GONE
            }
        }
        sRef.addValueEventListener(sListener)
        statusRef = sRef
        statusListener = sListener

        val r = Runnable {
            if (!receivedAnyData) {
                setStatus("Tidak dapat terhubung (cek PIN atau koneksi internet)", COLOR_ERROR)
                btnDisconnect.visibility = View.GONE
            }
        }
        timeoutRunnable = r
        handler.postDelayed(r, CONNECT_TIMEOUT_MS)
    }

    private fun disconnect() {
        val pin = currentPin
        Log.d(TAG, "Memutuskan sambungan dari Viewer untuk pin=$pin")
        detachListeners()
        if (pin != null) {
            getAppDatabase().getReference("sessions/$pin/disconnect_request").setValue(true)
            getAppDatabase().getReference("sessions/$pin/status").setValue("disconnected")
            getAppDatabase().getReference("sessions/$pin/frame").removeValue()
        }
        currentPin = null
        receivedAnyData = false
        imgMirror.setImageDrawable(null)
        btnDisconnect.visibility = View.GONE
        setStatus("Sambungan diputuskan", COLOR_OFFLINE)
        Toast.makeText(this, "Sambungan diputuskan", Toast.LENGTH_SHORT).show()
    }

    private fun markDataReceived(pin: String) {
        receivedAnyData = true
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        if (!pinAlreadySaved) {
            pinAlreadySaved = true
            saveRecentPin(pin)
        }
    }

    private fun detachListeners() {
        frameListener?.let { frameRef?.removeEventListener(it) }
        statusListener?.let { statusRef?.removeEventListener(it) }
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        frameListener = null
        statusListener = null
        timeoutRunnable = null
    }

    private fun setStatus(text: String, color: Int) {
        tvStatus.text = text
        tvStatus.setTextColor(color)
    }

    override fun onDestroy() {
        super.onDestroy()
        detachListeners()
    }
}