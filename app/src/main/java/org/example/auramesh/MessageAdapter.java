package org.example.auramesh;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import org.example.auramesh.data.models.AuraMessage;
import org.example.auramesh.utils.AuraIdentityManager;
import android.graphics.drawable.GradientDrawable;
import java.util.List;

public class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.MessageViewHolder> {

    private final List<AuraMessage> messageList;

    public MessageAdapter(List<AuraMessage> messageList) {
        this.messageList = messageList;
    }

    @NonNull
    @Override
    public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_message, parent, false);
        return new MessageViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MessageViewHolder holder, int position) {
        AuraMessage msg = messageList.get(position);
        holder.txtContent.setText(msg.payload);

        // Acil durum mesajları kırmızı, normal mesajlar mor
        int color;
        if (msg.payload != null && (msg.payload.startsWith("ENKAZ") || msg.payload.startsWith("YARAL"))) {
            color = Color.parseColor("#FF1F1F");
        } else if (msg.payload != null && msg.payload.startsWith("GÜVENDEYİM")) {
            color = Color.parseColor("#9ABD3F");
        } else {
            color = Color.parseColor("#7F56D9");
        }

        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setCornerRadius(30f);
        shape.setColor(color);
        holder.txtContent.setBackground(shape);
    }

    @Override
    public int getItemCount() {
        return messageList.size();
    }

    static class MessageViewHolder extends RecyclerView.ViewHolder {
        TextView txtContent;
        MessageViewHolder(@NonNull View itemView) {
            super(itemView);
            txtContent = itemView.findViewById(R.id.txtMessageContent);
        }
    }
}
