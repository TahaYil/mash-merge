package org.example.auramesh.hardware.BluetoothLE;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import org.example.auramesh.Register.BleAdvertiseRegister;
import org.example.auramesh.events.HardwareToRegisterEvents.TriggerTtlCleanupEvent;
import org.example.auramesh.utils.AppConstants;
import org.greenrobot.eventbus.EventBus;

import java.math.BigInteger;
import java.nio.ByteBuffer;

@SuppressLint("MissingPermission")
public class AuraBleAdvertiser {

    private static final String TAG = "AuraAdvertiser";
    private static final ParcelUuid AURA_MESH_UUID = AppConstants.AURA_MESH_UUID;
    private static final long HEARTBEAT_INTERVAL = 10_000;

    private final BluetoothLeAdvertiser advertiser;
    private final Handler heartbeatHandler = new Handler(Looper.getMainLooper());
    private boolean isAdvertising = false;

    public AuraBleAdvertiser() {
        this.advertiser = BluetoothAdapter.getDefaultAdapter().getBluetoothLeAdvertiser();
    }

    // Sınıfın en üstüne (isAdvertising değişkeninin yanına) bu iki hafızayı ekle:
    private String lastAdvertisedHash = "";
    private int lastAdvertisedCount = -1;

    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            EventBus.getDefault().post(new TriggerTtlCleanupEvent());

            String currentHash = BleAdvertiseRegister.getInstance().getMessageHash();
            int currentCount = BleAdvertiseRegister.getInstance().getMessageCount();

            if (currentHash != null && (!currentHash.equals(lastAdvertisedHash) || currentCount != lastAdvertisedCount)) {
                lastAdvertisedHash = currentHash;
                lastAdvertisedCount = currentCount;

                heartbeatHandler.postDelayed(() -> restartAdvertising(), 100);
                Log.d(TAG, "Hash değişti! Yeni MAC adresi ile yayın güncelleniyor.");
            } else {
                Log.d(TAG, "Değişiklik yok, radyo frekansı bozulmadan yayına devam ediliyor.");
            }

            heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL);
        }
    };

    public void start() {
        if (advertiser != null) {
            heartbeatHandler.post(heartbeatRunnable);
        }
    }

    public void stop() {
        heartbeatHandler.removeCallbacksAndMessages(null);
        if (isAdvertising) {
            advertiser.stopAdvertising(advertiseCallback);
            isAdvertising = false;
        }
    }

    private void restartAdvertising() {
        if (advertiser == null) return;

        if (isAdvertising) {
            advertiser.stopAdvertising(advertiseCallback);
        }

        String myNodeId = BleAdvertiseRegister.getInstance().getMyNodeId();
        String hashHex = BleAdvertiseRegister.getInstance().getMessageHash();
        int msgCount = BleAdvertiseRegister.getInstance().getMessageCount();

        if (myNodeId == null) return;

        byte[] payload = buildPayload(myNodeId, hashHex, msgCount);

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setConnectable(true)
                .build();

        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceData(AURA_MESH_UUID, payload)
                .build();

        advertiser.startAdvertising(settings, data, advertiseCallback);
        isAdvertising = true;
    }

    private byte[] buildPayload(String nodeIdHex, String hashHex, int count) {
        ByteBuffer buffer = ByteBuffer.allocate(18);
        buffer.put(hexStringToByteArray(nodeIdHex));
        buffer.putLong(new BigInteger(hashHex, 16).longValue());
        buffer.putInt(count);
        return buffer.array();
    }

    private byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            Log.d(TAG, "advertise başladı");
        }
    };
}