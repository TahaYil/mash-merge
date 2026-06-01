package org.example.auramesh;

import android.app.Application;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import org.example.auramesh.hardware.Bluetooth.AuraBluetoothForegroundService;
import org.example.auramesh.hardware.Bluetooth.AuraBluetoothService;
import org.example.auramesh.hardware.BluetoothLE.AuraBleAdvertiser;
import org.example.auramesh.hardware.BluetoothLE.AuraBleScanner;
import org.example.auramesh.routing.GossipRouter;
import org.example.auramesh.routing.StateManager;

public class AuraMeshApplication extends Application {

    private static final String TAG = "AuraMeshApp";
    private AuraBluetoothService bluetoothService;
    private AuraBleAdvertiser bleAdvertiser;
    private AuraBleScanner bleScanner;
    private GossipRouter gossipRouter;
    private StateManager stateManager;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "AuraMesh Application oluşturuluyor");
    }

    public void startMeshServices() {
        Log.d(TAG, "Mesh servisleri başlatılıyor...");

        if (bluetoothService == null) {
            stateManager = new StateManager(this);
            bluetoothService = new AuraBluetoothService();
            gossipRouter = new GossipRouter(this);
            bleAdvertiser = new AuraBleAdvertiser();
            bleScanner = new AuraBleScanner();
            bleAdvertiser.start();
            bleScanner.start();
        }

        // Foreground service'i başlat (Bluetooth dinlemesini arka planda tutmak için)
        startBluetoothForegroundService();
    }

    private void startBluetoothForegroundService() {
        try {
            Intent serviceIntent = new Intent(this, AuraBluetoothForegroundService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            Log.d(TAG, "Bluetooth Foreground Service başlatıldı");
        } catch (Exception e) {
            Log.e(TAG, "Foreground Service başlatılamadı: " + e.getMessage());
        }
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        Log.d(TAG, "AuraMesh Application sonlandırılıyor");

        if (bluetoothService != null) bluetoothService.onDestroy();
        if (gossipRouter != null) gossipRouter.onDestroy();
        if (stateManager != null) stateManager.onDestroy();
        if (bleAdvertiser != null) bleAdvertiser.stop();
        if (bleScanner != null) bleScanner.stop();

        // Foreground service'i durdur
        stopBluetoothForegroundService();
    }

    private void stopBluetoothForegroundService() {
        try {
            Intent serviceIntent = new Intent(this, AuraBluetoothForegroundService.class);
            stopService(serviceIntent);
            Log.d(TAG, "Bluetooth Foreground Service durduruldu");
        } catch (Exception e) {
            Log.e(TAG, "Foreground Service durdurulamadı: " + e.getMessage());
        }
    }
}
