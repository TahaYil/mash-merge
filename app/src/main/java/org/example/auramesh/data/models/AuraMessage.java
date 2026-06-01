package org.example.auramesh.data.models;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import org.example.auramesh.utils.AppConstants;

import java.sql.Timestamp;

@Entity(tableName = "auraMessages")
public class AuraMessage {
    //todo: bunu tekrar tekrar kaydediyoruz appconstant al bir de sos ekle target uuid sos ise yine targetpublic_sos olacak vb.
    public static final String TARGET_PUBLIC = "BROADCAST_ALL";
    @PrimaryKey
    @NonNull
    public String messageId;
    public String senderUuid;   // Gönderen cihaz
    public String targetUuid;   // eğer sos mesajı ya da grup mesajı ise TARGET_PUBLIC olacak yani new AuraMessage(..., targetUuid = AuraMessage.TARGET_PUBLIC, ...) gibi bir kullanım olacak
    public String payload;
    public long timestamp;
    public long ttl;

    public AuraMessage(@NonNull String messageId, String senderUuid, String targetUuid, String payload, long timestamp, long ttl) {
        this.messageId = messageId;
        this.senderUuid = senderUuid;
        this.targetUuid = targetUuid;
        this.payload = payload;
        this.timestamp = timestamp;
    }
}