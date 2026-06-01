package org.example.auramesh.hardware.Bluetooth;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.annotation.RequiresPermission;
import androidx.core.app.ActivityCompat;

import org.example.auramesh.data.models.AuraMessage;
import org.example.auramesh.events.HardwareToRouterEvents.BluetoothConnectionFailedEvent;
import org.example.auramesh.events.HardwareToRouterEvents.BluetoothDisconnectedGracefulEvent;
import org.example.auramesh.events.HardwareToRouterEvents.BluetoothDisconnectedUngracefulEvent;
import org.example.auramesh.events.HardwareToRouterEvents.Client.BluetoothConnectedAsClientEvent;
import org.example.auramesh.events.HardwareToRouterEvents.Client.BluetoothConnectingAsClientEvent;
import org.example.auramesh.events.HardwareToRouterEvents.Client.InventoryTransmittedAsClientEvent;
import org.example.auramesh.events.HardwareToRouterEvents.Client.MessagesReceivedAsClientEvent;
import org.example.auramesh.events.HardwareToRouterEvents.Client.MessagesTransmittedAsClientEvent;
import org.example.auramesh.events.HardwareToRouterEvents.Client.RemoteInventoryReceivedAsClientEvent;
import org.example.auramesh.events.HardwareToRouterEvents.Server.BluetoothConnectedAsServerEvent;
import org.example.auramesh.events.HardwareToRouterEvents.Server.IncomingBluetoothConnectionEvent;
import org.example.auramesh.events.HardwareToRouterEvents.Server.InventoryTransmittedAsServerEvent;
import org.example.auramesh.events.HardwareToRouterEvents.Server.MessagesReceivedAsServerEvent;
import org.example.auramesh.events.HardwareToRouterEvents.Server.MessagesTransmittedAsServerEvent;
import org.example.auramesh.events.HardwareToRouterEvents.Server.RemoteInventoryReceivedAsServerEvent;
import org.example.auramesh.events.RouterToHardwareEvents.ConnectWithBluetoothCommandEvent;
import org.example.auramesh.events.RouterToHardwareEvents.DisconnectGracefulCommandEvent;
import org.example.auramesh.events.RouterToHardwareEvents.RejectConnectionRequestCommandEvent;
import org.example.auramesh.events.RouterToHardwareEvents.TransmitInventoryCommandEvent;
import org.example.auramesh.events.RouterToHardwareEvents.TransmitMessagesCommandEvent;
import org.example.auramesh.utils.AppConstants;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;


//TODO: ÖNEMLİ ileride yapılacak şeyler
//todo: 1- AZ ÖNEMLİ aynı bağlantıda bufferlar belli bir büyüklüğe geldiğinde asenkron mimari göz önüne alınarak mesajları parça parça kaydedebiliriz.
//todo: 2- ÇOK ÖNEMLİ mesajları okurken, tek bir mesajı stringden nesneye dönüştürmeye çalışırken exception alırsak sadece o mesajı yazmamalıyız şu an direkt bağlantıyı kapatıyoruz errorla
//todo: 3- 2. todo için ve daha iyi bir senkronizasyon için invSync->msgSync->control şeklinde bir yapı kurulabilir yani alınan mesajlar alınan envanterle örtüşüyor mu ya da yeni hash karşının yeni hash i ile eşleşiyor mu diye bakılabilir.
//todo: 4- ÇOK ÖNEMLİ herhangi bir sebepten ötürü bazı uç durumlarda sistem kilitlenmişse (örn: bir sebepten bir connecting veya connected durumunda vb 3 saat kaldık (deadnode)) bu durumları engelleycek bir mekanizma yazılmalı. mesela her bağlantı için bir maksimum bağlantı süresi falan belirlenebilir bu süre aşılırsa resetAll() gibi bir şey çalıştırılabilir belki tam olarak emin değilim.
//todo: 5- ÖNEMLİ kullanıcıdan izinleri al
//todo: 6- ÖNEMLİ bluetooth kapalıysa kullanıcıya açmasını söyle
//todo: 7- ÖNEMLİ eğer bir süredir hiç komşu yoksa bu servisi pil tasarrufu için kapat sürekli dinleme yapmasın.
@SuppressLint("MissingPermission")
public class AuraBluetoothService {
    private static final String TAG = "AuraBtService";
    private static final UUID RFCOMM_UUID = AppConstants.RFCOMM_SOCKET_UUID;
    private final BluetoothAdapter adapter;
    private final ExecutorService networkExecutor = Executors.newFixedThreadPool(3);
    private final Gson gson = new Gson();
    private BluetoothServerSocket serverSocket;
    private BluetoothSocket activeSocket;
    private DataOutputStream outputStream;
    private DataInputStream inputStream;
    private final AtomicBoolean isHardwareBusy = new AtomicBoolean(false);
    public enum Role {
        CLIENT,
        SERVER,
        NONE
    }
    private Role currentRole = Role.NONE;
    private final List<String> remoteInventoryBuffer = new ArrayList<>();
    private final List<AuraMessage> receivedMessagesBuffer = new ArrayList<>();

