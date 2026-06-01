package org.example.auramesh.Register;
import org.example.auramesh.events.RegisterToRouterEvents.DatabaseStateUpdatedEvent;
import org.example.auramesh.events.RegisterToRouterEvents.MyNodeIdUpdatedEvent;
import org.greenrobot.eventbus.EventBus;

public class BleAdvertiseRegister {

    // Singleton Instance
    private static volatile BleAdvertiseRegister instance;

    private volatile String myNodeId = "000000000000";

    private volatile int messageCount = 0;

    private volatile String messageHash = "0000000000000000";

    private BleAdvertiseRegister() {

    }

    public static BleAdvertiseRegister getInstance() {
        if (instance == null) {
            synchronized (BleAdvertiseRegister.class) {
                if (instance == null) {
                    instance = new BleAdvertiseRegister();
                }
            }
        }
        return instance;
    }

    public void updateNodeId(String nodeId) {
        if (nodeId != null && !nodeId.isEmpty()) {
            this.myNodeId = nodeId;
            EventBus.getDefault().post(new MyNodeIdUpdatedEvent(nodeId));
        }
    }

    public void updateDatabaseState(int currentMessageCount, String currentDatabaseHash) {
        this.messageCount = currentMessageCount;
        this.messageHash = currentDatabaseHash;
        EventBus.getDefault().post(new DatabaseStateUpdatedEvent(currentMessageCount, currentDatabaseHash));
    }

    public String getMyNodeId(){
        return this.myNodeId;
    }

    public int getMessageCount() {
        return messageCount;
    }

    public String getMessageHash() {
        return messageHash;
    }
}