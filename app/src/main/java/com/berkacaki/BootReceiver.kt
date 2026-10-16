package com.berkacaki

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * BOOT_COMPLETED punya exemption resmi dari Android untuk memanggil startActivity()
 * langsung dari onReceive() tanpa perlu notifikasi/tap dulu (dipakai juga oleh
 * app alarm/dialer). Karena itu SourceActivity dibuka langsung di sini.
 *
 * Internet TIDAK dijadikan syarat untuk membuka SourceActivity -- pengiriman frame
 * ke Firebase sudah ditangani terpisah oleh NetworkCallback di ScreenCaptureService,
 * jadi begitu koneksi tersedia (kapan pun), frame otomatis lanjut terkirim tanpa
 * SourceActivity perlu menunggu.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        android.util.Log.d("BerkacaKi_Boot", "BootReceiver triggered, action=${intent.action}")
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return

        val prefs = context.getAppPreferences()
        val hadActiveSource = prefs.contains("saved_pin")
        if (!hadActiveSource) {
            android.util.Log.d("BerkacaKi_Boot", "Device ini belum pernah jadi Source, auto-resume dilewati")
            return
        }

        android.util.Log.d("BerkacaKi_Boot", "Memulai ConnectionListenerService secara pasif di latar belakang")
        val serviceIntent = Intent(context, ConnectionListenerService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}