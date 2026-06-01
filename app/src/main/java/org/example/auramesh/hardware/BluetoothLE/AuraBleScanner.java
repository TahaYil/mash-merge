package org.example.auramesh.hardware.BluetoothLE;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import org.example.auramesh.data.models.NeighborDevice;
import org.example.auramesh.utils.AppConstants;
import org.example.auramesh.utils.HardwareUtil;
import org.greenrobot.eventbus.EventBus;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@SuppressLint("MissingPermission")
public class AuraBleScanner {

    private static final String TAG = "AuraScanner";
    private static final ParcelUuid AURA_MESH_UUID = AppConstants.AURA_MESH_UUID;
    private static final long SCAN_SYNC_INTERVAL = 5_000;
    private static final long WATCHDOG_INTERVAL = 30_000;

    private final BluetoothLeScanner scanner;
    private final Handler syncHandler = new Handler(Looper.getMainLooper());
    private final Handler watchdogHandler = new Handler(Looper.getMainLooper());
    private final Map<String, NeighborDevice> temporaryBuffer = new ConcurrentHashMap<>();
    private long lastScanResultTime = System.currentTimeMillis();

    public AuraBleScanner() {
        this.scanner = BluetoothAdapter.getDefaultAdapter().getBluetoothLeScanner();
    }

    private final Runnable watchdogRunnable = new Runnable() {
        @Override
        public void run() {
            if (System.currentTimeMillis() - lastScanResultTime > WATCHDOG_INTERVAL) {
                Log.w(TAG, "Watchdog: Tarama donmuş, yeniden başlatılıyor.");
                stop();
                start();
            }
            watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL);
        }
    };

    private final Runnable syncRunnable = new Runnable() {
        @Override
        public void run() {
            if (!temporaryBuffer.isEmpty()) {
                HardwareUtil.SyncNeighborList(new HashMap<>(temporaryBuffer));
                temporaryBuffer.clear();
            }
            syncHandler.postDelayed(this, SCAN_SYNC_INTERVAL);
        }
    };

    public void start() {
        if (scanner == null) return;

        ScanFilter filter = new ScanFilter.Builder()
                .setServiceData(AURA_MESH_UUID, null)
                .build();

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED) // LOW_LATENCY yerine BALANCED
                .setMatchMode(ScanSettings.MATCH_MODE_STICKY) // Sistemin kısıtlamasını azaltır
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build();

        scanner.startScan(Collections.singletonList(filter), settings, scanCallback);
        Log.d(TAG, "BLE Dinleyici başlatıldı.");

        syncHandler.post(syncRunnable);
        watchdogHandler.post(watchdogRunnable);
        lastScanResultTime = System.currentTimeMillis();
    }

    public void stop() {
        if (scanner != null) {
            try {
                scanner.stopScan(scanCallback);
            } catch (Exception e) {
                Log.e(TAG, "Stop hatası: " + e.getMessage());
            }
        }
        syncHandler.removeCallbacks(syncRunnable);
        watchdogHandler.removeCallbacks(watchdogRunnable);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            lastScanResultTime = System.currentTimeMillis(); // Watchdog'u resetle

            if (result.getScanRecord() == null) return;

            byte[] payload = result.getScanRecord().getServiceData(AURA_MESH_UUID);
            if (payload == null || payload.length < 18) return;

            ByteBuffer buffer = ByteBuffer.wrap(payload);
            byte[] nodeBytes = new byte[6];
            buffer.get(nodeBytes);
            String targetNodeId = byteArrayToHexString(nodeBytes);

            long hashLong = buffer.getLong();
            String targetHash = String.format("%016X", hashLong);
            int targetCount = buffer.getInt();

            NeighborDevice device = new NeighborDevice(
                    targetNodeId,
                    result.getRssi(),
                    targetCount,
                    targetHash,
                    result.getDevice()
            );

            if (!temporaryBuffer.containsKey(targetNodeId) || !device.equals(temporaryBuffer.get(targetNodeId))) {
                if (!temporaryBuffer.containsKey(targetNodeId))
                {
                    Log.d(TAG, "Yeni cihaz bulundu: " + targetNodeId + " mac: " + device.physicalDevice.getAddress() + " RSSI: " + device.rssi);
                } else {
                    Log.d(TAG, "Cihaz güncellendi: " + targetNodeId + " mac: " + device.physicalDevice.getAddress() + " RSSI: " + device.rssi);
                }
                temporaryBuffer.put(targetNodeId, device);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            Log.e(TAG, "Tarama başarısız oldu, kod: " + errorCode);
            syncHandler.postDelayed(() -> start(), 5000);
        }
    };

    private String byteArrayToHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02X", b));
        return sb.toString();
    }
}