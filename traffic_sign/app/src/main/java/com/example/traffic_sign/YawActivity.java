package com.example.traffic_sign;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import android.app.Activity;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

public class YawActivity extends Activity implements SensorEventListener {

    private static final String TAG = "YawActivity";

    private SensorManager sensorManager;
    private Sensor rotationVectorSensor;

    private final float[] rotationMatrix = new float[9];
    private final float[] orientationAngles = new float[3];

    private float initialYaw = Float.NaN;

    private TextView yawTextView, deltaYawTextView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_yaw);

        // Khởi tạo SensorManager và cảm biến vector quay
        // Reset lại giá trị initialYaw mỗi khi Activity được tạo lại
        initialYaw = Float.NaN;

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        }

        if (rotationVectorSensor == null) {
            Log.e(TAG, "Cảm biến vector quay không khả dụng trên thiết bị này.");
            finish(); // Kết thúc Activity nếu cảm biến không khả dụng
        }
        yawTextView = findViewById(R.id.yawTextView);
        deltaYawTextView = findViewById(R.id.deltaYawTextView);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Đăng ký lắng nghe sự kiện từ cảm biến
        if (rotationVectorSensor != null) {
            sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Hủy đăng ký lắng nghe khi Activity tạm dừng
        sensorManager.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        // Chuyển đổi vector quay thành ma trận xoay
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
        // Tính toán các góc định hướng từ ma trận xoay
        SensorManager.getOrientation(rotationMatrix, orientationAngles);

        // orientationAngles[0] là azimuth (góc quay quanh trục Z), tính bằng radian
        float azimuthRad = orientationAngles[0];
        float azimuthDeg = (float) Math.toDegrees(azimuthRad);

        // Chuẩn hóa góc về khoảng [0, 360)
        azimuthDeg = (azimuthDeg + 360) % 360;

        // Thiết lập góc yaw ban đầu nếu chưa được thiết lập
        if (Float.isNaN(initialYaw)) {
            initialYaw = azimuthDeg;
        }

        // Tính toán sự thay đổi góc yaw so với góc ban đầu
        float deltaYaw = azimuthDeg - initialYaw;

        // Đảm bảo deltaYaw nằm trong khoảng [-180, 180)
        if (deltaYaw > 180) {
            deltaYaw -= 360;
        } else if (deltaYaw < -180) {
            deltaYaw += 360;
        }

        // Cập nhật TextView với giá trị hiện tại
        yawTextView.setText(String.format("Góc hiện tại: %.2f°", azimuthDeg));
        deltaYawTextView.setText(String.format("Thay đổi: %.2f°", deltaYaw));

        // Log góc yaw hiện tại và sự thay đổi góc
        Log.d(TAG, "Góc yaw hiện tại: " + azimuthDeg + "°, Sự thay đổi: " + deltaYaw + "°");

    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Không sử dụng trong ví dụ này
    }
}
