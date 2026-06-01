package org.example.auramesh.hardware.Bluetooth;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.util.Log;

import org.example.auramesh.data.models.AuraMessage;
import org.example.auramesh.events.HardwareToRouterEvents.*;
import org.example.auramesh.events.HardwareToRouterEvents.Client.*;
import org.example.auramesh.events.HardwareToRouterEvents.Server.*;
import org.example.auramesh.events.RouterToHardwareEvents.*;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressLint("MissingPermission")
public class AuraGattService {

    private static final String TAG = "AuraGattService";

    // BLE Servis ve Karakteristik UUID'leri (AppConstants içindeki AURA_MESH_UUID'ni kullandık)
    private static final UUID SERVICE_UUID = UUID.fromString("0000A0EA-0000-1000-8000-00805F9B34FB");
    private static final UUID CHAR_WRITE_UUID = UUID.fromString("1111A0EA-0000-1000-8000-00805F9B34FB"); // Client -> Server yazar
    private static final UUID CHAR_NOTIFY_UUID = UUID.fromString("3333A0EA-0000-1000-8000-00805F9B34FB"); // Server -> Client'a bildirir
    private static final UUID CLIENT_CONFIG_DESCRIPTOR = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context context;
    private final BluetoothManager bluetoothManager;
    private final Gson gson = new Gson();

    private BluetoothGattServer gattServer;
    private BluetoothGatt activeClientGatt;
    private BluetoothDevice connectedDevice;

    private final AtomicBoolean isHardwareBusy = new AtomicBoolean(false);

    public enum Role { CLIENT, SERVER, NONE }
    private Role currentRole = Role.NONE;

    // Veri Biriktirme ve Parçalama (Chunking) Yapıları
    private final List<String> remoteInventoryBuffer = new ArrayList<>();
    private final List<AuraMessage> receivedMessagesBuffer = new ArrayList<>();
    private final StringBuilder rxBuffer = new StringBuilder(); // Gelen parçaları birleştirir

    private final ConcurrentLinkedQueue<TxChunk> txQueue = new ConcurrentLinkedQueue<>();
    private boolean isTxBusy = false;
    private int currentMtu = 20; // Varsayılan değer, bağlanınca 512 istenecek

    // --- ÖZEL VERİ SINIFI ---
    private static class TxChunk {
        byte[] data;
        String completionAction;
        TxChunk(byte[] data, String completionAction) {
            this.data = data;
            this.completionAction = completionAction;
        }
    }

    public AuraGattService(Context context) {
        this.context = context;
        this.bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        EventBus.getDefault().register(this);
        startListeningAsServer();
    }

    // ==========================================
    // 1. SERVER (DİNLEYİCİ) KURULUMU
    // ==========================================
    private void startListeningAsServer() {
        if (gattServer != null) return;

        gattServer = bluetoothManager.openGattServer(context, gattServerCallback);
        BluetoothGattService service = new BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);

        BluetoothGattCharacteristic writeChar = new BluetoothGattCharacteristic(CHAR_WRITE_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);

        BluetoothGattCharacteristic notifyChar = new BluetoothGattCharacteristic(CHAR_NOTIFY_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ);

        // Client'ın Notify'a abone olabilmesi için Descriptor ekliyoruz
        notifyChar.addDescriptor(new BluetoothGattDescriptor(CLIENT_CONFIG_DESCRIPTOR,
                BluetoothGattDescriptor.PERMISSION_WRITE | BluetoothGattDescriptor.PERMISSION_READ));

