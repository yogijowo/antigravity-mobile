# Antigrafity Remote (Android)

Aplikasi Android native berbasis **Kotlin + Jetpack Compose** yang membungkus `https://antigravity.google.com` ke dalam WebView layar penuh (*edge-to-edge*) sebagai remote control cepat di perangkat Android.

## Fitur Utama

1. **WebView Full-Screen & Smooth**
   - Tampilan penuh *edge-to-edge* dengan *hardware acceleration* aktif.
   - Penyesuaian **Dark / Light Mode** otomatis mengikuti tema sistem.
   - **Pull-to-Refresh** mulus (`SwipeRefreshLayout`) dan indikator progress tipis di bagian atas.
   - Dukungan tombol **Back** fisik/gesture untuk navigasi riwayat halaman, serta konfirmasi tekan 2x untuk keluar saat berada di halaman utama.
   - Penyimpanan sesi & login Google (*cookies*, *third-party cookies*, dan *DOM storage*) serta penanganan popup OAuth langsung di dalam aplikasi tanpa keluar ke browser eksternal.

2. **Floating Control Pill (Navigasi Ringkas)**
   - Satu tombol melayang yang dapat digeser bebas dan menempel otomatis ke tepi kiri/kanan layar.
   - Dapat disembunyikan ke tepi layar (*tuck*) agar tidak mengganggu tampilan web, lalu dibuka kembali dengan satu ketukan.
   - Akses cepat ke **Kembali**, **Beranda**, **Perintah Suara**, **Pengaturan Notifikasi**, dan **Muat Ulang**.

3. **Perintah Suara (Voice Command)**
   - Menggunakan `SpeechRecognizer` bawaan Android dengan pilihan bahasa **Indonesia (`id-ID`)** dan **English (`en-US`)**.
   - Dilengkapi indikator gelombang suara *real-time* dan pratinjau transkrip sementara.
   - Hasil suara otomatis disisipkan ke kolom input/prompt yang aktif di halaman `antigravity.google.com` melalui `evaluateJavascript`.

4. **Notifikasi Lokal & Jembatan JavaScript (`AndroidBridge`)**
   - Bottom sheet pengaturan untuk mengaktifkan/mematikan pengingat terjadwal, memilih interval (**15 menit – 24 jam**), mengubah judul/isi pesan, serta mengirim notifikasi tes.
   - Penjadwalan latar belakang menggunakan **WorkManager** dan penanganan izin notifikasi runtime Android 13+ (`POST_NOTIFICATIONS`).
   - Halaman web tepercaya pada domain `antigravity.google.com` / `google.com` dapat langsung memicu notifikasi lokal melalui objek global `window.AndroidBridge`:

```javascript
// Menampilkan notifikasi lokal dari halaman web
window.AndroidBridge?.showNotification("Status Sesi", "Tugas selesai dijalankan.");

// Shortcut notifikasi perubahan status
window.AndroidBridge?.notifyStatus("Deploy Berhasil", "Build terbaru sudah aktif.");

// Mengecek apakah izin notifikasi aktif di perangkat
const enabled = window.AndroidBridge?.isNotificationEnabled?.();
```

## Auto-Build APK via GitHub Actions

Proyek ini sudah menyertakan workflow otomatis di `.github/workflows/build-apk.yml` menggunakan **JDK 17** dan **Gradle 9.3.1**.

Cara mengunduh APK:
1. Push repository ini ke branch `main` atau `master` di GitHub (atau jalankan manual melalui tombol **Run workflow**).
2. Buka tab **Actions** pada repository GitHub Anda dan pilih workflow **Build Android Debug APK**.
3. Setelah selesai, unduh file APK pada bagian **Artifacts** bernama `antigrafity-remote-debug-apk` (`app-debug.apk`).

## Struktur Proyek

```text
.
├── .github/workflows/build-apk.yml        # Auto-build APK (JDK 17 + Gradle)
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/libs.versions.toml
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/example/
        │   ├── MainActivity.kt
        │   ├── data/AppPreferencesRepository.kt
        │   ├── notifications/
        │   │   ├── NotificationHelper.kt
        │   │   ├── NotificationScheduler.kt
        │   │   └── ReminderWorker.kt
        │   ├── voice/VoiceCommandManager.kt
        │   ├── webview/
        │   │   ├── AndroidBridge.kt
        │   │   ├── WebHostPolicy.kt
        │   │   └── WebViewController.kt
        │   └── ui/
        │       ├── MainViewModel.kt
        │       ├── components/
        │       │   ├── AntigrafityWebViewContainer.kt
        │       │   ├── FloatingControlPill.kt
        │       │   ├── NotificationSettingsSheet.kt
        │       │   └── VoiceCommandOverlay.kt
        │       └── theme/
        └── res/
```

## Build Lokal

Prasyarat: **JDK 17**, **Android SDK (API 36)**, dan **Gradle 9.3.1**.

```bash
gradle assembleDebug
```

File APK hasil build akan tersedia di:
`app/build/outputs/apk/debug/app-debug.apk`

## Izin Aplikasi

- `INTERNET` & `ACCESS_NETWORK_STATE` — Memuat `https://antigravity.google.com`.
- `RECORD_AUDIO` — Diminta saat menggunakan fitur Perintah Suara.
- `POST_NOTIFICATIONS` — Diminta pada Android 13+ untuk menampilkan pengingat lokal dan notifikasi dari `AndroidBridge`.
