package org.example.auramesh;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.MotionEvent;
import android.widget.ImageView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import org.example.auramesh.hardware.Bluetooth.AuraBluetoothForegroundService;
import org.example.auramesh.hardware.BluetoothLE.AuraBleScannerForegroundService;

public class HomeActivity extends AppCompatActivity {

    private Handler handler = new Handler();
    private Runnable sosRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        // Mesh servisleri ve Foreground Service'leri başlat
        ((AuraMeshApplication) getApplication()).startMeshServices();
        startBluetoothServices();

        ImageView btnSos = findViewById(R.id.btnSos);
        btnSos.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                sosRunnable = () -> startActivity(new Intent(HomeActivity.this, AfetActivity.class));
                handler.postDelayed(sosRunnable, 3000);
            } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                handler.removeCallbacks(sosRunnable);
            }
            return true;
        });

        findViewById(R.id.topProfileArea).setOnClickListener(v ->
            startActivity(new Intent(this, ProfileActivity.class)));

        findViewById(R.id.topBatteryArea).setOnClickListener(v ->
            Toast.makeText(this, "Ağ Durumu: Aktif", Toast.LENGTH_SHORT).show());

        findViewById(R.id.navMessage).setOnClickListener(v ->
            startActivity(new Intent(this, MessageActivity.class)));

        findViewById(R.id.navProfile).setOnClickListener(v ->
            startActivity(new Intent(this, ProfileActivity.class)));
    }

    /**
     * BLE Scanner ve GATT Bluetooth Foreground Service'lerini başlat
     * Eski cihazlarda sistem tarafından sonlandırılmasını önler
     */
    private void startBluetoothServices() {
        try {
            // BLE Scanner Foreground Service
            Intent bleScannerIntent = new Intent(this, AuraBleScannerForegroundService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(bleScannerIntent);
            } else {
                startService(bleScannerIntent);
            }

            // GATT Bluetooth Foreground Service
            Intent gattIntent = new Intent(this, AuraBluetoothForegroundService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(gattIntent);
            } else {
                startService(gattIntent);
            }
        } catch (Exception e) {
            Toast.makeText(this, "Bluetooth servisleri başlatılamadı", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(sosRunnable);
    }
}
