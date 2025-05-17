package com.example.traffic_sign;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DataCollectionActivity extends AppCompatActivity {
    private static final String TAG = "DataCollection";
    private ImageView imageView;
    private Button buttonForward, buttonLeft, buttonRight;
    private String esp32Ip, esp32CamIp, dataServerIp = "192.168.86.174"; // Thay bằng IP của PC
    private final int dataServerPort = 8080; // Thay bằng port của server
    private DatagramSocket socket;
    private final int baseSpeed = 200;
    private volatile Bitmap latestFrame; // Lưu khung hình mới nhất
    private ExecutorService streamExecutor;
    private final ExecutorService sendExecutor = Executors.newSingleThreadExecutor();
    private long lastUiUpdateTime = 0;
    private static final long UI_UPDATE_INTERVAL_MS = 500; // Cập nhật UI mỗi 500ms

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_data_collection);

        // Khởi tạo giao diện
        imageView = findViewById(R.id.imageView);
        buttonForward = findViewById(R.id.button_forward);
        buttonLeft = findViewById(R.id.button_left);
        buttonRight = findViewById(R.id.button_right);

        // Lấy IP từ intent
        esp32Ip = getIntent().getStringExtra("ESP32_IP");
        esp32CamIp = getIntent().getStringExtra("ESP32CAM_IP");
        if (esp32Ip == null || esp32Ip.isEmpty() || esp32CamIp == null || esp32CamIp.isEmpty()) {
            Toast.makeText(this, "ESP32 hoặc CAM IP không được cung cấp", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // Khởi tạo socket và luồng MJPEG
        initSocket();
        streamExecutor = Executors.newSingleThreadExecutor();
        streamExecutor.execute(this::streamMjpeg);

        // Xử lý sự kiện nhấn nút
        buttonForward.setOnClickListener(v -> handleButtonPress("forward"));
        buttonLeft.setOnClickListener(v -> handleButtonPress("left"));
        buttonRight.setOnClickListener(v -> handleButtonPress("right"));
    }

    private void initSocket() {
        new Thread(() -> {
            try {
                socket = new DatagramSocket();
                Log.d(TAG, "UDP socket đã được tạo");
            } catch (Exception e) {
                Log.e(TAG, "Khởi tạo socket thất bại", e);
            }
        }).start();
    }

    private void streamMjpeg() {
        while (!Thread.currentThread().isInterrupted()) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL("http://" + esp32CamIp + ":81/stream").openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(0);
                conn.connect();

                MjpegInputStream mjis = new MjpegInputStream(conn.getInputStream());
                Bitmap frame;
                while ((frame = mjis.readFrame()) != null && !Thread.currentThread().isInterrupted()) {
                    latestFrame = frame; // Cập nhật khung hình mới nhất
                    long now = System.currentTimeMillis();
                    if (now - lastUiUpdateTime >= UI_UPDATE_INTERVAL_MS) {
                        lastUiUpdateTime = now;
                        runOnUiThread(() -> imageView.setImageBitmap(latestFrame));
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Lỗi luồng video, thử lại", e);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
    }

    private void handleButtonPress(String direction) {
        sendCommand(direction); // Gửi lệnh tới ESP32
        if (latestFrame != null) {
            sendDataToServer(latestFrame, direction); // Gửi ảnh và nhãn tới server
        }
    }

    private void sendCommand(String direction) {
        String cmd = String.format(Locale.US, "%s,%d,%d", direction, baseSpeed, baseSpeed);
        sendExecutor.execute(() -> {
            try {
                byte[] data = cmd.getBytes();
                InetAddress addr = InetAddress.getByName(esp32Ip);
                socket.send(new DatagramPacket(data, data.length, addr, 5000));
                Log.d(TAG, "Đã gửi: " + cmd);
            } catch (IOException ex) {
                Log.e(TAG, "Gửi lệnh thất bại", ex);
            }
        });
    }

    private void sendDataToServer(Bitmap image, String label) {
        new Thread(() -> {
            try {
                // Chuyển ảnh thành mảng byte
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                image.compress(Bitmap.CompressFormat.JPEG, 80, baos);
                byte[] imageData = baos.toByteArray();

                // Tạo yêu cầu multipart
                String boundary = "*****";
                String lineEnd = "\r\n";
                String twoHyphens = "--";
                URL url = new URL("http://" + dataServerIp + ":" + dataServerPort + "/upload");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setDoOutput(true);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

                DataOutputStream dos = new DataOutputStream(conn.getOutputStream());
                // Gửi nhãn
                dos.writeBytes(twoHyphens + boundary + lineEnd);
                dos.writeBytes("Content-Disposition: form-data; name=\"label\"" + lineEnd);
                dos.writeBytes(lineEnd);
                dos.writeBytes(label + lineEnd);
                // Gửi ảnh
                dos.writeBytes(twoHyphens + boundary + lineEnd);
                dos.writeBytes("Content-Disposition: form-data; name=\"image\"; filename=\"image.jpg\"" + lineEnd);
                dos.writeBytes("Content-Type: image/jpeg" + lineEnd);
                dos.writeBytes(lineEnd);
                dos.write(imageData);
                dos.writeBytes(lineEnd);
                dos.writeBytes(twoHyphens + boundary + twoHyphens + lineEnd);
                dos.flush();
                dos.close();

                int responseCode = conn.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    Log.d(TAG, "Dữ liệu gửi thành công");
                } else {
                    Log.e(TAG, "Gửi dữ liệu thất bại, mã phản hồi: " + responseCode);
                }
            } catch (Exception e) {
                Log.e(TAG, "Lỗi khi gửi dữ liệu", e);
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        streamExecutor.shutdownNow();
        sendExecutor.shutdownNow();
        if (socket != null && !socket.isClosed()) socket.close();
    }
}