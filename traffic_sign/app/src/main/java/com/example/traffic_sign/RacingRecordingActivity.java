package com.example.traffic_sign;

import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

public class RacingRecordingActivity extends AppCompatActivity {

    private static final String TAG = "RacingRecordingActivity";

    private static final int CONTROL_PORT = 7000; // Port for sending commands
    private String controlIp; // IP of OneLineControlActivity device

    private DatagramSocket socket;

    private TextView tvThrottleValue, tvSteerValue;

    private int turnOffset = 0;

    // control state
    private int baseSpeed = 0;      // throttle
    private int speedA, speedB;

    private int maxSteeringSpeed = 200;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_racing_recording);

        controlIp = getIntent().getStringExtra("CONTROL_IP");
        if (controlIp == null || controlIp.isEmpty()) {
            Log.e(TAG, "Control IP missing");
            finish();
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

        // init display
        tvThrottleValue.setText(String.valueOf(baseSpeed));
        tvSteerValue.setText("0");  // zero offset
        sbThrottle.setProgress(baseSpeed); // 0 by default
        sbSteer.setProgress(maxSteeringSpeed);

        // Throttle listener
        sbThrottle.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                baseSpeed = progress;
                tvThrottleValue.setText(String.valueOf(baseSpeed));
                computeAndSendForwardCommand();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });

        sbSteer.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // Remap progress [0, 400] to [-200, 200]
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

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void computeAndSendForwardCommand() {
        // simple mixing: A = base + offset, B = base - offset
        speedA = clamp(baseSpeed + turnOffset);
        speedB = clamp(baseSpeed - turnOffset);
        sendCommand();
    }

    private int clamp(int val) {
        return Math.max(0, Math.min(200, val));
    }

    private void sendCommand() {
        String cmd = baseSpeed + "," + speedA + "," + speedB + "\n";
        new Thread(() -> {
            try {
                byte[] data = cmd.getBytes(StandardCharsets.UTF_8);
                InetAddress addr = InetAddress.getByName(controlIp);
                DatagramPacket packet = new DatagramPacket(data, data.length, addr, CONTROL_PORT);
                socket.send(packet);
                Log.d(TAG, "Sent command: " + cmd);
            } catch (Exception e) {
                Log.e(TAG, "Command send failed", e);
            }
        }).start();
    }

}