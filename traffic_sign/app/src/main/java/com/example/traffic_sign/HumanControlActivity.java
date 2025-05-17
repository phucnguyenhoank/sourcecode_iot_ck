package com.example.traffic_sign;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.gson.Gson;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import fi.iki.elonen.NanoHTTPD;

public class HumanControlActivity extends AppCompatActivity {

    private static final int ESP32_PORT = 5000;
    private String esp32Ip;
    private SeekBar seekBarA, seekBarB;
    private TextView speedAValue, speedBValue;
    private int speedA = 200, speedB = 200;
    private DatagramSocket socket;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private List<double[]> routeCoordinates = new ArrayList<>(); // [latitude, longitude]
    private boolean isTracking = false;
    private WebServer webServer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_human_control);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // Khởi tạo socket UDP cho ESP32
        new Thread(() -> {
            try {
                socket = new DatagramSocket();
                Log.d("HumanControlActivity", "Đã tạo socket UDP");
            } catch (Exception e) {
                Log.e("HumanControlActivity", "Tạo socket thất bại", e);
            }
        }).start();

        // Lấy IP của ESP32 từ Intent
        esp32Ip = getIntent().getStringExtra("ESP32_IP");
        if (esp32Ip == null || esp32Ip.isEmpty()) {
            Log.e("HumanControlActivity", "Không có IP của ESP32");
            finish();
            return;
        }

        // Khởi tạo giao diện
        seekBarA = findViewById(R.id.seekBarA);
        seekBarB = findViewById(R.id.seekBarB);
        speedAValue = findViewById(R.id.speedAValue);
        speedBValue = findViewById(R.id.speedBValue);
        Button btnForward = findViewById(R.id.btnForward);
        Button btnLeft = findViewById(R.id.btnLeft);
        Button btnStop = findViewById(R.id.btnStop);
        Button btnRight = findViewById(R.id.btnRight);
        Button btnReverse = findViewById(R.id.btnReverse);
        Button btnStartMap = findViewById(R.id.btnStartMap);

        // Thiết lập SeekBar A
        seekBarA.setMax(255);
        seekBarA.setProgress(speedA);
        speedAValue.setText(String.valueOf(speedA));
        seekBarA.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                speedA = progress;
                speedAValue.setText(String.valueOf(speedA));
                Log.d("HumanControlActivity", "Tốc độ động cơ A: " + speedA);
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Thiết lập SeekBar B
        seekBarB.setMax(255);
        seekBarB.setProgress(speedB);
        speedBValue.setText(String.valueOf(speedB));
        seekBarB.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                speedB = progress;
                speedBValue.setText(String.valueOf(speedB));
                Log.d("HumanControlActivity", "Tốc độ động cơ B: " + speedB);
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Xử lý các nút điều khiển ESP32
        View.OnClickListener listener = v -> {
            String action = null;
            int id = v.getId();
            if (id == R.id.btnForward) {
                action = "forward";
            } else if (id == R.id.btnLeft) {
                action = "left";
            } else if (id == R.id.btnStop) {
                action = "stop";
            } else if (id == R.id.btnRight) {
                action = "right";
            } else if (id == R.id.btnReverse) {
                action = "reverse";
            }
            if (action != null) {
                sendCommand(action);
            }
        };

        btnForward.setOnClickListener(listener);
        btnLeft.setOnClickListener(listener);
        btnStop.setOnClickListener(listener);
        btnRight.setOnClickListener(listener);
        btnReverse.setOnClickListener(listener);

        // Khởi tạo GPS
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        requestLocationPermissions();

        // Xử lý nút StartMap
        btnStartMap.setOnClickListener(v -> {
            if (!isTracking) {
                startTracking();
                btnStartMap.setText("Dừng ghi lộ trình");
            } else {
                stopTracking();
                btnStartMap.setText("Bắt đầu ghi lộ trình");
                TextView ipDisplay = findViewById(R.id.ipDisplay);
                if (ipDisplay != null) {
                    ipDisplay.setText("Truy cập: Chưa khởi động");
                }
            }
        });

        // Callback cho GPS
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null) {
                    Log.w("GPS", "Không nhận được dữ liệu GPS");
                    return;
                }
                for (android.location.Location location : locationResult.getLocations()) {
                    if (location.getAccuracy() > 50) {
                        Log.w("GPS", "Tín hiệu GPS yếu, độ chính xác: " + location.getAccuracy());
                    }
                    routeCoordinates.add(new double[]{location.getLatitude(), location.getLongitude()});
                    Log.d("GPS", "Vĩ độ: " + location.getLatitude() + ", Kinh độ: " + location.getLongitude());
                }
            }
        };
    }

    // Yêu cầu quyền GPS
    private void requestLocationPermissions() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, 1);
        }
    }

    // Gửi lệnh tới ESP32 qua UDP
    private void sendCommand(String action) {
        String cmd = action + "," + speedA + "," + speedB + "\n";
        new Thread(() -> {
            try {
                byte[] data = cmd.getBytes("UTF-8");
                InetAddress addr = InetAddress.getByName(esp32Ip);
                DatagramPacket packet = new DatagramPacket(data, data.length, addr, ESP32_PORT);
                socket.send(packet);
                Log.d("HumanControlActivity", "Đã gửi lệnh: " + cmd);
            } catch (Exception e) {
                Log.e("HumanControlActivity", "Gửi lệnh thất bại", e);
            }
        }).start();
    }

    // Bắt đầu theo dõi GPS
    private void startTracking() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e("GPS", "Không có quyền GPS");
            return;
        }
        isTracking = true;
        LocationRequest locationRequest = LocationRequest.create()
                .setInterval(3000) // Cập nhật mỗi 3 giây
                .setFastestInterval(1000)
                .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null);
        startWebServer();
    }

    // Dừng theo dõi GPS
    private void stopTracking() {
        isTracking = false;
        fusedLocationClient.removeLocationUpdates(locationCallback);
        stopWebServer();
        routeCoordinates.clear(); // Xóa dữ liệu để không lưu
        Log.d("GPS", "Đã dừng theo dõi và xóa lộ trình");
    }

    // Khởi động máy chủ web
    private void startWebServer() {
        try {
            webServer = new WebServer();
            webServer.start();
            String url = "http://" + getLocalIpAddress() + ":8080";
            Log.d("WebServer", "Máy chủ chạy tại: " + url);
            runOnUiThread(() -> {
                TextView ipDisplay = findViewById(R.id.ipDisplay);
                if (ipDisplay != null) {
                    ipDisplay.setText("Truy cập: " + url);
                }
            });
        } catch (Exception e) {
            Log.e("WebServer", "Khởi động máy chủ thất bại", e);
        }
    }

    // Dừng máy chủ web
    private void stopWebServer() {
        if (webServer != null) {
            webServer.stop();
            webServer = null;
            Log.d("WebServer", "Đã dừng máy chủ web");
        }
    }

    // Lấy địa chỉ IPv4 của điện thoại
    private String getLocalIpAddress() {
        try {
            for (NetworkInterface intf : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress addr : Collections.list(intf.getInetAddresses())) {
                    if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                        Log.d("IP", "Đã tìm thấy IPv4: " + addr.getHostAddress());
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.e("IP", "Lấy IP thất bại", e);
        }
        Log.w("IP", "Không tìm thấy IPv4, trả về localhost");
        return "127.0.0.1";
    }

    // Lớp máy chủ web
    private class WebServer extends NanoHTTPD {
        public WebServer() {
            super(8080);
        }

        @Override
        public Response serve(IHTTPSession session) {
            String uri = session.getUri();
            if (uri.equals("/")) {
                return newFixedLengthResponse(getHtml());
            } else if (uri.equals("/route")) {
                Gson gson = new Gson();
                String json = gson.toJson(routeCoordinates);
                return newFixedLengthResponse(Response.Status.OK, "application/json", json);
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Không tìm thấy");
        }

        private String getHtml() {
            return "<!DOCTYPE html>" +
                    "<html>" +
                    "<head>" +
                    "<meta charset='UTF-8'>" +
                    "<title>Lộ trình xe</title>" +
                    "<link rel='stylesheet' href='https://unpkg.com/leaflet@1.9.4/dist/leaflet.css' />" +
                    "<script src='https://unpkg.com/leaflet@1.9.4/dist/leaflet.js'></script>" +
                    "<style>" +
                    "#map { height: calc(100vh - 60px); }" +
                    ".header { background-color: #1976D2; color: white; padding: 10px; text-align: center; font-family: Arial, sans-serif; font-size: 18px; }" +
                    ".status { position: absolute; bottom: 10px; left: 10px; background: rgba(255, 255, 255, 0.8); padding: 5px; border-radius: 3px; font-family: Arial, sans-serif; font-size: 14px; z-index: 1000; }" +
                    "#focusButton { position: absolute; top: 70px; right: 10px; background: #1976D2; color: white; border: none; padding: 8px 12px; border-radius: 4px; cursor: pointer; font-family: Arial, sans-serif; font-size: 14px; z-index: 1000; }" +
                    "#focusButton:hover { background: #1565C0; }" +
                    ".pulse { animation: pulse 1s infinite alternate; }" +
                    "@keyframes pulse { from { transform: scale(1); } to { transform: scale(1.2); } }" +
                    "</style>" +
                    "</head>" +
                    "<body>" +
                    "<div class='header'>Theo dõi lộ trình xe</div>" +
                    "<div id='map'></div>" +
                    "<button id='focusButton'>Focus vị trí hiện tại</button>" +
                    "<div id='status' class='status'>Đang chờ GPS...</div>" +
                    "<script>" +
                    "var map = L.map('map', { zoomControl: true }).setView([10.7769, 106.7009], 13);" +
                    "L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {" +
                    "attribution: '© <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a> contributors'," +
                    "maxZoom: 19" +
                    "}).addTo(map);" +
                    "var polyline = L.polyline([], { color: 'red', weight: 5, opacity: 0.8 }).addTo(map);" +
                    "var marker = L.marker([0, 0], {" +
                    "icon: L.divIcon({" +
                    "className: 'custom-marker'," +
                    "html: '<div style=\"background-color: red; width: 24px; height: 24px; border-radius: 50%; border: 3px solid white; box-shadow: 0 0 5px rgba(0,0,0,0.5);\" class=\"pulse\"></div>'," +
                    "iconSize: [24, 24]," +
                    "iconAnchor: [12, 12]" +
                    "})}).addTo(map);" +
                    "var positionCircle = L.circle([0, 0], { radius: 10, color: 'red', fillColor: 'red', fillOpacity: 0.2, weight: 2 }).addTo(map);" +
                    "function updateRoute() {" +
                    "fetch('/route').then(response => response.json()).then(data => {" +
                    "var status = document.getElementById('status');" +
                    "if (data.length > 0) {" +
                    "polyline.setLatLngs(data);" +
                    "var currentPos = data[data.length - 1];" +
                    "marker.setLatLng(currentPos);" +
                    "positionCircle.setLatLng(currentPos);" +
                    "marker.bindPopup('Xe hiện tại<br>Vị trí: ' + currentPos[0].toFixed(5) + ', ' + currentPos[1].toFixed(5) + '<br>Thời gian: ' + new Date().toLocaleTimeString()).openPopup();" +
                    "map.fitBounds(polyline.getBounds(), { padding: [50, 50] });" +
                    "status.innerText = 'Cập nhật: ' + new Date().toLocaleTimeString();" +
                    "} else {" +
                    "marker.setLatLng([0, 0]);" +
                    "positionCircle.setLatLng([0, 0]);" +
                    "status.innerText = 'Đang chờ GPS...';" +
                    "console.warn('Không có dữ liệu lộ trình');" +
                    "}" +
                    "}).catch(err => {" +
                    "console.error('Lỗi fetch:', err);" +
                    "status.innerText = 'Lỗi kết nối';" +
                    "});" +
                    "}" +
                    "function focusOnCurrentPosition() {" +
                    "var latlng = marker.getLatLng();" +
                    "if (latlng.lat !== 0 && latlng.lng !== 0) {" +
                    "map.setView(latlng, 17);" +
                    "marker.openPopup();" +
                    "document.querySelector('.custom-marker div').classList.add('pulse');" +
                    "setTimeout(() => document.querySelector('.custom-marker div').classList.remove('pulse'), 3000);" +
                    "} else {" +
                    "console.warn('Không có vị trí hiện tại để focus');" +
                    "document.getElementById('status').innerText = 'Chưa có dữ liệu GPS';" +
                    "}" +
                    "}" +
                    "document.getElementById('focusButton').addEventListener('click', focusOnCurrentPosition);" +
                    "setInterval(updateRoute, 3000);" +
                    "updateRoute();" +
                    "</script>" +
                    "</body>" +
                    "</html>";
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        stopTracking();
    }
}