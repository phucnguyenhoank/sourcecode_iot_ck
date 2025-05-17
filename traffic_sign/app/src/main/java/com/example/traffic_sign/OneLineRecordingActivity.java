package com.example.traffic_sign;

import static java.nio.charset.StandardCharsets.UTF_8;

import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.opencv.android.CameraActivity;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class OneLineRecordingActivity extends CameraActivity implements CameraBridgeViewBase.CvCameraViewListener2 {

    private static final String TAG = "OneLineRecordingActivity";
    private static final int CONTROL_PORT = 7000; // Port for receiving control commands
    private String dataServerIp; // Python server IP
    private static final int DATA_SERVER_PORT = 6000;

    private String esp32Ip;
    private static final int ESP32_PORT = 5000;

    private DatagramSocket socket;

    private CameraBridgeViewBase mOpenCvCameraView;
    private TextView offsetTextView;
    private int frameCounter = 0;

    // Data need to save
    private int baseSpeed = 0;
    private int speedA, speedB;
    private double lineOffset;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_one_line_recording);

        esp32Ip = getIntent().getStringExtra("ESP32_IP");
        dataServerIp = getIntent().getStringExtra("DATA_SERVER_IP");
        if (esp32Ip == null || esp32Ip.isEmpty()) {
            Log.e(TAG, "ESP32 IP missing");
            finish();
            return;
        }
        else {
            Log.e(TAG, "ESP32 IP: " + esp32Ip);
        }
        if (dataServerIp == null || dataServerIp.isEmpty()) {
            Log.e(TAG, "Data Server IP missing");
            finish();
            return;
        }
        else {
            Log.e(TAG, "Data Server IP: " + dataServerIp);
        }

        new Thread(() -> {
            try {
                socket = new DatagramSocket();
                Log.d(TAG, "Socket opened");
            } catch (Exception e) {
                Log.e(TAG, "Socket error", e);
            }
        }).start();

        if (OpenCVLoader.initLocal()) {
            Log.i(TAG, "OpenCV loaded successfully");

        } else {
            Log.e(TAG, "OpenCV initialization failed!");
            (Toast.makeText(this, "OpenCV initialization failed!", Toast.LENGTH_LONG)).show();
            return;
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        mOpenCvCameraView = (CameraBridgeViewBase) findViewById(R.id.tutorial1_activity_java_surface_view);
        mOpenCvCameraView.setVisibility(SurfaceView.VISIBLE);
        mOpenCvCameraView.setCvCameraViewListener(this);

        // Start listening for control commands
        offsetTextView = findViewById(R.id.offsetTextView);
        startControlCommandListener();

    }

    /** UDP listener for control commands (maxSpeed, speedA, speedB) */
    private void startControlCommandListener() {
        new Thread(() -> {
            try {
                DatagramSocket controlSocket = new DatagramSocket(CONTROL_PORT);
                byte[] buffer = new byte[1024];

                while (!controlSocket.isClosed()) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    controlSocket.receive(packet);
                    String command = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).trim();

                    // Parse command: "maxSpeed,speedA,speedB"
                    String[] parts = command.split(",");
                    if (parts.length == 3) {
                        baseSpeed = Integer.parseInt(parts[0]);
                        speedA = Integer.parseInt(parts[1]);
                        speedB = Integer.parseInt(parts[2]);
                        Log.d(TAG, "Received control: " + command);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Control listener error", e);
            }
        }).start();
    }

    @Override
    public Mat onCameraFrame(CameraBridgeViewBase.CvCameraViewFrame inputFrame) {
        frameCounter++;
        if (frameCounter % 3 != 0) {
            return inputFrame.rgba(); // skip this frame
        }

        Mat rgba = inputFrame.rgba();
        lineOffset = getLineOffset(rgba, true);

        if (Double.isNaN(lineOffset)) {
            baseSpeed = 0;
        }

        // Send data to Python server
        sendData();

        // Send motor commands to ESP32
        sendCommand();

        // Update UI
        runOnUiThread(() -> {
            String status = Double.isNaN(lineOffset)
                    ? "Line lost"
                    : String.format("Offset: %.1f", lineOffset);
            offsetTextView.setText(status);
        });

        return rgba;
    }

    private void sendData() {
        String msg = lineOffset + "," + baseSpeed + "," + speedA + "," + speedB + "\n";
        new Thread(() -> {
            try {
                byte[] buf = msg.getBytes(StandardCharsets.UTF_8);
                InetAddress addr = InetAddress.getByName(dataServerIp);
                DatagramPacket packet = new DatagramPacket(buf, buf.length, addr, DATA_SERVER_PORT);
                socket.send(packet);
                Log.d(TAG, "Sent data: " + msg);
            } catch (Exception e) {
                Log.e(TAG, "Data send failed", e);
            }
        }).start();
    }

    private void sendCommand() {
        String cmd = "forward," + speedA + "," + speedB + "\n";
        new Thread(() -> {
            try {
                byte[] data = cmd.getBytes(StandardCharsets.UTF_8);
                InetAddress addr = InetAddress.getByName(esp32Ip);
                DatagramPacket packet = new DatagramPacket(data, data.length, addr, ESP32_PORT);
                socket.send(packet);
                Log.d(TAG, "Sent command: " + cmd);
            } catch (Exception e) {
                Log.e(TAG, "Command send failed", e);
            }
        }).start();
    }

    private double getLineOffset(Mat rgba, boolean normalize) {
        // 1) to HSV
        Mat hsv = new Mat();
        Imgproc.cvtColor(rgba, hsv, Imgproc.COLOR_RGB2HSV);

        // 2) threshold yellow
        Scalar lo = new Scalar(20, 100, 100), hi = new Scalar(30, 255, 255);
        Mat mask = new Mat();
        Core.inRange(hsv, lo, hi, mask);

        // 3) morphology
        Mat K = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(5,5));
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, K, new Point(-1,-1), 2);
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN,  K, new Point(-1,-1), 2);

        // 4) find largest contour
        List<MatOfPoint> cnts = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(mask, cnts, hierarchy,
                Imgproc.RETR_EXTERNAL,
                Imgproc.CHAIN_APPROX_NONE);
        if (cnts.isEmpty()) return Double.NaN;

        double maxA = 0; MatOfPoint best = null;
        for (MatOfPoint c : cnts) {
            double A = Imgproc.contourArea(c);
            if (A > maxA) { maxA = A; best = c; }
        }
        if (best == null || maxA < 100) return Double.NaN;

        // 5) fit infinite line
        Mat line = new Mat();
        Imgproc.fitLine(
                new MatOfPoint2f(best.toArray()),
                line,
                Imgproc.DIST_L2, 0, 0.01, 0.01
        );
        float[] v = new float[4];
        line.get(0, 0, v);
        float vx = v[0], vy = v[1], x0 = v[2], y0 = v[3];

        // 6) intersection with bottom
        int h = rgba.rows(), w = rgba.cols();
        if (Math.abs(vy) < 1e-6) return Double.NaN;
        double yb = h - 1;
        double xb = x0 + (yb - y0) * (vx / vy);

        // 7) offset from center
        return (xb - (w / 2.0) ) / (w / 2.0);
    }
    
    @Override
    public void onPause() {
        super.onPause();
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.disableView();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.enableView();
    }

    @Override
    protected List<? extends CameraBridgeViewBase> getCameraViewList() {
        return Collections.singletonList(mOpenCvCameraView);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.disableView();
    }

    @Override
    public void onCameraViewStarted(int width, int height) {

    }

    @Override
    public void onCameraViewStopped() {

    }

}