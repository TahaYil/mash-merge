package org.example.auramesh;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.ImageView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.example.auramesh.data.local.AuraDatabase;
import org.example.auramesh.data.models.AuraMessage;
import org.example.auramesh.events.RouterToUiEvent.UpdateUiEvent;
import org.example.auramesh.events.UiToRouterEvents.UserSendMessageEvent;
import org.example.auramesh.utils.AppConstants;
import org.example.auramesh.utils.AuraIdentityManager;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

public class MessageActivity extends AppCompatActivity {

    private RecyclerView rvMessages;
    private MessageAdapter adapter;
    private EditText edtMessageInput;
    private ImageView btnSendMessage;
    private List<AuraMessage> messageList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_message);

        rvMessages = findViewById(R.id.rvMessages);
        edtMessageInput = findViewById(R.id.edtMessageInput);
        btnSendMessage = findViewById(R.id.btnSendMessage);

        rvMessages.setLayoutManager(new LinearLayoutManager(this));
        adapter = new MessageAdapter(messageList);
        rvMessages.setAdapter(adapter);

        rvMessages.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (bottom < oldBottom) scrollToBottom();
        });

        loadMessagesFromDb();

        btnSendMessage.setOnClickListener(v -> {
            String text = edtMessageInput.getText().toString().trim();
            if (!text.isEmpty()) {
                sendMessage(text);
                edtMessageInput.setText("");
            }
        });

        findViewById(R.id.navHome).setOnClickListener(v -> {
            startActivity(new Intent(this, HomeActivity.class));
            finish();
        });

        findViewById(R.id.navProfile).setOnClickListener(v -> {
            startActivity(new Intent(this, ProfileActivity.class));
            finish();
        });
    }

    private void loadMessagesFromDb() {
        Executors.newSingleThreadExecutor().execute(() -> {
            List<AuraMessage> dbMessages = AuraDatabase.getDatabase(this).auraMessageDao().getAllMessages();
            runOnUiThread(() -> {
                messageList.clear();
                messageList.addAll(dbMessages);
                adapter.notifyDataSetChanged();
                scrollToBottom();
            });
        });
    }

    private void sendMessage(String text) {
        String myNodeId = AuraIdentityManager.getMyNodeId(this);
        String messageId = UUID.randomUUID().toString();
        AuraMessage msg = new AuraMessage(
                messageId,
                myNodeId,
                AppConstants.TARGET_PUBLIC,
                text,
                System.currentTimeMillis(),
                AppConstants.DEFAULT_MESSAGE_TTL
        );
        EventBus.getDefault().post(new UserSendMessageEvent(msg));
        // Optimistic UI update
        runOnUiThread(() -> {
            messageList.add(msg);
            adapter.notifyItemInserted(messageList.size() - 1);
            scrollToBottom();
        });
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onUpdateUi(UpdateUiEvent event) {
        loadMessagesFromDb();
    }

    private void scrollToBottom() {
        if (adapter != null && adapter.getItemCount() > 0) {
            rvMessages.post(() -> rvMessages.scrollToPosition(adapter.getItemCount() - 1));
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        EventBus.getDefault().unregister(this);
    }
}
