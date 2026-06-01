package org.example.auramesh.events.RegisterToRouterEvents;

public class DatabaseStateUpdatedEvent {
    int newMessageCount;
    String newHash;

    public DatabaseStateUpdatedEvent(int newMessageCount, String newHash) {
        this.newMessageCount = newMessageCount;
        this.newHash = newHash;
    }
}
