package org.example.auramesh.hardware.BluetoothLE;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.example.auramesh.MainActivity;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AuraBleScannerForegroundService - BLE Scanner'ı Foreground Service olarak çalıştırır
 *
 * Eski cihazlarda scanner'ın kapanmasını önlemek için:
 * - Foreground Service ile sistem tarafından sonlandırılması engellenir
 * - Watchdog mekanizması Scanner'ın sağlığını kontrol eder
 * - Health Checker BLE taramasının aktif kalmasını sağlar
 */
public class AuraBleScannerForegroundService extends Service {
    private static final String TAG = "AuraBleScannerFgService";
    private static final String CHANNEL_ID = "aura_ble_scanner_channel";
    private static final int NOTIF_ID = 102;

    private static final long WATCHDOG_INTERVAL_SECONDS = 30;
    private static final long HEALTH_CHECK_INTERVAL_SECONDS = 60;

    private AuraBleScanner bleScanner;
    private ScheduledExecutorService watchdog;
    private ScheduledExecutorService healthChecker;
    private final AtomicBoolean isServiceRunning = new AtomicBoolean(false);
    private BluetoothAdapter bluetoothAdapter;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "BLE Scanner Foreground Service oluşturuluyor...");
        createNotificationChannel();
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        startForeground(NOTIF_ID, buildNotification("BLE Taraması Aktif"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "BLE Scanner Foreground Service başlatılıyor...");

        if (!isServiceRunning.compareAndSet(false, true)) {
            Log.d(TAG, "BLE Scanner servisi zaten çalışıyor");
            return START_STICKY;
        }

        // BLE Scanner'ı oluştur ve başlat
        if (bleScanner == null) {
            try {
                bleScanner = new AuraBleScanner();
                bleScanner.start();
                Log.d(TAG, "BLE Scanner oluşturuldu ve başlatıldı");
            } catch (Exception e) {
                Log.e(TAG, "BLE Scanner hatası: " + e.getMessage());
                isServiceRunning.set(false);
                return START_NOT_STICKY;
            }
        }

        startWatchdog();
        startHealthChecker();

        return START_STICKY;
    }

    /**
     * Watchdog: Bluetooth'un açık olduğunu kontrol eder
     */
    @SuppressWarnings("resource")
    private void startWatchdog() {
        if (watchdog != null) return;

        watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AuraBleWatchdog");
            t.setDaemon(false);
            return t;
        });

        watchdog.scheduleWithFixedDelay(() -> {
            try {
                if (bleScanner != null && bluetoothAdapter != null) {
                    if (!bluetoothAdapter.isEnabled()) {
                        Log.w(TAG, "Bluetooth kapalı, yeniden başlatılıyor");
                        bleScanner.stop();
                        bleScanner.start();
                    }
                    Log.d(TAG, "Watchdog: BLE Scanner kontrol ediliyor");
                }
            } catch (Throwable e) {
                Log.e(TAG, "Watchdog hatası: " + e.getMessage());
            }
        }, WATCHDOG_INTERVAL_SECONDS, WATCHDOG_INTERVAL_SECONDS, TimeUnit.SECONDS);

        Log.d(TAG, "Watchdog başlatıldı");
    }

    /**
     * Health Checker: BLE Scanner'ın aktif kalmasını sağlar
     * Eski cihazlarda scanner'ın kapanmasını önler
     */
    @SuppressWarnings("resource")
    private void startHealthChecker() {
        if (healthChecker != null) return;

        healthChecker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AuraBleHealthChecker");
            t.setDaemon(false);
            return t;
        });

        healthChecker.scheduleWithFixedDelay(() -> {
            try {
                if (bleScanner != null) {
                    Log.d(TAG, "Health Check: BLE Scanner aktif");
                    updateNotification("BLE Taraması Aktif ✓");
                }
            } catch (Throwable e) {
                Log.e(TAG, "Health Check hatası: " + e.getMessage());
            }
        }, HEALTH_CHECK_INTERVAL_SECONDS, HEALTH_CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);

        Log.d(TAG, "Health Checker başlatıldı");
    }

    private void updateNotification(String text) {
        try {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.notify(NOTIF_ID, buildNotification(text));
            }
        } catch (Exception e) {
            Log.e(TAG, "Bildirim hatası: " + e.getMessage());
        }
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "BLE Scanner Foreground Service durduruluyor...");
        isServiceRunning.set(false);

        if (watchdog != null) {
            try {
                watchdog.shutdown();
                if (!watchdog.awaitTermination(5, TimeUnit.SECONDS)) {
                    watchdog.shutdownNow();
                }
            } catch (InterruptedException e) {
                watchdog.shutdownNow();
                Thread.currentThread().interrupt();
            }
            watchdog = null;
        }

        if (healthChecker != null) {
            try {
                healthChecker.shutdown();
                if (!healthChecker.awaitTermination(5, TimeUnit.SECONDS)) {
                    healthChecker.shutdownNow();
                }
            } catch (InterruptedException e) {
                healthChecker.shutdownNow();
                Thread.currentThread().interrupt();
            }
            healthChecker = null;
        }

        if (bleScanner != null) {
            try {
                bleScanner.stop();
                bleScanner = null;
            } catch (Exception e) {
                Log.e(TAG, "BLE Scanner temizleme hatası: " + e.getMessage());
            }
        }

        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "AuraMesh BLE Scanner",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("AuraMesh ağını aktif tutuyor - BLE scanning");
            channel.enableVibration(false);
            channel.setSound(null, null);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        PendingIntent pending = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("AuraMesh BLE Taraması")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(pending)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setAutoCancel(false)
                .build();
    }

    @SuppressWarnings("unused")
    public AuraBleScanner getBleScanner() {
        return bleScanner;
    }

    @SuppressWarnings("unused")
    public boolean isServiceRunning() {
        return isServiceRunning.get() && bleScanner != null;
    }
}
