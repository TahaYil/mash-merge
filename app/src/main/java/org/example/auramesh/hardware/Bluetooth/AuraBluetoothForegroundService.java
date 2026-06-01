package org.example.auramesh.hardware.Bluetooth;

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
 * AuraBluetoothForegroundService - GATT Service'i Foreground Service olarak çalıştırır
 *
 * Eski cihazlarda scanner'ın kapanmasını önlemek için:
 * - Foreground Service ile sistem tarafından sonlandırılması engellenir
 * - Watchdog mekanizması GATT Service'in sağlığını kontrol eder
 * - Health Checker BLE bağlantısının aktif kalmasını sağlar
 */
public class AuraBluetoothForegroundService extends Service {
    private static final String TAG = "AuraFgService";
    private static final String CHANNEL_ID = "aura_bt_channel";
    private static final int NOTIF_ID = 101;

    private static final long WATCHDOG_INTERVAL_SECONDS = 30;
    private static final long HEALTH_CHECK_INTERVAL_SECONDS = 60;

    private AuraGattService gattService;
    private ScheduledExecutorService watchdog;
    private ScheduledExecutorService healthChecker;
    private final AtomicBoolean isServiceRunning = new AtomicBoolean(false);
    private BluetoothAdapter bluetoothAdapter;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Foreground Service oluşturuluyor...");
        createNotificationChannel();
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        startForeground(NOTIF_ID, buildNotification("AuraMesh Ağı Aktif"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Foreground Service başlatılıyor...");

        if (!isServiceRunning.compareAndSet(false, true)) {
            Log.d(TAG, "Servis zaten çalışıyor");
            return START_STICKY;
        }

        // GATT Service'i oluştur
        if (gattService == null) {
            try {
                gattService = new AuraGattService(getApplicationContext());
                Log.d(TAG, "GATT Service oluşturuldu");
            } catch (Exception e) {
                Log.e(TAG, "GATT Service hatası: " + e.getMessage());
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
            Thread t = new Thread(r, "AuraWatchdog");
            t.setDaemon(false);
            return t;
        });

        watchdog.scheduleWithFixedDelay(() -> {
            try {
                if (gattService != null && bluetoothAdapter != null) {
                    if (!bluetoothAdapter.isEnabled()) {
                        Log.w(TAG, "Bluetooth kapalı");
                    }
                    Log.d(TAG, "Watchdog: GATT kontrol ediliyor");
                }
            } catch (Throwable e) {
                Log.e(TAG, "Watchdog hatası: " + e.getMessage());
            }
        }, WATCHDOG_INTERVAL_SECONDS, WATCHDOG_INTERVAL_SECONDS, TimeUnit.SECONDS);

        Log.d(TAG, "Watchdog başlatıldı");
    }

    /**
     * Health Checker: GATT Service'in aktif kalmasını sağlar
     * Eski cihazlarda scanner'ın kapanmasını önler
     */
    @SuppressWarnings("resource")
    private void startHealthChecker() {
        if (healthChecker != null) return;

        healthChecker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AuraHealthChecker");
            t.setDaemon(false);
            return t;
        });

        healthChecker.scheduleWithFixedDelay(() -> {
            try {
                if (gattService != null) {
                    Log.d(TAG, "Health Check: GATT Service aktif");
                    updateNotification("AuraMesh Ağı Aktif ✓");
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
        Log.d(TAG, "Foreground Service durduruluyor...");
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

        if (gattService != null) {
            try {
                gattService.onDestroy();
                gattService = null;
            } catch (Exception e) {
                Log.e(TAG, "GATT temizleme hatası: " + e.getMessage());
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
                    "AuraMesh Bluetooth",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("AuraMesh ağını aktif tutuyor - Constant BLE scanning");
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
                .setContentTitle("AuraMesh Ağı Aktif")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(pending)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setAutoCancel(false)
                .build();
    }

    @SuppressWarnings("unused")
    public AuraGattService getGattService() {
        return gattService;
    }

    @SuppressWarnings("unused")
    public boolean isServiceRunning() {
        return isServiceRunning.get() && gattService != null;
    }
}
