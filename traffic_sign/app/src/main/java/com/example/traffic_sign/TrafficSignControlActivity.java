package com.example.traffic_sign;

import android.graphics.Bitmap;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.util.Log;
import android.util.Pair;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.opencv.android.OpenCVLoader;
import org.opencv.core.Mat;
import org.opencv.android.Utils;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Activity to read MJPEG, detect signs using a queue-based majority voting system,
 * integrate yaw sensor, and send commands at throttled rate.
 */
public class TrafficSignControlActivity extends AppCompatActivity implements SensorEventListener {
    private static final String TAG = "TrafficSignControl";
    private TrafficSignClassifier classifier;
    private final String[] classes = {"Stop", "Turn Right", "Turn Left", "Straight"};
    private TextView detectedTextView, yawTextView, deltaYawTextView;
    private ImageView imageView;

    private static final int ESP32_PORT = 5000;
    private String esp32Ip, esp32CamIp;
    private DatagramSocket socket;
    private final int baseSpeed = 200;
    private int speedA = baseSpeed, speedB = baseSpeed;

    // Queue for detection results
    private static final int QUEUE_SIZE = 4;
    private final Deque<Integer> detectionQueue = new ArrayDeque<>(QUEUE_SIZE);
    private static final float CONFIRM_THRESHOLD = 0.98f;

    // Throttle sending
    private static final long SEND_INTERVAL_MS = 700; // Tần suất gửi lệnh
    private long lastSendTime = 0;

    // Yaw sensor
    private SensorManager sensorManager;
    private Sensor rotationSensor;
    private float initialYaw = Float.NaN;

    // Command state
    private enum State { STOP, TURN_LEFT, TURN_RIGHT, FORWARD }
    private State state = State.STOP;
    private boolean isStopping = true;
    private boolean isTurning = false;

    private ExecutorService streamExecutor;
    private final ExecutorService sendExecutor = Executors.newSingleThreadExecutor();

