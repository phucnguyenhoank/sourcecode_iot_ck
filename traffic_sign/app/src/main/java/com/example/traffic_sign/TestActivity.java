package com.example.traffic_sign;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public class TestActivity extends AppCompatActivity implements SensorEventListener {

    private static final String TAG = "TestActivity";
    private static final int ESP32_PORT = 5000;
    private boolean isRight = false;
    private String esp32Ip;
    private DatagramSocket socket;
    private int speedA = 200;
    private int speedB = 200;

    private Button btnLeft, btnStop, btnRight;
    private TextView yawTextView, deltaYawTextView;

    private SensorManager sensorManager;
    private Sensor rotationVectorSensor;

    private final float[] rotationMatrix = new float[9];
    private final float[] orientationAngles = new float[3];

    private float initialYaw;
    private boolean isTracking = false;
    private boolean isFinished = false;

    private Handler handler = new Handler();
    private Runnable sendLeftRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_test);
        initialYaw = 0;
        esp32Ip = getIntent().getStringExtra("ESP32_IP");
        if (esp32Ip == null || esp32Ip.isEmpty()) {
            Log.e(TAG, "ESP32 IP not provided");
            finish();
            return;
        }

        new Thread(() -> {
            try {
                socket = new DatagramSocket();
                Log.d(TAG, "UDP socket created");
            } catch (Exception e) {
                Log.e(TAG, "Socket creation failed", e);
            }
        }).start();

        btnLeft = findViewById(R.id.btnLeft1);
        btnStop = findViewById(R.id.btnStop1);
        btnRight = findViewById(R.id.right_btn1);
        yawTextView = findViewById(R.id.yawTextView1);           // Thêm TextView nếu bạn muốn show góc
        deltaYawTextView = findViewById(R.id.deltaYawTextView1); // Nếu không cần thì có thể bỏ

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        }

        btnLeft.setOnClickListener(v -> startLeftAction());
        btnStop.setOnClickListener(v -> manualStop());
        btnRight.setOnClickListener(v -> startRightAction());
    }

    private void startRightAction() {
        if (isTracking) return; // tránh spam nút Left

        sendCommand("right");
        isTracking = true;
        isFinished = false;
        initialYaw = Float.NaN;

        // Đăng ký sensor
        if (rotationVectorSensor != null) {
            sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_GAME);
        }

        // Sau 1.5s mới bắt đầu gửi tiếp left liên tục
        handler.postDelayed(() -> {
            sendLeftRunnable = new Runnable() {
                @Override
                public void run() {
                    if (!isFinished) {
                        sendCommand("right");
                        handler.postDelayed(this, 500); // mỗi 0.5s gửi 1 lần
                    }
                }
            };
            handler.post(sendLeftRunnable);
        }, 1500);
    }

    private void startLeftAction() {
        if (isTracking) return; // tránh spam nút Left

        sendCommand("left");
        isTracking = true;
        isFinished = false;
        initialYaw = Float.NaN;

        // Đăng ký sensor
        if (rotationVectorSensor != null) {
            sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_GAME);
        }

        // Sau 1.5s mới bắt đầu gửi tiếp left liên tục
        handler.postDelayed(() -> {
            sendLeftRunnable = new Runnable() {
                @Override
                public void run() {
                    if (!isFinished) {
                        sendCommand("left");
                        handler.postDelayed(this, 500); // mỗi 0.5s gửi 1 lần
                    }
                }
            };
            handler.post(sendLeftRunnable);
        }, 1500);
    }

    private void manualStop() {
        sendCommand("stop");
        stopTracking();
        initialYaw = 0;
    }

    private void stopTracking() {
        isTracking = false;
        isFinished = true;
        handler.removeCallbacksAndMessages(null); // dừng gửi "left" lặp
        sensorManager.unregisterListener(this);
    }

    private void sendCommand(String action) {
        String cmd = action + "," + speedA + "," + speedB + "\n";
        new Thread(() -> {
            try {
                byte[] data = cmd.getBytes("UTF-8");
                InetAddress addr = InetAddress.getByName(esp32Ip);
                DatagramPacket packet = new DatagramPacket(data, data.length, addr, ESP32_PORT);
                socket.send(packet);
                Log.d(TAG, "Sent command: " + cmd);
            } catch (Exception e) {
                Log.e(TAG, "Command send failed", e);
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        stopTracking();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!isTracking) return;

        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
        SensorManager.getOrientation(rotationMatrix, orientationAngles);

        float yawRad = orientationAngles[0];
        float yawDeg = (float) Math.toDegrees(yawRad);
        yawDeg = (yawDeg + 360) % 360;

        if (Float.isNaN(initialYaw)) {
            initialYaw = yawDeg;
        }

        float deltaYaw = yawDeg - initialYaw;
        if (deltaYaw > 180) {
            deltaYaw -= 360;
        } else if (deltaYaw < -180) {
            deltaYaw += 360;
        }

        yawTextView.setText(String.format("Yaw: %.2f°", yawDeg));
        deltaYawTextView.setText(String.format("Delta: %.2f°", deltaYaw));

        if (Math.abs(deltaYaw) >= 90 && !isFinished) {
            Log.d(TAG, "Đã xoay đủ 90 độ, gửi STOP");
            sendCommand("stop");
            stopTracking();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Không dùng
    }
}
