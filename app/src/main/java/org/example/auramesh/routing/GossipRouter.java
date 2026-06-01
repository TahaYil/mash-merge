package org.example.auramesh.routing;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.example.auramesh.Register.BleAdvertiseRegister;
import org.example.auramesh.Register.NeighborRegister;
import org.example.auramesh.data.local.AuraDatabase;
import org.example.auramesh.data.local.AuraMessageDao;
import org.example.auramesh.data.models.AuraMessage;
import org.example.auramesh.data.models.NeighborDevice;
import org.example.auramesh.events.HardwareToRegisterEvents.NewMessagesSavedToDatabaseEvent;
import org.example.auramesh.events.HardwareToRouterEvents.*;
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
import org.example.auramesh.events.RegisterToRouterEvents.DatabaseStateUpdatedEvent;
import org.example.auramesh.events.RegisterToRouterEvents.MyNodeIdUpdatedEvent;
import org.example.auramesh.events.RouterToHardwareEvents.*;
import org.example.auramesh.events.UiToRouterEvents.UserSendMessageEvent;
import org.example.auramesh.utils.RouterUtil;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.List;
import java.util.Map;
import java.util.Random;

public class GossipRouter {

    private static final String TAG = "GossipRouter";
    private final AuraMessageDao messageDao;

    private enum RouterState {
        IDLE,
        CONNECTING,
        CONNECTED_AS_CLIENT,
        CONNECTED_AS_SERVER,
        BACKOFF,
        WAITING_JITTER,
        WAITING_NODE_ID,
    }

    private volatile RouterState currentState;
    private int backoffMultiplier = 1;
    private final Handler timeHandler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private boolean isLocalInvSent = false;
    private boolean isRemoteInvReceived = false;
    private boolean isLocalMsgsSent = false;
    private boolean isRemoteMsgsReceived = false;
    private List<String> currentRemoteInventory = null;

    public GossipRouter(Context context) {
        Log.i(TAG, "GossipRouter başlatılıyor...");
        this.messageDao = AuraDatabase.getDatabase(context).auraMessageDao();
        EventBus.getDefault().register(this);

        if (BleAdvertiseRegister.getInstance().getMyNodeId() == null || BleAdvertiseRegister.getInstance().getMyNodeId().equals("000000000000")) {
            this.currentState = RouterState.WAITING_NODE_ID;
            Log.d(TAG, "[Constructor] NodeId yok, WAITING_NODE_ID durumuna geçildi.");
        } else {
            dropToIdleAndListen();
        }
    }

    private void resetSyncFlags() {
        Log.d(TAG, "[resetSyncFlags] Senkronizasyon bayrakları sıfırlandı.");
        isLocalInvSent = false;
        isRemoteInvReceived = false;
        isLocalMsgsSent = false;
        isRemoteMsgsReceived = false;
        currentRemoteInventory = null;
    }

    private void dropToIdleAndListen() {
        Log.d(TAG, "[dropToIdleAndListen] Durum IDLE'a çekiliyor. Önceki Durum: " + currentState);
        currentState = RouterState.IDLE;
        resetSyncFlags();
        timeHandler.removeCallbacksAndMessages(null);

        int cooldownMs = random.nextInt(2000) + 1500;
        Log.d(TAG, "[dropToIdleAndListen] " + cooldownMs + " ms sonra checkPendingNeighborsAndAct tetiklenecek.");
        timeHandler.postDelayed(() -> {
            if (currentState == RouterState.IDLE) {
                checkPendingNeighborsAndAct();
            } else {
                Log.d(TAG, "[dropToIdleAndListen - Gecikmeli] Süre doldu ama durum IDLE değil (" + currentState + "), kontrol iptal.");
            }
        }, cooldownMs);
    }

