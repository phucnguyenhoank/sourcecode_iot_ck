package com.example.traffic_sign;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class MainActivity extends AppCompatActivity {

    private final String TAG = "MainActivity";

    private EditText espIpInput;

    private EditText camIpInput;

    private final String dataServerIp = "192.168.128.230";
    private final String controlIp = "192.168.46.29";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        espIpInput = findViewById(R.id.espIpInput);
        camIpInput = findViewById(R.id.camIpInput);

        Log.i(TAG, espIpInput.getText().toString());
        Log.i(TAG, camIpInput.getText().toString());

        Button startDetectButton = findViewById(R.id.startDetectButton);
        startDetectButton.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, DetectTrafficSignActivity.class);
            startActivity(intent);
        });

        Button startHumanControlButton = findViewById(R.id.startHumanControlButton);
        startHumanControlButton.setOnClickListener(v -> {
            String ip = espIpInput.getText().toString().trim();
            if (isValidIp(ip)) {
                Intent intent = new Intent(MainActivity.this, HumanControlActivity.class);
                intent.putExtra("ESP32_IP", ip);
                startActivity(intent);
            } else {
                Toast.makeText(this, "Please enter a valid IP address", Toast.LENGTH_SHORT).show();
            }
        });

        Button startOneLineControlButton = findViewById(R.id.startOneLineControlButton);
        startOneLineControlButton.setOnClickListener(v -> {
            String ip = espIpInput.getText().toString().trim();
            String camIp = camIpInput.getText().toString().trim();
            if (isValidIp(ip)) {
                Intent intent = new Intent(MainActivity.this, OneLineControlActivity.class);
                intent.putExtra("ESP32_IP", ip);
                intent.putExtra("ESP32CAM_IP", camIp);
                startActivity(intent);
            } else {
                Toast.makeText(this, "Please enter a valid IP address", Toast.LENGTH_SHORT).show();
            }
        });

        Button startRacingHumanControlButton = findViewById(R.id.startRacingHumanControlButton);
        startRacingHumanControlButton.setOnClickListener(v -> {
            String ip = espIpInput.getText().toString().trim();
            if (isValidIp(ip)) {
                Intent intent = new Intent(MainActivity.this, RacingHumanControlActivity.class);
                intent.putExtra("ESP32_IP", ip);
                intent.putExtra("DATA_SERVER_IP", dataServerIp);
                startActivity(intent);
            } else {
                Toast.makeText(this, "Please enter a valid IP address", Toast.LENGTH_SHORT).show();
            }
        });

//        Button startOneLineRecordingButton = findViewById(R.id.startOneLineRecordingButton);
//        startOneLineRecordingButton.setOnClickListener(v -> {
//            String ip = espIpInput.getText().toString().trim();
//            if (isValidIp(ip)) {
//                Intent intent = new Intent(MainActivity.this, OneLineRecordingActivity.class);
//                intent.putExtra("ESP32_IP", ip);
//                intent.putExtra("DATA_SERVER_IP", dataServerIp);
//                startActivity(intent);
//            } else {
//                Toast.makeText(this, "Please enter a valid IP address", Toast.LENGTH_SHORT).show();
//            }
//        });

//        Button startRacingRecordingButton = findViewById(R.id.startRacingRecordingButton);
//        startRacingRecordingButton.setOnClickListener(v -> {
//            String ip = espIpInput.getText().toString().trim();
//            if (isValidIp(ip)) {
//                Intent intent = new Intent(MainActivity.this, RacingRecordingActivity.class);
//                intent.putExtra("CONTROL_IP", controlIp);
//                startActivity(intent);
//            } else {
//                Toast.makeText(this, "Please enter a valid IP address", Toast.LENGTH_SHORT).show();
//            }
//        });

        Button startYawTestButton = findViewById(R.id.startYawTestButton);
        startYawTestButton.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, YawActivity.class);
            startActivity(intent);
        });

        Button startTrafficSignControlButton = findViewById(R.id.startTrafficSignControlButton);
        startTrafficSignControlButton.setOnClickListener(v -> {
            String espIp = espIpInput.getText().toString().trim();
            String camIp = camIpInput.getText().toString().trim();
            if (isValidIp(espIp) && isValidIp(camIp)) {
                Intent intent = new Intent(MainActivity.this, TrafficSignControlActivity.class);
                intent.putExtra("ESP32_IP", espIp);
                intent.putExtra("ESP32CAM_IP", camIp);
                startActivity(intent);
            } else {
                Toast.makeText(this, "Please enter a valid IP address", Toast.LENGTH_SHORT).show();
            }
        });

        Button startDataCollectionActivity = findViewById(R.id.startDataCollectionActivity);
        startDataCollectionActivity.setOnClickListener(v -> {
            String espIp = espIpInput.getText().toString().trim();
            String camIp = camIpInput.getText().toString().trim();
            if (isValidIp(espIp) && isValidIp(camIp)) {
                Intent intent = new Intent(MainActivity.this, DataCollectionActivity.class);
                intent.putExtra("ESP32_IP", espIp);
                intent.putExtra("ESP32CAM_IP", camIp);
                startActivity(intent);
            } else {
                Toast.makeText(this, "Please enter a valid IP address", Toast.LENGTH_SHORT).show();
            }
        });

    }

    private boolean isValidIp(String ip) {
        // Simple IP validation; you can enhance this as needed
        return ip.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
    }
}