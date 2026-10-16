# BerkacaKi (SystemProtected) - Screen Mirroring & Remote Screen Viewer

Aplikasi Android untuk mirroring dan pemantauan layar real-time antar perangkat menggunakan **Firebase Realtime Database**, dilengkapi fitur auto-accept MediaProjection, deep link browser, serta kontrol pemutusan sambungan (disconnect) dua arah.

---

## 📋 Fitur Utama

- **Role Selection**: Mode **Sumber (Source)** untuk perangkat yang membagikan layar, dan Mode **Penampil (Viewer)** untuk memantau layar.
- **PIN Sambungan 6-Digit**: Sesi diamankan menggunakan PIN unik.
- **Deep Link Browser**: Aplikasi dapat diluncurkan langsung dari browser atau web menggunakan skema:
  - `open://com.systemprotected`
  - `systemprotected://open`
- **Fitur Disconnect Dua Arah**:
  - **Viewer**: Tombol *Putuskan* untuk menghentikan penerimaan layar dan mematikan transmisi di sisi Source.
  - **Source**: Tombol *Putuskan / Hentikan Siaran* dan *Mulai Siaran Layar* langsung dari tampilan aplikasi.
- **Layanan Latar Belakang (System Mode)**:
  - Tetap siaga menerima sambungan dari Viewer via `ConnectionListenerService`.
  - Aksesibilitas (`MirrorAutoAcceptService`) untuk mengonfirmasi izin perekaman layar secara otomatis.
  - Opsi menyembunyikan ikon aplikasi dari launcher untuk keamanan privasi.

---

## 🛠️ Prasyarat (Prerequisites)

1. **Android Studio**: Android Studio Hedgehog / Iguana / Jellyfish atau versi yang lebih baru.
2. **JDK**: JDK 17 atau JDK 21.
3. **Android SDK**:
   - `compileSdk`: 35
   - `minSdk`: 24 (Android 7.0+)
   - `targetSdk`: 35 (Android 15)
4. **Firebase Project**:
   - Firebase Realtime Database yang aktif.
   - File konfigurasi `google-services.json` yang ditempatkan di dalam folder `app/`.

---

## 🚀 Setup Proyek

### 1. Clone / Buka Proyek
Buka Android Studio, pilih **Open**, lalu arahkan ke direktori root proyek ini.

### 2. Konfigurasi Firebase
Pastikan file `app/google-services.json` sudah terkonfigurasi dengan project Firebase Anda.
Struktur aturan database (*Security Rules*) Firebase Realtime Database minimal untuk pengujian:
```json
{
  "rules": {
    "sessions": {
      ".read": true,
      ".write": true
    }
  }
}
```

### 3. Konfigurasi Signing (Build Lokal di Mesin Sendiri)
Jika Anda mengompilasi di Android Studio atau terminal lokal dan tidak memiliki `debugConfig` bawaan cloud, periksa blok `signingConfigs` di `app/build.gradle.kts`:
Ganti:
```kotlin
signingConfig = signingConfigs.getByName("debugConfig")
```
Menjadi:
```kotlin
signingConfig = signingConfigs.getByName("debug")
```

---

## 🔨 Cara Build APK

### Melalui Terminal / CLI:
```bash
# Untuk Linux / macOS:
./gradlew assembleDebug

# Untuk Windows CMD / PowerShell:
gradlew.bat assembleDebug
```
File APK hasil build akan berada di:
`app/build/outputs/apk/debug/app-debug.apk`

### Melalui Android Studio:
1. Klik menu **Build** > **Build Bundle(s) / APK(s)** > **Build APK(s)**.
2. Tunggu proses Gradle selesai, lalu klik **locate** pada notifikasi yang muncul.

---

## 📱 Cara Instalasi & Pengujian

### 1. Pasang APK ke Perangkat
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 2. Izin Penting pada Perangkat Sumber (Source):
Saat pertama kali membuka mode **Jadi Sumber**:
1. **Izin Tampil di Atas Aplikasi Lain (Draw Over Apps / Overlay)**: Aktifkan izin hamparan agar proses mirroring dapat berjalan dari latar belakang.
2. **Layanan Aksesibilitas (Klik Otomatis)**: Aktifkan *BerkacaKi Auto Accept Service* di menu Aksesibilitas perangkat agar dialog konfirmasi perekaman layar diklik otomatis.
3. **Notifikasi**: Berikan izin notifikasi (Android 13+).

### 3. Menguji Pembukaan via Browser / Deep Link:
Buka browser di perangkat atau kirim perintah melalui ADB:
```bash
adb shell am start -a android.intent.action.VIEW -d "open://com.systemprotected"
```
Atau buat tautan HTML sederhana di browser:
```html
<a href="open://com.systemprotected">Buka SystemProtected</a>
```

### 4. Menguji Fitur Disconnect:
- **Dari Viewer**: Masukkan PIN 6-digit > Tekan **Sambung**. Setelah layar Source tampil, tekan tombol merah **Putuskan**. Transmisi frame akan berhenti di kedua perangkat.
- **Dari Source**: Buka SourceActivity > Tekan tombol merah **Putuskan / Hentikan Siaran**. Siaran layar akan dihentikan seketika dan Viewer akan menerima status offline. Tekan **Mulai Siaran Layar** untuk memulai kembali.