    // =========================================================================
    // SERVER OLARAK BAĞLANDIĞIMDA KULLANACAĞIMIZ METOTLAR
    // =========================================================================

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onIncomingBluetoothConnectionEvent(IncomingBluetoothConnectionEvent event) {
        Log.i(TAG, "[onIncomingBluetoothConnectionEvent] Tetiklendi. Mevcut Durum: " + currentState);
        if (currentState == RouterState.IDLE || currentState == RouterState.BACKOFF || currentState == RouterState.WAITING_JITTER) {

            Log.d(TAG, "[onIncomingBluetoothConnectionEvent] Durum -> CONNECTING olarak güncelleniyor.");
            currentState = RouterState.CONNECTING;
            timeHandler.removeCallbacksAndMessages(null);

            boolean kabulEdilebilirMi = true; // Karar mekanizması

            if (kabulEdilebilirMi) {
                Log.d(TAG, "[onIncomingBluetoothConnectionEvent] Bağlantı kabul edildi.");
            } else {
                Log.w(TAG, "[onIncomingBluetoothConnectionEvent] Bağlantı REDDEDİLDİ. RejectCommand yollanıyor.");
                EventBus.getDefault().post(new RejectConnectionRequestCommandEvent(event.bluetoothDevice));
                dropToIdleAndListen();
            }
        } else {
            Log.w(TAG, "[onIncomingBluetoothConnectionEvent] Router meşgul (" + currentState + "). Bağlantı REDDEDİLDİ.");
            EventBus.getDefault().post(new RejectConnectionRequestCommandEvent(event.bluetoothDevice));
        }
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onBluetoothConnectedAsServerEvent(BluetoothConnectedAsServerEvent event) {
        Log.i(TAG, "[onBluetoothConnectedAsServerEvent] Server olarak tam bağlantı sağlandı! Durum -> CONNECTED_AS_SERVER");
        currentState = RouterState.CONNECTED_AS_SERVER;
        backoffMultiplier = 1;
        resetSyncFlags();
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onRemoteInventoryReceivedAsServerEvent(RemoteInventoryReceivedAsServerEvent event) {
        Log.d(TAG, "[SERVER] Karşı tarafın envanteri alındı. (isRemoteInvReceived = true)");
        isRemoteInvReceived = true;
        currentRemoteInventory = event.remoteInventory;

        List<String> myInventory = messageDao.getInventory();
        Log.d(TAG, "[SERVER] Kendi envanterimizi yollama emri veriliyor. Envanter boyutu: " + myInventory.size());
        EventBus.getDefault().post(new TransmitInventoryCommandEvent(myInventory));
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onInventoryTransmittedAsServerEvent(InventoryTransmittedAsServerEvent event) {
        Log.d(TAG, "[SERVER] Kendi envanterimizi başarıyla yolladık. (isLocalInvSent = true)");
        isLocalInvSent = true;
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onMessagesReceivedAsServerEvent(MessagesReceivedAsServerEvent event) {
        Log.d(TAG, "[SERVER] Karşı taraftan eksik mesajlar alındı. (isRemoteMsgsReceived = true)");
        isRemoteMsgsReceived = true;

        List<AuraMessage> messagesToSend = messageDao.getMissingMessages(currentRemoteInventory);
        Log.d(TAG, "[SERVER] Karşı taraf için " + messagesToSend.size() + " adet eksik mesaj yollanıyor.");
        EventBus.getDefault().post(new TransmitMessagesCommandEvent(messagesToSend));

        checkFinalizeSync();
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onMessagesTransmittedAsServerEvent(MessagesTransmittedAsServerEvent event) {
        Log.d(TAG, "[SERVER] Eksik mesajları başarıyla yolladık. (isLocalMsgsSent = true)");
        isLocalMsgsSent = true;
        checkFinalizeSync();
    }


    // =========================================================================
    // CLIENT OLARAK BAĞLANDIĞIMUZDA KULLANACAĞIMIZ METOTLAR
    // =========================================================================

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onBluetoothConnectingAsClientEvent(BluetoothConnectingAsClientEvent event) {
        Log.i(TAG, "[onBluetoothConnectingAsClientEvent] Client olarak bağlantı deneniyor. Durum -> CONNECTING");
        currentState = RouterState.CONNECTING;
        timeHandler.removeCallbacksAndMessages(null);
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onBluetoothConnectedAsClientEvent(BluetoothConnectedAsClientEvent event) {
        Log.i(TAG, "[onBluetoothConnectedAsClientEvent] Client olarak bağlantı sağlandı! Durum -> CONNECTED_AS_CLIENT");
        currentState = RouterState.CONNECTED_AS_CLIENT;
        backoffMultiplier = 1;
        resetSyncFlags();

        List<String> myInventory = messageDao.getInventory();
        Log.d(TAG, "[CLIENT] Kendi envanterimizi yollama emri veriliyor. Envanter boyutu: " + myInventory.size());
        EventBus.getDefault().post(new TransmitInventoryCommandEvent(myInventory));
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onInventoryTransmittedAsClientEvent(InventoryTransmittedAsClientEvent event) {
        Log.d(TAG, "[CLIENT] Kendi envanterimizi başarıyla yolladık. (isLocalInvSent = true)");
        isLocalInvSent = true;
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onRemoteInventoryReceivedAsClientEvent(RemoteInventoryReceivedAsClientEvent event) {
        Log.d(TAG, "[CLIENT] Karşı tarafın envanteri alındı. (isRemoteInvReceived = true)");
        isRemoteInvReceived = true;
        currentRemoteInventory = event.remoteInventory;

        if (isLocalInvSent) {
            List<AuraMessage> messagesToSend = messageDao.getMissingMessages(currentRemoteInventory);
            Log.d(TAG, "[CLIENT] Kendi envanterimiz zaten yollanmıştı, karşı taraf için " + messagesToSend.size() + " adet eksik mesaj yollanıyor.");
            EventBus.getDefault().post(new TransmitMessagesCommandEvent(messagesToSend));
        } else {
            // İŞTE BURAYA DİKKAT! DEADLOCK BURADA OLUŞABİLİR.
            Log.w(TAG, "🚨 [CLIENT DİKKAT] Karşının envanteri geldi ama biz HÂLÂ kendi envanterimizi yollamamışız! (isLocalInvSent=false) Mesaj aktarımı tetiklenemedi!");
        }
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onMessagesTransmittedAsClientEvent(MessagesTransmittedAsClientEvent event) {
        Log.d(TAG, "[CLIENT] Eksik mesajları başarıyla yolladık. (isLocalMsgsSent = true)");
        isLocalMsgsSent = true;
        checkFinalizeSync();
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onRemoteMessagesReceivedAsClientEvent(MessagesReceivedAsClientEvent event) {
        Log.d(TAG, "[CLIENT] Karşı taraftan eksik mesajlar alındı. (isRemoteMsgsReceived = true)");
        isRemoteMsgsReceived = true;
        checkFinalizeSync();
    }

    // =========================================================================
    // YARDIMICI METHODLAR VE SENKRONİZASYON SONLANDIRMA
    // =========================================================================

    private void writeReceivedMessagesToDatabase(List<AuraMessage> receivedMessages) {
        Log.d(TAG, "[writeReceivedMessagesToDatabase] Veritabanına " + receivedMessages.size() + " adet yeni mesaj yazılacak.");
        if (!receivedMessages.isEmpty()) {
            messageDao.insertAll(receivedMessages);
            EventBus.getDefault().post(new NewMessagesSavedToDatabaseEvent(receivedMessages));
        }
    }

    private void checkFinalizeSync() {
        Log.d(TAG, "[checkFinalizeSync] Bayraklar kontrol ediliyor -> " +
                "isLocalMsgsSent: " + isLocalMsgsSent + ", " +
                "isRemoteMsgsReceived: " + isRemoteMsgsReceived + ", " +
                "isLocalInvSent: " + isLocalInvSent + ", " +
                "isRemoteInvReceived: " + isRemoteInvReceived);

        if (isLocalMsgsSent && isRemoteMsgsReceived && isLocalInvSent && isRemoteInvReceived) {
            Log.i(TAG, "[checkFinalizeSync] BÜTÜN BAYRAKLAR TRUE! Senkronizasyon başarılı. Graceful kopma emri yollanıyor.");
            EventBus.getDefault().post(new DisconnectGracefulCommandEvent());
        }
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onDisconnectedGraceful(BluetoothDisconnectedGracefulEvent event) {
        Log.i(TAG, "[onDisconnectedGraceful] Bağlantı GÜVENLİ koptu. Alınan mesajlar işleniyor...");
        writeReceivedMessagesToDatabase(event.remoteMessages);
        resetSyncFlags();
        dropToIdleAndListen();
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onDisconnectedUngraceful(BluetoothDisconnectedUngracefulEvent event) {
        Log.e(TAG, "[onDisconnectedUngraceful] Bağlantı BEKLENMEDİK ŞEKİLDE koptu!");
        currentState = RouterState.IDLE;
        resetSyncFlags();
        dropToIdleAndListen();
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onConnectionFailed(BluetoothConnectionFailedEvent event) {
        Log.e(TAG, "[onConnectionFailed] Bağlantı girişimi BAŞARISIZ! Durum -> BACKOFF");
        currentState = RouterState.BACKOFF;
        int baseDelayMs = 50;
        int backoffTime = baseDelayMs * backoffMultiplier;
        backoffMultiplier = Math.min(backoffMultiplier * 2, 8);

        Log.d(TAG, "[onConnectionFailed] Backoff süresi: " + backoffTime + " ms. Sonra IDLE'a dönülecek.");
        timeHandler.postDelayed(this::dropToIdleAndListen, backoffTime);
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onUserSendMessageEvent(UserSendMessageEvent event) {
        Log.i(TAG, "[onUserSendMessageEvent] Kullanıcı yeni mesaj attı. Veritabanına yazılıyor.");
        messageDao.insert(event.auraMessage);
        EventBus.getDefault().post(new NewMessagesSavedToDatabaseEvent(List.of(event.auraMessage)));

        Log.d(TAG, "[onUserSendMessageEvent] Mevcut Durum: " + currentState + ". Eğer IDLE ise komşular kontrol edilecek.");
        if (currentState == RouterState.IDLE) {
            checkPendingNeighborsAndAct();
        }
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onDatabaseStateUpdated(DatabaseStateUpdatedEvent event) {
        Log.d(TAG, "[onDatabaseStateUpdated] Veritabanı durumu güncellendi. Mevcut Durum: " + currentState);
        if (currentState == RouterState.IDLE) {
            Log.d(TAG, "[onDatabaseStateUpdated] Router IDLE modunda. Komşular saniyesinde kontrol ediliyor.");
            checkPendingNeighborsAndAct();
        }
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onNeighborUpdated(NeighborUpdatedEvent event) {
        // Log.v(TAG, "[onNeighborUpdated] Komşu listesi güncellendi."); // Çok sık tetikleniyorsa bu logu kapalı tutabiliriz, şimdilik sessiz kalsın
        if (currentState == RouterState.IDLE) checkPendingNeighborsAndAct();
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onMyNodeIdUpdated(MyNodeIdUpdatedEvent event) {
        Log.d(TAG, "[onMyNodeIdUpdated] Yeni NodeID geldi: " + event.myNodeId);
        if (event.myNodeId != null && !event.myNodeId.equals("000000000000")){
            dropToIdleAndListen();
        }
    }

    private synchronized void checkPendingNeighborsAndAct() {
        Log.d(TAG, "[checkPendingNeighborsAndAct] KONTROL BAŞLIYOR. Mevcut Durum: " + currentState);

        if (currentState != RouterState.IDLE) {
            Log.d(TAG, "[checkPendingNeighborsAndAct] İPTAL -> Router şu an meşgul.");
            return;
        }

        String myNodeId = BleAdvertiseRegister.getInstance().getMyNodeId();
        String myHash = BleAdvertiseRegister.getInstance().getMessageHash();
        int myMessageCount = BleAdvertiseRegister.getInstance().getMessageCount();

        if (myNodeId == null || myNodeId.equals("000000000000")) {
            Log.d(TAG, "[checkPendingNeighborsAndAct] İPTAL -> Kendi NodeID'miz geçersiz.");
            return;
        }

        Map<String, NeighborDevice> currentNeighbors = NeighborRegister.getInstance().getNeighborMap();
        if (currentNeighbors.isEmpty()) {
            Log.d(TAG, "[checkPendingNeighborsAndAct] İPTAL -> Etrafta hiç komşu yok.");
            return;
        }

        Log.d(TAG, "[checkPendingNeighborsAndAct] " + currentNeighbors.size() + " komşu bulundu. Kontrol ediliyor...");

        for (Map.Entry<String, NeighborDevice> entry : currentNeighbors.entrySet()) {
            String targetNodeId = entry.getKey();
            String targetHash = entry.getValue().messageHash;
            int targetMessageCount = entry.getValue().messageCount;

            Log.d(TAG, " -> Komşu [" + targetNodeId + "] inceleniyor. (Benim Hash: " + myHash + ", Onun Hash: " + targetHash + ")");

            if (!myHash.equals(targetHash) || myMessageCount != targetMessageCount) {
                Log.d(TAG, "    * FARK BULUNDU! Eşleşmeyen Hash veya Mesaj Sayısı.");

                if (RouterUtil.amITheInitiator(myNodeId, targetNodeId)) {
                    Log.i(TAG, "    * Karar: INITIATOR BENİM! Bağlantı başlatılıyor...");
                    initiateConnectionWithJitter(entry.getValue());
                    return;
                } else {
                    Log.d(TAG, "    * Karar: INITIATOR DEĞİLİM. Karşı tarafın bana bağlanmasını bekleyeceğim.");
                }
            } else {
                Log.d(TAG, "    * UYUM: Bu komşu ile zaten %100 senkronizeyiz.");
            }
        }
    }

    private void initiateConnectionWithJitter(NeighborDevice targetDevice) {
        Log.d(TAG, "[initiateConnectionWithJitter] Hedef: " + targetDevice.nodeId + " Durum -> WAITING_JITTER");
        currentState = RouterState.WAITING_JITTER;

        int jitterMs = random.nextInt(100) + 100;
        Log.d(TAG, "[initiateConnectionWithJitter] " + jitterMs + " ms Jitter bekleniyor...");

        timeHandler.postDelayed(() -> {
            if (currentState == RouterState.WAITING_JITTER) {
                Log.i(TAG, "[initiateConnectionWithJitter] Jitter bitti. ConnectWithBluetoothCommandEvent fırlatılıyor!");
                currentState = RouterState.CONNECTING;
                EventBus.getDefault().post(new ConnectWithBluetoothCommandEvent(targetDevice));
            } else {
                Log.w(TAG, "[initiateConnectionWithJitter] Jitter bitti ama Router artık WAITING_JITTER modunda değil! (" + currentState + ")");
            }
        }, jitterMs);
    }

    public void onDestroy() {
        Log.i(TAG, "[onDestroy] GossipRouter kapatılıyor.");
        EventBus.getDefault().unregister(this);
        timeHandler.removeCallbacksAndMessages(null);
    }
}