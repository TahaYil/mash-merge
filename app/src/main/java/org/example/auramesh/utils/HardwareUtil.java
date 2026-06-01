package org.example.auramesh.utils;

import org.example.auramesh.Register.NeighborRegister;
import org.example.auramesh.data.models.NeighborDevice;
import org.example.auramesh.events.HardwareToRouterEvents.NeighborUpdatedEvent;
import org.greenrobot.eventbus.EventBus;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class HardwareUtil {
    private static final Map<String, Long> lastSeenMap = new ConcurrentHashMap<>();

    //todo: ileride aha uzun  veya kısa süre beklenmesini gerektiren uygulama modları arasında (örn: ulra güç tasarrufu vb) değiştirilir diye appconst a eklenip mod değişikliğinde orası değiştirilebilir bilmiyorum emin değilim üstüne düşünmedim
    private static final long timeout = 30_000;

    //taha sana yardımcı olması için yazdım blescannerda buffer ile registerdakileri bu method yardımıyla karşılaştıracaksın
    // todo: ACİL OLARAK MAC ADRESİNİN OTOMATİK DEĞİŞTİĞİ DURUMLARDA NE YAPACAKSIN BİRR ÇARE BUL.
    public static void SyncNeighborList(Map<String, NeighborDevice> newNeighborMap) {
        NeighborRegister neighborRegister = NeighborRegister.getInstance();
        Map<String, NeighborDevice> oldNeighborMap = neighborRegister.getNeighborMap();

        boolean changed = false;

        long currentTime = System.currentTimeMillis();

        for (Map.Entry<String, NeighborDevice> entry : newNeighborMap.entrySet()) {
            String nodeId = entry.getKey();
            NeighborDevice newDevice = entry.getValue();

            lastSeenMap.put(nodeId, currentTime);

            NeighborDevice oldDevice = oldNeighborMap.get(nodeId); // macadress aslında nodeid
            if (oldDevice == null) {
                neighborRegister.addOrUpdateNeighbor(nodeId, newDevice);
                changed = true;
            } else if (!oldDevice.messageHash.equals(newDevice.messageHash) ||
                    oldDevice.messageCount != newDevice.messageCount ||
                    !oldDevice.physicalDevice.getAddress().equals(newDevice.physicalDevice.getAddress())) {

                neighborRegister.addOrUpdateNeighbor(nodeId, newDevice);
                changed = true;
            }
        }

        for (String nodeId : oldNeighborMap.keySet()) {
            Long lastSeen = lastSeenMap.get(nodeId);

            if (lastSeen == null || (currentTime - lastSeen > timeout)) {
                neighborRegister.deleteNeighbor(nodeId);
                lastSeenMap.remove(nodeId);
                changed = true;
            }
        }

        if (changed) {
            EventBus.getDefault().post(new NeighborUpdatedEvent());
        }
    }
}