        service.addCharacteristic(writeChar);
        service.addCharacteristic(notifyChar);
        gattServer.addService(service);
    }

    private final BluetoothGattServerCallback gattServerCallback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {

                // 🚨 BÜYÜK DÜZELTME (ECHO KORUMASI) 🚨
                // Eğer biz zaten bu cihaza Client olarak bağlanıyorsak, Android'in
                // fırlattığı bu "Sunucu Yankısını" görmezden gel! Kendi bağlantımızı boğma!
                if (currentRole == Role.CLIENT && connectedDevice != null &&
                        device.getAddress().equals(connectedDevice.getAddress())) {
                    return;
                }

                if (isHardwareBusy.compareAndSet(false, true)) {
                    connectedDevice = device;
                    currentRole = Role.SERVER;
                    // Router'a biri bağlandı, kabul ediyor musun diye soruyoruz
                    EventBus.getDefault().post(new IncomingBluetoothConnectionEvent(device));
                } else {
                    gattServer.cancelConnection(device); // Gerçek bir davetsiz misafirse reddet
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                errorCloseConnection();
            }
        }

        @Override
        public void onMtuChanged(BluetoothDevice device, int mtu) {
            currentMtu = mtu;
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId, BluetoothGattDescriptor descriptor, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
            if (CLIENT_CONFIG_DESCRIPTOR.equals(descriptor.getUuid())) {

                // ANDROID BUG KORUMASI: Eğer Android 'Bağlandın' demeyi unuttuysa,
                // işlemi burada yakalayıp Rolümüzü zorla SERVER yapıyoruz!
                if (currentRole == Role.NONE) {
                    if (isHardwareBusy.compareAndSet(false, true)) {
                        connectedDevice = device;
                        currentRole = Role.SERVER;
                    }
                }

                if (currentRole == Role.SERVER) {
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
                    EventBus.getDefault().post(new BluetoothConnectedAsServerEvent());
                } else {
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null);
                }
            }
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId, BluetoothGattCharacteristic characteristic, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
            if (responseNeeded) gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
            if (CHAR_WRITE_UUID.equals(characteristic.getUuid())) {
                processIncomingChunk(value);
            }
        }

        @Override
        public void onNotificationSent(BluetoothDevice device, int status) {
            isTxBusy = false;
            processTxQueue(); // Önceki parça gitti, sıradakini fırlat
        }
    };

    // ==========================================
    // 2. CLIENT (BAĞLANICI) KURULUMU
    // ==========================================
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConnectWithBluetoothCommand(ConnectWithBluetoothCommandEvent event) {
        if (!isHardwareBusy.compareAndSet(false, true)) {
            EventBus.getDefault().post(new BluetoothConnectionFailedEvent());
            return;
        }

        currentRole = Role.CLIENT;
        connectedDevice = event.targetDevice.physicalDevice;
        EventBus.getDefault().post(new BluetoothConnectingAsClientEvent());

        // GATT Bağlantısını Başlat (Eşleşme/Pair olmadan doğrudan tünel açar)
        activeClientGatt = connectedDevice.connectGatt(context, false, gattClientCallback, BluetoothDevice.TRANSPORT_LE);
    }

    private final BluetoothGattCallback gattClientCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.requestMtu(512); // Bağlanır bağlanmaz büyük boru istiyoruz!
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                errorCloseConnection();
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            currentMtu = mtu;
            gatt.discoverServices(); // MTU ayarlandı, şimdi servisleri bulalım
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            BluetoothGattCharacteristic notifyChar = gatt.getService(SERVICE_UUID).getCharacteristic(CHAR_NOTIFY_UUID);
            gatt.setCharacteristicNotification(notifyChar, true);

            BluetoothGattDescriptor descriptor = notifyChar.getDescriptor(CLIENT_CONFIG_DESCRIPTOR);
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            gatt.writeDescriptor(descriptor);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            // Abone olduk, iletişim tüneli tam kapasite hazır!
            EventBus.getDefault().post(new BluetoothConnectedAsClientEvent());
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (CHAR_NOTIFY_UUID.equals(characteristic.getUuid())) {
                processIncomingChunk(characteristic.getValue());
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            isTxBusy = false;
            processTxQueue(); // Önceki parça gitti, sıradakini fırlat
        }
    };


    // ==========================================
    // 3. PARÇALAMA (CHUNKING) VE İLETİM MOTORU
    // ==========================================
    private void enqueueData(String data, String completionAction) {
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        int chunkSize = currentMtu - 3; // GATT paket başlığı payı

        for (int i = 0; i < bytes.length; i += chunkSize) {
            int length = Math.min(bytes.length - i, chunkSize);
            byte[] chunk = new byte[length];
            System.arraycopy(bytes, i, chunk, 0, length);

            // Sadece son parçaya tetikleyici (completionAction) ekle
            String action = (i + chunkSize >= bytes.length) ? completionAction : null;
            txQueue.add(new TxChunk(chunk, action));
        }
        processTxQueue();
    }

    private synchronized void processTxQueue() {
        if (isTxBusy || txQueue.isEmpty()) return;

        TxChunk nextChunk = txQueue.peek();
        isTxBusy = true;

        if (currentRole == Role.CLIENT) {
            BluetoothGattCharacteristic writeChar = activeClientGatt.getService(SERVICE_UUID).getCharacteristic(CHAR_WRITE_UUID);
            writeChar.setValue(nextChunk.data);
            activeClientGatt.writeCharacteristic(writeChar);
        } else if (currentRole == Role.SERVER) {
            BluetoothGattCharacteristic notifyChar = gattServer.getService(SERVICE_UUID).getCharacteristic(CHAR_NOTIFY_UUID);
            notifyChar.setValue(nextChunk.data);
            gattServer.notifyCharacteristicChanged(connectedDevice, notifyChar, false);
        }

        // Gönderim kuyruğa verildi, listeden çıkar ve eğer bittiyse Router'a haber ver
        txQueue.poll();
        if (nextChunk.completionAction != null) {
            handleTxCompletion(nextChunk.completionAction);
        }
    }

    private void handleTxCompletion(String action) {
        if ("INV_SENT".equals(action)) {
            if (currentRole == Role.SERVER) EventBus.getDefault().post(new InventoryTransmittedAsServerEvent());
            else if (currentRole == Role.CLIENT) EventBus.getDefault().post(new InventoryTransmittedAsClientEvent());
        } else if ("MSG_SENT".equals(action)) {
            if (currentRole == Role.SERVER) EventBus.getDefault().post(new MessagesTransmittedAsServerEvent());
            else if (currentRole == Role.CLIENT) EventBus.getDefault().post(new MessagesTransmittedAsClientEvent());
        }
    }

    // ==========================================
    // 4. GELEN VERİYİ BİRLEŞTİRME VE ÇÖZME (PARSING)
    // ==========================================
    private synchronized void processIncomingChunk(byte[] chunk) {
        rxBuffer.append(new String(chunk, StandardCharsets.UTF_8));

        int newlineIndex;
        // Satır satır oku (Çünkü her paketimizin sonuna \n koyacağız)
        while ((newlineIndex = rxBuffer.indexOf("\n")) != -1) {
            String packet = rxBuffer.substring(0, newlineIndex);
            rxBuffer.delete(0, newlineIndex + 1); // Okunan kısmı tampondan sil

            parsePacket(packet);
        }
    }

    private void parsePacket(String packet) {
        if (packet.equals("INV_DONE")) {
            List<String> fullInventory = new ArrayList<>(remoteInventoryBuffer);
            // KATI KURAL: Sadece eminsen zarf fırlat, "else" kullanıp körü körüne fırlatma!
            if (currentRole == Role.SERVER) {
                EventBus.getDefault().post(new RemoteInventoryReceivedAsServerEvent(fullInventory));
            } else if (currentRole == Role.CLIENT) {
                EventBus.getDefault().post(new RemoteInventoryReceivedAsClientEvent(fullInventory));
            }
        }
        else if (packet.equals("SYNC_DONE")) {
            if (currentRole == Role.SERVER) {
                EventBus.getDefault().post(new MessagesReceivedAsServerEvent());
            } else if (currentRole == Role.CLIENT) {
                EventBus.getDefault().post(new MessagesReceivedAsClientEvent());
            }
        }
        else if (packet.startsWith("INV_PAYLOAD:")) {
            String jsonList = packet.substring(12);
            List<String> inventory = gson.fromJson(jsonList, new TypeToken<List<String>>(){}.getType());
            if (inventory != null) remoteInventoryBuffer.addAll(inventory);
        }
        else if (packet.startsWith("MSG_PAYLOAD:")) {
            String jsonMsg = packet.substring(12);
            AuraMessage msg = gson.fromJson(jsonMsg, AuraMessage.class);
            receivedMessagesBuffer.add(msg);
        }
    }

    // ==========================================
    // 5. GOSSIP ROUTER'DAN GELEN EMİRLER
    // ==========================================
    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onTransmitInventoryCommand(TransmitInventoryCommandEvent event) {
        String json = gson.toJson(event.myInventory);
        enqueueData("INV_PAYLOAD:" + json + "\n", null);
        enqueueData("INV_DONE\n", "INV_SENT");
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onTransmitMessagesCommand(TransmitMessagesCommandEvent event) {
        for (AuraMessage msg : event.messagesToSend) {
            enqueueData("MSG_PAYLOAD:" + gson.toJson(msg) + "\n", null);
        }
        enqueueData("SYNC_DONE\n", "MSG_SENT");
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onDisconnectGracefulCommand(DisconnectGracefulCommandEvent event) {
        safeCloseConnection();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRejectConnectionRequest(RejectConnectionRequestCommandEvent event) {
        if (connectedDevice != null && connectedDevice.equals(event.bluetoothDevice)) {
            silentCloseConnection();
        }
    }

    // ==========================================
    // 6. TEMİZLİK VE KAPANIŞ METOTLARI
    // ==========================================
    private void clearHardware() {
        if (activeClientGatt != null) {
            activeClientGatt.disconnect();
            activeClientGatt.close();
            activeClientGatt = null;
        }
        if (gattServer != null && connectedDevice != null) {
            gattServer.cancelConnection(connectedDevice);
        }
        connectedDevice = null;
        currentRole = Role.NONE;
        isTxBusy = false;
        txQueue.clear();
        rxBuffer.setLength(0);
        isHardwareBusy.set(false); // Kilidi açıyoruz, yeni birine hazır!
    }

    private void errorCloseConnection() {
        if (!isHardwareBusy.compareAndSet(true, false)) return;
        EventBus.getDefault().post(new BluetoothConnectionFailedEvent());
        receivedMessagesBuffer.clear();
        remoteInventoryBuffer.clear();
        clearHardware();
    }

    private void silentCloseConnection() {
        if (!isHardwareBusy.compareAndSet(true, false)) return;
        receivedMessagesBuffer.clear();
        remoteInventoryBuffer.clear();
        clearHardware();
    }

    private void safeCloseConnection() {
        if (!isHardwareBusy.compareAndSet(true, false)) return;
        EventBus.getDefault().post(new BluetoothDisconnectedGracefulEvent(new ArrayList<>(receivedMessagesBuffer)));
        receivedMessagesBuffer.clear();
        remoteInventoryBuffer.clear();
        clearHardware();
    }

    public void onDestroy() {
        EventBus.getDefault().unregister(this);
        clearHardware();
        if (gattServer != null) {
            gattServer.close();
            gattServer = null;
        }
    }
}