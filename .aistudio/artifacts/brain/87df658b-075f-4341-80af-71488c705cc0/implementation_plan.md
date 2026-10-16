# Integrasi Deep Link Browser & Fitur Disconnect Dua Arah

Rencana pembaruan untuk mendukung pembukaan aplikasi melalui tautan browser `open://com.systemprotected` serta menambahkan kontrol pemutusan sambungan (disconnect) interaktif di sisi Viewer dan Source.

## User Review & Critical Decisions

> [!IMPORTANT]
> Keputusan berikut telah dikonfirmasi berdasarkan jawaban klarifikasi:
> - **Format Deep Link Browser**: Mendukung kedua skema, yaitu format baru `open://com.systemprotected` dan format lama `systemprotected://open`.
> - **Aksi Putus dari Viewer**: Memutuskan tampilan lokal Viewer sekaligus mengirim sinyal ke Firebase untuk menghentikan `ScreenCaptureService` di perangkat Source.
> - **Penempatan Tombol Putus di Source**: Ditempatkan langsung di dalam tampilan aplikasi Sumber (`SourceActivity`) dengan status siaran yang jelas.

---

## 1. Overview & Core Concept

- **What It Does**: Memungkinkan pengguna meluncurkan aplikasi langsung dari browser atau tautan HTML menggunakan URL `open://com.systemprotected`, serta memberikan kendali penuh bagi pengguna di kedua perangkat (Source dan Viewer) untuk mengakhiri sesi mirroring kapan saja secara instan.
- **Target Audience / Persona**: Pengguna yang mengelola perangkat cloning/mirroring dari jarak jauh atau secara lokal yang membutuhkan kemudahan akses via tautan web dan kontrol keamanan untuk menghentikan transmisi layar.
- **Key Value**: Akses instan tanpa repot mencari ikon di app drawer, serta keamanan privasi terjamin karena transmisi layar dapat dihentikan seketika baik dari sisi pengirim (Source) maupun penerima (Viewer).

---

## 2. User Experience & Visual Design

### Key User Flows

1. **Alur Buka via Browser**:
   - Pengguna membuka tautan `<a href="open://com.systemprotected">Buka Aplikasi</a>` di Chrome atau browser Android lainnya.
   - Sistem Android langsung mengenali intent dan membuka `MainActivity` secara mulus.

2. **Alur Putus Sambungan dari Viewer**:
   - Saat Viewer sedang terhubung dan menampilkan layar mirroring, terdapat tombol aksi berwarna merah **"Putuskan Sambungan"**.
   - Mengetuk tombol tersebut akan:
     - Mengirim sinyal `disconnect_request: true` ke Firebase Realtime Database pada path sesi.
     - Menghentikan listener frame dan membersihkan tampilan layar mirror di Viewer.
     - Mengubah status tampilan Viewer menjadi "Koneksi diputuskan".

3. **Alur Putus Sambungan dari Source**:
   - Pada kartu status siaran di `SourceActivity`, terdapat tombol **"Hentikan Siaran"**.
   - Mengetuk tombol tersebut akan:
     - Menghentikan `ScreenCaptureService` di latar belakang.
     - Mengubah status sesi di Firebase menjadi `"offline"` atau `"disconnected"`.
     - Memperbarui tombol menjadi **"Mulai Siaran Lagi"** jika pengguna ingin menyiarkan kembali.

4. **Sinkronisasi Otomatis**:
   - Jika Viewer menekan "Putuskan", Source mendeteksi `disconnect_request` dan otomatis mematikan penangkapan layar serta memperbarui status lokal.

### Visual Styling & Component Layout

- **Warna & Aksen**:
  - Tombol Putus / Disconnect: Warna merah Material 3 (`#D32F2F`) dengan teks kontras putih untuk menandakan aksi penghentian.
  - Tombol Sambung / Mulai: Warna biru Material 3 (`#1A73E8`).
  - Indikator Status: Hijau (`#2E7D32`) saat aktif, Abu-abu (`#757575`) saat terputus/standby, Oranye (`#F57C00`) saat proses menyambung.
- **Touch Target**: Seluruh tombol interaktif memiliki tinggi minimal 48dp untuk kenyamanan sentuh.

---

## 3. Key Product Decisions & Trade-Offs

- **Multi-Scheme Intent Filter**:
  - *Pendekatan*: Menambahkan `<data android:scheme="open" android:host="com.systemprotected" />` serta mempertahankan skema lama.
  - *Alasan*: Menjamin kompatibilitas tanpa merusak integrasi browser lama.
- **Two-Way Firebase Disconnect Signaling**:
  - *Pendekatan*: Menggunakan node `sessions/{pin}/disconnect_request` dan sinkronisasi `sessions/{pin}/status`.
  - *Alasan*: Komunikasi asinkron via Firebase Realtime Database bekerja seketika tanpa memerlukan soket P2P tambahan, dan memastikan kedua perangkat selalu dalam status sinkron.

---

## 4. Technical Architecture & Data Strategy

```
┌───────────────────────────────────────────────────────────────┐
│                      Browser Link / Web                       │
│                   open://com.systemprotected                  │
└───────────────────────────────┬───────────────────────────────┘
                                │ Intent VIEW / BROWSABLE
                                ▼
┌───────────────────────────────────────────────────────────────┐
│              AndroidManifest.xml (MainActivity)               │
└───────────────────────────────┬───────────────────────────────┘
                                │
                                ▼
┌───────────────────────────────┴───────────────────────────────┐
│                  Firebase Realtime Database                   │
│  Path: sessions/{pin}/                                        │
│    ├── frame: "<base64_jpeg>"                                 │
│    ├── status: "online" | "ready" | "disconnected"            │
│    ├── connect_request: true | false                          │
│    └── disconnect_request: true | false                       │
└───────────────┬───────────────────────────────▲───────────────┘
                │ Sinyal Disconnect             │ Kirim Frame / Status
                ▼                               │
┌───────────────────────────────┐ ┌─────────────┴───────────────┐
│        ViewerActivity         │ │   SourceActivity & Service  │
│  - Tombol "Putuskan"          │ │  - ScreenCaptureService     │
│  - Bersihkan canvas/frame     │ │  - Tombol "Hentikan Siaran" │
│  - Set disconnect_request     │ │  - Listener disconnect_req  │
└───────────────────────────────┘ └─────────────────────────────┘
```

### Rencana File Perubahan

1. **`app/src/main/AndroidManifest.xml`**:
   - Tambahkan intent-filter dengan `android:scheme="open"` dan `android:host="com.systemprotected"`.
2. **`app/src/main/res/layout/activity_viewer.xml` & `ViewerActivity.kt`**:
   - Tambahkan tombol `btnDisconnect` untuk memutus sambungan.
   - Implementasikan logika pemutusan lokal dan penulisan sinyal `disconnect_request` ke Firebase.
3. **`app/src/main/res/layout/activity_source.xml` & `SourceActivity.kt`**:
   - Tambahkan status indikator siaran dan tombol "Hentikan Siaran" / "Mulai Siaran".
   - Hubungkan aksi tombol untuk menghentikan `ScreenCaptureService` dan mereset status Firebase.
4. **`app/src/main/java/com/berkacaki/ScreenCaptureService.kt`**:
   - Tambahkan pemantauan pada node `sessions/{pin}/disconnect_request`. Jika `true`, otomatis panggil `stopSelf()`.