    private long lastUiUpdateTime = 0;
    private static final long UI_UPDATE_INTERVAL_MS = 500; // Cập nhật UI mỗi 500ms

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_traffic_sign_control);

        detectedTextView = findViewById(R.id.detectedTextView);
        imageView = findViewById(R.id.imageView);
        yawTextView = findViewById(R.id.yawTextView);
        deltaYawTextView = findViewById(R.id.deltaYawTextView);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);

        esp32Ip = getIntent().getStringExtra("ESP32_IP");
        esp32CamIp = getIntent().getStringExtra("ESP32CAM_IP");
        if (esp32Ip == null || esp32Ip.isEmpty() || esp32CamIp == null || esp32CamIp.isEmpty()) {
            Toast.makeText(this, "ESP32 or CAM IP not provided", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        else {
            Toast.makeText(this, "ESP32IP,CAM_IP\n" + esp32Ip + "\n" + esp32CamIp, Toast.LENGTH_LONG).show();
        }

        initSocket();
        initSensors();
        initOpenCV();
        initClassifier();

        // 1) Create a single-thread executor
        streamExecutor = Executors.newSingleThreadExecutor();

        // 2) Submit the MJPEG streaming task
        streamExecutor.execute(this::streamMjpeg);
    }

    private void initSocket() {
        new Thread(() -> {
            try {
                socket = new DatagramSocket();
                Log.d(TAG, "UDP socket created");
            } catch (Exception e) {
                Log.e(TAG, "Socket init failed", e);
            }
        }).start();
    }

    private void initSensors() {
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (rotationSensor != null) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
    }

    private void initOpenCV() {
        if (!OpenCVLoader.initDebug()) {
            Toast.makeText(this, "OpenCV init failed", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void initClassifier() {
        try {
            classifier = new TrafficSignClassifier(getAssets(), "traffic_detect_4_classes.tflite");
        } catch (IOException e) {
            Toast.makeText(this, "Classifier load failed", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void streamMjpeg() {
        while (!Thread.currentThread().isInterrupted()) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection)
                        new URL("http://" + esp32CamIp + ":81/stream")
                                .openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(0);
                conn.connect();

                MjpegInputStream mjis = new MjpegInputStream(conn.getInputStream());
                Bitmap frame;
                while ((frame = mjis.readFrame()) != null
                        && !Thread.currentThread().isInterrupted()) {
                    // Xử lý khung hình để dự đoán
                    processFrame(frame);

                    // Kiểm tra xem đã đến lúc cập nhật UI chưa
                    long now = System.currentTimeMillis();
                    if (now - lastUiUpdateTime >= UI_UPDATE_INTERVAL_MS) {
                        lastUiUpdateTime = now;
                        Bitmap finalFrame = frame;
                        runOnUiThread(() -> imageView.setImageBitmap(finalFrame));
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Stream error, retry", e);
                try { Thread.sleep(1000); }
                catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
    }

    // Detection with queue-based majority voting
    private void processFrame(Bitmap bmp) {
        if (isTurning) return; // Do not process new detections while turning

        // 1) Convert to OpenCV Mat & crop to circular sign (if any)
        Bitmap resizedBmp = Bitmap.createScaledBitmap(bmp, 320, 240, true);
        Mat mat = new Mat();
        Utils.bitmapToMat(resizedBmp, mat);
        Object[] res = ImageProcessingUtils.detectAndCropCircularSign(mat);
        Mat crop = (Mat) res[0];

        // 2) If we got a crop, run classifier; else force a “no-detection”:
        int rawId;
        float prob = -1;
        if (crop != null) {
            Bitmap cb = Bitmap.createBitmap(crop.cols(), crop.rows(), Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(crop, cb);
            Pair<Integer, Float> pred = classifier.predictWithProb(cb);
            prob = pred.second;
            rawId = (prob >= CONFIRM_THRESHOLD ? pred.first : -1);
            crop.release();

        } else {
            rawId = -1;
        }
        mat.release();

        Log.i(TAG, "detected:" + rawId + ";" + prob);

        // 3) Enqueue the new rawId (or −1)
        addToQueue(rawId);

        // 4) Decide the dominant (most-frequent) id & update state
        updateState(getDominantId());
    }

    private void addToQueue(int id) {
        if (detectionQueue.size() >= QUEUE_SIZE) {
            detectionQueue.removeFirst();
        }
        detectionQueue.addLast(id);
    }

    private void cleanQueue() {
        detectionQueue.clear();
    }

    private int getDominantId() {
        Map<Integer, Integer> countMap = new HashMap<>();
        for (int id : detectionQueue) {
            countMap.put(id, countMap.getOrDefault(id, 0) + 1);
        }
        if (countMap.isEmpty()) return -1;
        return Collections.max(countMap.entrySet(), Map.Entry.comparingByValue()).getKey();
    }

    private void updateState(int dominantId) {
        if (isTurning) return;  // freeze while mid-turn

        String label;
        switch (dominantId) {
            case -1:
                // No reliable sign → keep moving forward after a stop
                if (!isStopping) state = State.FORWARD;
                label = "Unknown (if not stopping, forward)";
                break;
            case 0:  // STOP sign
                state = State.STOP;  isStopping = true;
                label = "Stop";
                break;
            case 1:  // TURN RIGHT
                state = State.TURN_RIGHT;  resetInitialYaw();  isStopping=false;  isTurning=true;
                label = "Turn Right";
                break;
            case 2:  // TURN LEFT
                state = State.TURN_LEFT;   resetInitialYaw();  isStopping=false;  isTurning=true;
                label = "Turn Left";
                break;
            case 3:  // STRAIGHT
                state = State.FORWARD;     isStopping=false;
                label = "Straight";
                break;
            default:
                if (!isStopping) state = State.FORWARD;
                label = "No Dominant Id (if not stopping, forward)";
                break;
        }
        runOnUiThread(() -> detectedTextView.setText(label));
    }

    // Sensor callback
    @Override
    public void onSensorChanged(SensorEvent event) {
        float[] rotM = new float[9], ori = new float[3];
        SensorManager.getRotationMatrixFromVector(rotM, event.values);
        SensorManager.getOrientation(rotM, ori);
        float currentYaw = (float) Math.toDegrees(ori[0]);
        currentYaw = (currentYaw + 360) % 360;
        if (Float.isNaN(initialYaw)) initialYaw = currentYaw;
        float deltaYaw = currentYaw - initialYaw;

        if (deltaYaw > 180) deltaYaw -= 360;
        if (deltaYaw < -180) deltaYaw += 360;
        yawTextView.setText(String.format(Locale.US, "Yaw: %.1f°", currentYaw));
        deltaYawTextView.setText(String.format(Locale.US, "Δ: %.1f°", deltaYaw));

        long now = System.currentTimeMillis();
        if (now - lastSendTime < SEND_INTERVAL_MS) return;
        lastSendTime = now;

        if (state == State.STOP) {
            speedA = speedB = 0;
            sendCommand("stop");
            return;
        }

        speedA = speedB = baseSpeed;
        switch(state) {
            case FORWARD:
                sendCommand("forward");
                break;
            case TURN_LEFT:
                sendCommand("left");
                if (deltaYaw <= -90) finishTurn();
                break;
            case TURN_RIGHT:
                sendCommand("right");
                if (deltaYaw >= 90) finishTurn();
                break;
        }
    }

    private void finishTurn() {
        state = State.FORWARD;
        isTurning = false;
        cleanQueue();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void resetInitialYaw() {
        initialYaw = Float.NaN;
    }

    private void sendCommand(String direction) {
        String cmd = String.format(Locale.US,"%s,%d,%d", direction, speedA, speedB);
        sendExecutor.execute(() -> {
            try {
                byte[] data = cmd.getBytes();
                InetAddress addr = InetAddress.getByName(esp32Ip);
                socket.send(new DatagramPacket(data, data.length, addr, ESP32_PORT));
//                Log.d(TAG, "Sent: " + cmd);
            } catch (IOException ex) {
                Log.e(TAG, "Send failed", ex);
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        streamExecutor.shutdownNow();
        sendExecutor.shutdownNow();
        if (socket != null && !socket.isClosed()) socket.close();
    }
}