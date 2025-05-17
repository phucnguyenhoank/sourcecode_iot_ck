package com.example.traffic_sign;

import static java.nio.charset.StandardCharsets.UTF_8;

import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RacingHumanControlActivity extends CameraActivity implements CameraBridgeViewBase.CvCameraViewListener2 {

    private static final String TAG = "RacingHumanControl";

    private static final int ESP32_PORT = 5000;
    private static final int DATA_SERVER_PORT = 6000;
    private String esp32Ip;
    private String dataServerIp;
    private DatagramSocket socket;

    private TextView tvThrottleValue, tvSteerValue;

    // control state
    private int baseSpeed = 130;      // throttle
    private int turnOffset = 0;
    private int speedA, speedB;
    private double lastLineOffset = 0;
    private boolean isRecording = false;
    private TextView tvLineOffset;

    private CameraBridgeViewBase cameraView;

    private final int maxSteeringSpeed = 200;


    private boolean isTouchingThrottle = false;
    private Handler throttleHandler = new Handler();
    private Runnable throttleRunnable = new Runnable() {
        @Override
        public void run() {
            if (isTouchingThrottle) {
                computeAndSendForwardCommand();
                throttleHandler.postDelayed(this, 100); // Repeat every 100ms
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_racing_human_control);

        // get IP
        esp32Ip = getIntent().getStringExtra("ESP32_IP");
        dataServerIp = getIntent().getStringExtra("DATA_SERVER_IP");
        if (esp32Ip == null || esp32Ip.isEmpty()) {
            Log.e(TAG, "ESP32 IP missing");
            finish();
            return;
        }
        if (dataServerIp == null || dataServerIp.isEmpty()) {
            Log.e(TAG, "Data Server IP missing");
            finish();
            return;
        }

        if (OpenCVLoader.initLocal()) {
            Log.i(TAG, "OpenCV loaded successfully");

        } else {
            Log.e(TAG, "OpenCV initialization failed!");
            (Toast.makeText(this, "OpenCV initialization failed!", Toast.LENGTH_LONG)).show();
            return;
        }

        // open UDP socket
        new Thread(() -> {
            try {
                socket = new DatagramSocket();
                Log.d(TAG, "Socket opened");
            } catch (Exception e) {
                Log.e(TAG, "Socket error", e);
            }
        }).start();

        // bind views
        SeekBar sbThrottle = findViewById(R.id.seekBarThrottle);
        SeekBar sbSteer = findViewById(R.id.seekBarSteer);
        tvThrottleValue = findViewById(R.id.tvThrottleValue);
        tvSteerValue    = findViewById(R.id.tvSteerValue);
        Button btnReverse = findViewById(R.id.btnReverse);
        Switch switchRecord = findViewById(R.id.switchRecord);
        switchRecord.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isRecording = isChecked;
            Log.d(TAG, "Recording: " + isRecording);
        });
        tvLineOffset = findViewById(R.id.tvLineOffset);

        // init display
        tvThrottleValue.setText(String.valueOf(baseSpeed));
        tvSteerValue.setText("0");  // zero offset
        sbThrottle.setProgress(baseSpeed); // 130 by default
        sbSteer.setProgress(maxSteeringSpeed);          // center = 0 offset

        // Throttle listener
        sbThrottle.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                baseSpeed = progress;
                tvThrottleValue.setText(String.valueOf(baseSpeed));
                computeAndSendForwardCommand();
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                isTouchingThrottle = true;
                throttleHandler.post(throttleRunnable);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                isTouchingThrottle = false;
                throttleHandler.removeCallbacks(throttleRunnable);
            }
        });

        sbSteer.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // Remap progress [0, 510] to [-255, 255]
                turnOffset = progress - maxSteeringSpeed;
                tvSteerValue.setText(String.valueOf(turnOffset) + "," + speedA + "," + speedB);
                computeAndSendForwardCommand();
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) { }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // Optional: snap back to center
                seekBar.setProgress(maxSteeringSpeed);
                turnOffset = 0;
                tvSteerValue.setText("0");
                computeAndSendForwardCommand();
            }
        });

        // reverse
        btnReverse.setOnClickListener(v -> sendCommand("reverse", baseSpeed, baseSpeed));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        cameraView = findViewById(R.id.cameraView);
        cameraView.setVisibility(SurfaceView.VISIBLE);
        cameraView.setCvCameraViewListener(this);
    }

    /** compute speedA/B and fire forward command */
    private void computeAndSendForwardCommand() {
        // simple mixing: A = base + offset, B = base - offset
        speedA = clamp(baseSpeed - turnOffset, 0, maxSteeringSpeed);
        speedB = clamp(baseSpeed + turnOffset, 0, maxSteeringSpeed);
        sendCommand("forward", speedA, speedB);
    }

    private void sendData(double lineOffset, int maxSpeed, int a, int b) {
        String msg = lineOffset + "," + maxSpeed + "," + a + "," + b + "\n";
        new Thread(() -> {
            try {
                byte[] buf = msg.getBytes(UTF_8);
                InetAddress addr = InetAddress.getByName(dataServerIp);
                DatagramPacket packet = new DatagramPacket(buf, buf.length, addr, DATA_SERVER_PORT);
                socket.send(packet);
                Log.d("DataSend", "→ " + msg.trim());
            } catch (Exception e) {
                Log.e("DataSend", "failed", e);
            }
        }).start();
    }

    /** helper to clamp values */
    private int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }

    /** send <action>,<A>,<B> over UDP */
    private void sendCommand(String action, int a, int b) {
        String cmd = action + "," + a + "," + b + "\n";
        new Thread(() -> {
            try {
                byte[] buf = cmd.getBytes(UTF_8);
                InetAddress addr = InetAddress.getByName(esp32Ip);
                DatagramPacket pkt = new DatagramPacket(buf, buf.length, addr, ESP32_PORT);
                socket.send(pkt);
                Log.d(TAG, "→ " + cmd.trim());
            } catch (Exception e) {
                Log.e(TAG, "send failed", e);
            }
        }).start();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (cameraView != null)
            cameraView.disableView();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (cameraView != null)
            cameraView.enableView();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (socket != null && !socket.isClosed()) socket.close();
    }

    @Override
    public void onCameraViewStarted(int width, int height) {    }

    @Override
    public void onCameraViewStopped() {

    }

    @Override
    protected List<? extends CameraBridgeViewBase> getCameraViewList() {
        return Collections.singletonList(cameraView);
    }

    @Override
    public Mat onCameraFrame(CameraBridgeViewBase.CvCameraViewFrame inputFrame) {
        Mat rgba = inputFrame.rgba();

        if (isRecording) {
            // 1) compute lineOffset
            double off = getLineOffset(rgba, true);
            lastLineOffset = off;

            // 2) recompute speeds from current throttle & steering
            speedA = clamp(baseSpeed + turnOffset, 0, 255);
            speedB = clamp(baseSpeed - turnOffset, 0, 255);

            // 3) send data packet every frame
            sendData(lastLineOffset, baseSpeed, speedA, speedB);

            runOnUiThread(() -> tvLineOffset.setText("Offset: " + lastLineOffset));
        }
        else {
            runOnUiThread(() -> tvLineOffset.setText("Stop Recording"));
        }

        return rgba;
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

}
