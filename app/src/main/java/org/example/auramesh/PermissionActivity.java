package org.example.auramesh;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.List;

public class PermissionActivity extends AppCompatActivity {

    private LinearLayout itemLocation, itemNotification, itemBattery, itemAll;
    private TextView btnCancel, btnContinue;

    private final ActivityResultLauncher<String[]> permissionLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
            boolean allGranted = true;
            for (Boolean granted : result.values()) {
                if (!granted) { allGranted = false; break; }
            }
            if (allGranted) {
                proceedToProfile();
            } else {
                Toast.makeText(this, "Lütfen devam etmek için tüm izinleri aktif hale getirin.", Toast.LENGTH_SHORT).show();
            }
        });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_permission);

        itemLocation = findViewById(R.id.itemLocation);
        itemNotification = findViewById(R.id.itemNotification);
        itemBattery = findViewById(R.id.itemBattery);
        itemAll = findViewById(R.id.itemAll);

        btnCancel = findViewById(R.id.btnCancel);
        btnContinue = findViewById(R.id.btnContinue);

        setupPermissionItem(itemLocation, "Konum ve Bluetooth",
                "AuraMesh, afet anında internet olmasa dahi çevredeki cihazları bulmak ve güvenli bir iletişim ağı oluşturmak için Bluetooth ve Konum izinlerini kullanır.");
        setupPermissionItem(itemNotification, "Bildirimler",
                "Hayati önem taşıyan acil durum uyarılarını ve çevrenizden gelen yardım çağrılarını anında görebilmeniz için bildirim izni gereklidir.");
        setupPermissionItem(itemBattery, "Pil Optimizasyonu Kapat",
                "Uygulamanın arka planda kesintisiz çalışabilmesi ve her an yardım sinyali gönderebilmesi için pil kısıtlamalarının kaldırılması önerilir.");
        setupPermissionItem(itemAll, "Tümüne İzin Ver",
                "Tek bir işlemle gerekli tüm sistem izinlerini onaylayabilir ve uygulamayı en yüksek verimle kullanmaya başlayabilirsiniz.");

        FrameLayout toggleAll = itemAll.findViewById(R.id.customToggle);
        toggleAll.setOnClickListener(v -> {
            boolean newState = !toggleAll.isSelected();
            setToggleState(itemLocation, newState);
            setToggleState(itemNotification, newState);
            setToggleState(itemBattery, newState);
            setToggleState(itemAll, newState);
        });

        btnCancel.setOnClickListener(v -> finish());

        btnContinue.setOnClickListener(v -> {
            boolean isLocationAccepted = itemLocation.findViewById(R.id.customToggle).isSelected();
            boolean isNotificationAccepted = itemNotification.findViewById(R.id.customToggle).isSelected();
            boolean isBatteryAccepted = itemBattery.findViewById(R.id.customToggle).isSelected();

            if (isLocationAccepted && isNotificationAccepted && isBatteryAccepted) {
                requestRealPermissions();
            } else {
                Toast.makeText(this, "Lütfen devam etmek için tüm izinleri aktif hale getirin.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void requestRealPermissions() {
        List<String> permsNeeded = new ArrayList<>();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permsNeeded.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                permsNeeded.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permsNeeded.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        if (permsNeeded.isEmpty()) {
            proceedToProfile();
        } else {
            permissionLauncher.launch(permsNeeded.toArray(new String[0]));
        }
    }

    private void proceedToProfile() {
        // Mesh servislerini başlat
        ((AuraMeshApplication) getApplication()).startMeshServices();
        Intent intent = new Intent(PermissionActivity.this, ProfileActivity.class);
        startActivity(intent);
    }

    private void setupPermissionItem(LinearLayout item, String title, String description) {
        TextView txtTitle = item.findViewById(R.id.txtPermissionTitle);
        TextView txtDesc = item.findViewById(R.id.txtPermissionDesc);
        View arrowArea = item.findViewById(R.id.arrowArea);
        ImageView arrowIcon = item.findViewById(R.id.arrowIcon);
        FrameLayout toggle = item.findViewById(R.id.customToggle);

        txtTitle.setText(title);
        txtDesc.setText(description);

        if (item.getId() != R.id.itemAll) {
            toggle.setOnClickListener(v -> {
                boolean isOn = toggle.isSelected();
                setToggleState(item, !isOn);
                updateAllToggleState();
            });
        }

        arrowArea.setOnClickListener(v -> {
            if (txtDesc.getVisibility() == View.VISIBLE) {
                txtDesc.setVisibility(View.GONE);
                arrowIcon.animate().rotation(0f).setDuration(200).start();
            } else {
                txtDesc.setVisibility(View.VISIBLE);
                arrowIcon.animate().rotation(180f).setDuration(200).start();
            }
        });
    }

    private void updateAllToggleState() {
        boolean allOn = itemLocation.findViewById(R.id.customToggle).isSelected() &&
                        itemNotification.findViewById(R.id.customToggle).isSelected() &&
                        itemBattery.findViewById(R.id.customToggle).isSelected();
        setToggleState(itemAll, allOn);
    }

    private void setToggleState(LinearLayout item, boolean active) {
        FrameLayout toggle = item.findViewById(R.id.customToggle);
        View circle = item.findViewById(R.id.toggleCircle);
        toggle.setSelected(active);
        if (active) {
            toggle.getChildAt(0).setBackgroundResource(R.drawable.bg_toggle_on);
            circle.animate().translationX(42f).setDuration(200).start();
        } else {
            toggle.getChildAt(0).setBackgroundResource(R.drawable.bg_toggle_off);
            circle.animate().translationX(0f).setDuration(200).start();
        }
    }
}