    public AuraBluetoothService() {
        this.adapter = BluetoothAdapter.getDefaultAdapter();
        EventBus.getDefault().register(this);
        startListeningAsServer();
    }

    private void startListeningAsServer() {
        networkExecutor.execute(() -> {
            try {
                serverSocket = adapter.listenUsingInsecureRfcommWithServiceRecord("AuraMesh", RFCOMM_UUID);
                while (!Thread.currentThread().isInterrupted()) {
                    BluetoothSocket socket = serverSocket.accept();
                    if (socket != null) {
                        if (!isHardwareBusy.compareAndSet(false, true)) {
                            socket.close();
                            continue;
                        }
                        currentRole = Role.SERVER;
                        EventBus.getDefault().post(new IncomingBluetoothConnectionEvent(socket.getRemoteDevice()));
                        manageConnectedSocket(socket);

                    }
                }
            } catch (Exception e) {
                currentRole = Role.NONE;
                Log.e(TAG, "Sunucu socket hatası: " + e.getMessage());
            }
        });
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onConnectWithBluetoothCommand(ConnectWithBluetoothCommandEvent event) {
        networkExecutor.execute(() -> {
            if (!isHardwareBusy.compareAndSet(false, true)) {
                EventBus.getDefault().post(new BluetoothConnectionFailedEvent());
                return;
            }
            try {
                currentRole = Role.CLIENT;
                EventBus.getDefault().post(new BluetoothConnectingAsClientEvent());

                BluetoothDevice device = event.targetDevice.physicalDevice;
                BluetoothSocket clientSocket = device.createInsecureRfcommSocketToServiceRecord(RFCOMM_UUID);
                adapter.cancelDiscovery();
                clientSocket.connect();

                manageConnectedSocket(clientSocket);
            } catch (Exception e) {
                //todo: burada closeconnection metodlarından biri kullanılacak
                isHardwareBusy.set(false);
                currentRole = Role.NONE;
                EventBus.getDefault().post(new BluetoothConnectionFailedEvent());
            }
        });
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onRejectConnectionRequest(RejectConnectionRequestCommandEvent event) {
        networkExecutor.execute(() -> {
            if (activeSocket != null && activeSocket.getRemoteDevice().equals(event.bluetoothDevice)) {
                silentCloseConnection();
            }
        });
    }

    private void manageConnectedSocket(BluetoothSocket socket) {
        this.activeSocket = socket;
        this.remoteInventoryBuffer.clear();
        this.receivedMessagesBuffer.clear();

        try {
            inputStream = new DataInputStream(socket.getInputStream());
            outputStream = new DataOutputStream(socket.getOutputStream());

            networkExecutor.execute(this::readerThreadTask);

            if (currentRole == Role.SERVER) {
                EventBus.getDefault().post(new BluetoothConnectedAsServerEvent());
            } else {
                EventBus.getDefault().post(new BluetoothConnectedAsClientEvent());
            }

        } catch (Exception e) { errorCloseConnection(); }
    }

    private void readerThreadTask() {
        try {
            while (!Thread.currentThread().isInterrupted() && activeSocket.isConnected()) {
                String incomingStr = inputStream.readUTF();

                if (incomingStr.equals("<INV_TRADE_DONE>")) {
                    List<String> fullInventory = new ArrayList<>(remoteInventoryBuffer);
                    if (currentRole == Role.SERVER) {
                        EventBus.getDefault().post(new RemoteInventoryReceivedAsServerEvent(fullInventory));
                    } else {
                        EventBus.getDefault().post(new RemoteInventoryReceivedAsClientEvent(fullInventory));
                    }
                }
                else if (incomingStr.equals("<SYNC_COMPLETE>")) {
                    if (currentRole == Role.SERVER) {
                        EventBus.getDefault().post(new MessagesReceivedAsServerEvent());
                    } else {
                        EventBus.getDefault().post(new MessagesReceivedAsClientEvent());
                    }
                }
                else if (incomingStr.startsWith("INV:")) {
                    String jsonList = incomingStr.substring(4);
                    List<String> inventory = gson.fromJson(jsonList, new TypeToken<List<String>>(){}.getType());
                    if (inventory != null) remoteInventoryBuffer.addAll(inventory);
                }
                else if (incomingStr.startsWith("MSG:")) {
                    String jsonMsg = incomingStr.substring(4);
                    AuraMessage msg = gson.fromJson(jsonMsg, AuraMessage.class);
                    receivedMessagesBuffer.add(msg);
                }
            }
        } catch (Exception e) { errorCloseConnection(); }
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onTransmitInventoryCommand(TransmitInventoryCommandEvent event) {
        networkExecutor.execute(() -> {
            try {
                String json = gson.toJson(event.myInventory);
                outputStream.writeUTF("INV:" + json);
                outputStream.writeUTF("<INV_TRADE_DONE>");
                outputStream.flush();

                if (currentRole == Role.SERVER) {
                    EventBus.getDefault().post(new InventoryTransmittedAsServerEvent());
                } else {
                    EventBus.getDefault().post(new InventoryTransmittedAsClientEvent());
                }
            } catch (Exception e) { errorCloseConnection(); }
        });
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onTransmitMessagesCommand(TransmitMessagesCommandEvent event) {
        networkExecutor.execute(() -> {
            try {
                for (AuraMessage msg : event.messagesToSend) {
                    outputStream.writeUTF("MSG:" + gson.toJson(msg));
                    outputStream.flush();
                }
                outputStream.writeUTF("<SYNC_COMPLETE>");
                outputStream.flush();

                if (currentRole == Role.SERVER) {
                    EventBus.getDefault().post(new MessagesTransmittedAsServerEvent());
                } else {
                    EventBus.getDefault().post(new MessagesTransmittedAsClientEvent());
                }
            } catch (Exception e) { errorCloseConnection(); }
        });
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onDisconnectGracefulCommand(DisconnectGracefulCommandEvent event) {
        networkExecutor.execute(this::safeCloseConnection);
    }

    //todo: ileride partial sync olacak. hata durumunda bile buffera alabildiğimiz mesajları yükleyeceğiz.
    private void errorCloseConnection() {
        if (!isHardwareBusy.compareAndSet(true, false)) return;

        try { if (activeSocket != null) activeSocket.close(); } catch (Exception ignored) {}
        currentRole = Role.NONE;

        EventBus.getDefault().post(new BluetoothDisconnectedUngracefulEvent());

        receivedMessagesBuffer.clear();
        remoteInventoryBuffer.clear();

    }

    private void silentCloseConnection() {
        if (!isHardwareBusy.compareAndSet(true, false)) return; // KİLİT

        try { if (activeSocket != null) activeSocket.close(); } catch (Exception ignored) {}
        currentRole = Role.NONE;

        receivedMessagesBuffer.clear();
        remoteInventoryBuffer.clear();
    }

    private void safeCloseConnection() {
        if (!isHardwareBusy.compareAndSet(true, false)) return; // KİLİT

        try { if (activeSocket != null) activeSocket.close(); } catch (Exception ignored) {}
        currentRole = Role.NONE;

        EventBus.getDefault().post(new BluetoothDisconnectedGracefulEvent(new ArrayList<>(receivedMessagesBuffer)));

        receivedMessagesBuffer.clear();
        remoteInventoryBuffer.clear();
    }

    public void onDestroy() {
        EventBus.getDefault().unregister(this);
        networkExecutor.shutdownNow();
    }
}