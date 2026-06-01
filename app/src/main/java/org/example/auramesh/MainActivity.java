package org.example.auramesh;

import android.content.Intent;
import android.os.Bundle;
import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        findViewById(R.id.btnStart).setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, PermissionActivity.class);
            startActivity(intent);
        });
    }
}
