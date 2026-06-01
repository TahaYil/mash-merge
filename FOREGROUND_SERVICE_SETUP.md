# AuraMesh Foreground Service Kurulumu

## Genel Bakış

`AuraBluetoothForegroundService`, **GATT Service**'i bir Foreground Service olarak çalıştırarak, eski cihazlarda Bluetooth scanner'ının kapanmasını önler.

### Sorun ve Çözüm

**Sorun:** Eski cihazlarda (API 24-30), sistem bellek tasarrufu için arka planda çalışan uygulamaları kapatabilir ve BLE scanner'ı sonlandırabilir.

**Çözüm:** 
- Foreground Service (persistent notification ile) sistem tarafından sonlandırılmaz
- Watchdog mekanizması Bluetooth durumunu kontrol eder
- Health Checker GATT Service'in aktif kalmasını sağlar

---

## Kurulum Adımları

### 1. Manifest Dosyası (Zaten Yapılmış ✓)

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />

<service
    android:name=".hardware.Bluetooth.AuraBluetoothForegroundService"
    android:enabled="true"
    android:exported="false"
    android:foregroundServiceType="connectedDevice" />
```

### 2. MainActivity veya Başlangıç Noktasında Servis'i Başlat

```java
// MainActivity.java veya ilgili Activity'de

import android.content.Intent;
import android.content.Context;

public class MainActivity extends AppCompatActivity {
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        // Foreground Service'i başlat
        startBluetoothForegroundService();
    }
    
    private void startBluetoothForegroundService() {
        Intent serviceIntent = new Intent(this, AuraBluetoothForegroundService.class);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Servis'i durdurmak isterseniz (opsiyonel)
        // Intent serviceIntent = new Intent(this, AuraBluetoothForegroundService.class);
        // stopService(serviceIntent);
    }
}
```

---

## Nasıl Çalışır?

### Bileşenler:

1. **Foreground Service**
   - Uygulamada sürekli çalışan bir hizmet
   - Sistem tarafından ölümcül şekilde sonlandırılmaz
   - Persistent notification gösterir

2. **GATT Service**
   - BLE Client ve Server işlevselliği sağlar
   - Komşu cihazlarla veri değişimi yapar
   - EventBus ile iletişim kurar

3. **Watchdog Thread**
   - Her 30 saniyede bir Bluetooth durumunu kontrol eder
   - Daemon olmayan thread (uygulama kapanana kadar çalışır)
   - Hataları log'lar

4. **Health Checker Thread**
   - Her 60 saniyede bir GATT Service'in yaşam durumunu kontrol eder
   - Notification'ı günceller
   - Eski cihazlarda scanner'ın kapanmasını önler

---

## Log Output Örneği

```
D/AuraFgService: Foreground Service oluşturuluyor...
D/AuraFgService: Foreground Service başlatılıyor...
D/AuraFgService: GATT Service oluşturuldu
D/AuraFgService: Watchdog başlatıldı
D/AuraFgService: Health Checker başlatıldı
D/AuraGattService: Server dinleme başlıyor...

// 30 saniye sonra:
D/AuraFgService: Watchdog: GATT kontrol ediliyor

// 60 saniye sonra:
D/AuraFgService: Health Check: GATT Service aktif
```

---

## Thread Modeli

```
MainThread
    ↓
AuraBluetoothForegroundService (onCreate/onStartCommand)
    ├─→ GATT Service Thread (EventBus operations)
    ├─→ Watchdog Thread (Non-Daemon) - Her 30s
    └─→ Health Checker Thread (Non-Daemon) - Her 60s
```

### Neden Daemon False?

- `setDaemon(false)`: Thread JVM kapanıncaya kadar çalışır
- `setDaemon(true)`: Ana thread bitince, bu thread de biter
- Eski cihazlarda, sistem uygulamayı suspend ettiğinde, daemon thread'ler durabilir
- Non-daemon thread'ler sistem tarafından agresif olarak kapatılmaz

---

## API Seviyeleri

| API | Davranış |
|-----|----------|
| 24-29 | Foreground Service ile tamamen korunur |
| 30+ | ConnectedDevice foregroundServiceType ile koruma altında |
| 34+ | Full compliance (SDK 36 ile test edildi) |

---

## Notification Gösterimi

Servis çalışırken kullanıcı şunu görür:

```
🔵 AuraMesh Ağı Aktif
   Bluetooth dinlemesi devam ediyor...
```

Notification üzerine tıklandığında MainActivity açılır.

---

## Hata İşleme

```java
// GATT Service oluşturma hatası
if (gattService == null) {
    try {
        gattService = new AuraGattService(getApplicationContext());
    } catch (Exception e) {
        Log.e(TAG, "GATT Service hatası: " + e.getMessage());
        isServiceRunning.set(false);
        return START_NOT_STICKY; // Servis otomatik restart yapılmaz
    }
}

// Watchdog ve Health Checker hataları
// → Log'lanır, servis çalışmaya devam eder
```

---

## Kapatma

Servis otomatik olarak kapatılmaz. Elle kapatmak için:

```java
// İçinden
Intent intent = new Intent(this, AuraBluetoothForegroundService.class);
stopService(intent);

// Dışarıdan (herhangi bir Activity'den)
Intent intent = new Intent(context, AuraBluetoothForegroundService.class);
context.stopService(intent);
```

`onDestroy()` şu işlemleri yapar:
1. Watchdog thread'i kapatır
2. Health Checker thread'i kapatır
3. GATT Service'i temizler
4. Foreground notification'ı kaldırır

---

## Eski Cihazlar İçin Spesifik Optimizasyonlar

### 1. Non-Daemon Threads
- Sistem tarafından erken sonlandırılması engellenir

### 2. `scheduleWithFixedDelay`
- Android 12+ cache süresinde beklenmedik davranışları önler

### 3. Persistent Notification
- Foreground Service, sistem GC işlemleri sırasında korunur

### 4. Regular Health Checks
- Scanner kapanmışsa, manuel restart mekanizması eklenebilir

---

## Test Etme

### Simulator Üzerinde:
```bash
# Android Studio'da API 24-26 cihazında test edin
# Notification'ın göründüğünü doğrulayın
# Logcat'te Watchdog/Health Check mesajlarını takip edin
```

### Gerçek Cihaz Üzerinde:
```bash
# Eski cihazda (API 24-30) test edin
# Uygulama arka plana gittikten sonra
# Komşu cihazları hala bulup bulamadığını kontrol edin
```

---

## İleri Düzey Ayarlamalar

### Watchdog Interval'ı Değiştirme:
```java
private static final long WATCHDOG_INTERVAL_SECONDS = 15; // 30'dan 15'e
```

### Health Check Interval'ı Değiştirme:
```java
private static final long HEALTH_CHECK_INTERVAL_SECONDS = 30; // 60'dan 30'a
```

---

## Bilinen Sınırlamalar

1. **Oreo+ (API 26+)**: Foreground Service artık zorunlu
2. **Android 12+**: `scheduleAtFixedRate` tavsiye edilmiyor → `scheduleWithFixedDelay` kullanılıyor
3. **Eski cihazlar**: Sistem tarafından aggressive suspend edilebilir → Watchdog mitigates

---

## Başarı Göstergeleri

✓ Servis başlangıçta başlatılıyor  
✓ Notification sürekli görünüyor  
✓ Logcat'te düzenli Watchdog mesajları  
✓ GATT Service başarıyla oluşturuluyor  
✓ Eski cihazlarda scanner kapanmıyor  

---

**Son Güncelleme:** June 2026  
**Test Edildi:** API 24-36, Gerçek cihazlar dahil

