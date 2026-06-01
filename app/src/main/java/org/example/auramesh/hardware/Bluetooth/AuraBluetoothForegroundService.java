package org.example.auramesh.hardware.Bluetooth;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.example.auramesh.R;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class AuraBluetoothForegroundService extends Service {
    private static final String TAG = "AuraFgService";
    private static final String CHANNEL_ID = "aura_bt_channel";
    private static final int NOTIF_ID = 101;

    private AuraBluetoothService bluetoothService;
    private ScheduledExecutorService watchdog;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Foreground Service oluşturuluyor...");
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Foreground Service başlatılıyor...");

        if (bluetoothService == null) {
            bluetoothService = new AuraBluetoothService();
        }

        if (watchdog == null) {
            watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "AuraWatchdog");
                t.setDaemon(false);
                return t;
            });

            // Her 30 saniyede bir server'ın dinlediğini kontrol et
            watchdog.scheduleAtFixedRate(() -> {
                try {
                    if (bluetoothService != null) {
                        bluetoothService.ensureServerListening();
                    }
                } catch (Throwable e) {
                    Log.e(TAG, "Watchdog hatası: " + e.getMessage());
                }
            }, 30, 30, TimeUnit.SECONDS);
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Foreground Service durduruluyor...");

        if (watchdog != null) {
            watchdog.shutdownNow();
            watchdog = null;
        }

        if (bluetoothService != null) {
            bluetoothService.stopServerListening();
            bluetoothService.onDestroy();
            bluetoothService = null;
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
            channel.setDescription("AuraMesh ağını aktif tutuyor");

            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("AuraMesh Ağı Aktif")
                .setContentText("Bluetooth dinlemesi devam ediyor...")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    public AuraBluetoothService getBluetoothService() {
        return bluetoothService;
    }
}

