package org.example.auramesh;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "AuraMeshPrefs";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. Kayıtlı profil var mı kontrol et
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedName = prefs.getString("profile_name", "");
        boolean isProfileSaved = !savedName.isEmpty();

        // 2. İzinler verilmiş mi kontrol et
        boolean arePermissionsGranted = checkEssentialPermissions();

        // Eğer hem profil var hem izinler tamsa doğrudan HomeActivity'ye git
        if (isProfileSaved && arePermissionsGranted) {
            // Servisleri başlat ve yönlendir
            ((AuraMeshApplication) getApplication()).startMeshServices();
            startActivity(new Intent(MainActivity.this, HomeActivity.class));
            finish();
            return;
        }

        // Eğer eksik varsa giriş ekranını göster
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        findViewById(R.id.btnStart).setOnClickListener(v -> {
            // İzinler eksikse izin ekranına, izinler tam ama profil eksikse profil ekranına git
            if (!arePermissionsGranted) {
                startActivity(new Intent(MainActivity.this, PermissionActivity.class));
            } else {
                startActivity(new Intent(MainActivity.this, ProfileActivity.class));
            }
        });
    }

    private boolean checkEssentialPermissions() {
        // Konum izni kontrolü
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }

        // Android 12+ Bluetooth izinleri kontrolü
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        
        return true;
    }
}
