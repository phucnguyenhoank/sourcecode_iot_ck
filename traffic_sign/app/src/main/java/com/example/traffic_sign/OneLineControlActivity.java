package com.example.traffic_sign;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;


import org.opencv.android.CameraActivity;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.JavaCameraView;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class OneLineControlActivity extends AppCompatActivity {

    private static final String TAG = "OneLineControl";
    private static final int ESP32_PORT = 5000;
    private String esp32Ip;

    private String esp32CamIp;
    private DatagramSocket socket;

    private TextView offsetTextView;
    private ImageView imageView;

    // PID gains — tune these to your car’s dynamics!
    private static final double KP = 0.9;
    private static final double KI = 0.01;
    private static final double KD = 0.001;

    private PIDController steeringPID;
    private final int baseSpeed = 200;     // Nominal forward speed
    private int speedA, speedB;
    private final int maxSteeringSpeed = 200; // Max speed difference between MotorA (left) and MotorB (right)

    private int frameCounter = 0;

    private ExecutorService streamExecutor;
    private final ExecutorService sendExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_one_line_control);

        esp32Ip = getIntent().getStringExtra("ESP32_IP");
        esp32CamIp = getIntent().getStringExtra("ESP32CAM_IP");
        if (esp32Ip == null || esp32Ip.isEmpty() || esp32CamIp == null || esp32CamIp.isEmpty()) {
            Log.e(TAG, "Not all IP provided");
            Toast.makeText(this, "Not all IP provided", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        else{
            Log.e(TAG, "esp32:" + esp32Ip + ";" + "espcam:" + esp32CamIp);
        }


        // Initialize UDP socket in a background thread
        new Thread(() -> {
            try {
                socket = new DatagramSocket();
                Log.d(TAG, "UDP socket created");
            } catch (Exception e) {
                Log.e(TAG, "Socket creation failed", e);
                runOnUiThread(() -> Toast.makeText(this, "Socket creation failed", Toast.LENGTH_SHORT).show());
            }
        }).start();

        // Initialize OpenCV
        if (OpenCVLoader.initLocal()) {
            Log.i(TAG, "OpenCV loaded successfully");
        } else {
            Log.e(TAG, "OpenCV initialization failed!");
            Toast.makeText(this, "OpenCV initialization failed!", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // Initialize PID controller
        steeringPID = new PIDController(KP, KI, KD);

        // Initialize UI components
        offsetTextView = findViewById(R.id.offsetTextView);
        imageView = findViewById(R.id.imageView);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER); // Ensure proper scaling
        imageView.setVisibility(View.VISIBLE); // Ensure ImageView is visible

        // Start MJPEG stream reader
        new MjpegStreamReader().execute("http://" + esp32CamIp + ":81/stream");
    }

    // Custom MjpegInputStream class to parse MJPEG stream
    private static class MjpegInputStream extends DataInputStream {
        private final byte[] SOI_MARKER = {(byte) 0xFF, (byte) 0xD8};
        private final byte[] EOF_MARKER = {(byte) 0xFF, (byte) 0xD9};
        private final String CONTENT_LENGTH = "Content-Length";
        private final static int HEADER_MAX_LENGTH = 100;
        private final static int FRAME_MAX_LENGTH = 40000 + HEADER_MAX_LENGTH;

        public MjpegInputStream(InputStream in) {
            super(new BufferedInputStream(in, FRAME_MAX_LENGTH));
        }

        private int getEndOfSequence(DataInputStream in, byte[] sequence) throws IOException {
            int seqIndex = 0;
            byte c;
            for (int i = 0; i < FRAME_MAX_LENGTH; i++) {
                c = (byte) in.readUnsignedByte();
                if (c == sequence[seqIndex]) {
                    seqIndex++;
                    if (seqIndex == sequence.length) return i + 1;
                } else {
                    seqIndex = 0;
                }
            }
            return -1;
        }

        private int getStartOfSequence(DataInputStream in, byte[] sequence) throws IOException {
            int end = getEndOfSequence(in, sequence);
            return (end < 0) ? (-1) : (end - sequence.length);
        }

        private int parseContentLength(byte[] headerBytes) throws IOException, NumberFormatException {
            String headerStr = new String(headerBytes, StandardCharsets.UTF_8);
            int startIndex = headerStr.indexOf(CONTENT_LENGTH) + CONTENT_LENGTH.length();
            int endIndex = headerStr.indexOf("\r\n", startIndex);
            if (endIndex < 0) {
                throw new NumberFormatException("Invalid Content-Length: no newline found");
            }
            String headerValue = headerStr.substring(startIndex, endIndex).trim();
            Log.d(TAG, "Raw Content-Length: [" + headerValue + "]");
            headerValue = headerValue.replaceAll("[^0-9]", "");
            Log.d(TAG, "Cleaned Content-Length: [" + headerValue + "]");
            if (headerValue.isEmpty()) {
                throw new NumberFormatException("Invalid Content-Length: empty after cleaning");
            }
            return Integer.parseInt(headerValue);
        }

        public Bitmap readMjpegFrame() throws IOException {
            mark(FRAME_MAX_LENGTH);
            int headerLen = getStartOfSequence(this, SOI_MARKER);
            if (headerLen < 0) {
                Log.e(TAG, "Failed to find SOI marker");
                return null;
            }
            reset();
            byte[] header = new byte[headerLen];
            readFully(header);
            int contentLength;
            try {
                contentLength = parseContentLength(header);
            } catch (NumberFormatException e) {
                Log.e(TAG, "Failed to parse Content-Length", e);
                return null;
            }
            byte[] frameData = new byte[contentLength];
            readFully(frameData);
            Bitmap bitmap = BitmapFactory.decodeStream(new ByteArrayInputStream(frameData));
            if (bitmap == null) {
                Log.e(TAG, "Failed to decode bitmap from frame data");
            } else {
                Log.d(TAG, "Successfully decoded bitmap: " + bitmap.getWidth() + "x" + bitmap.getHeight());
            }
            return bitmap;
        }
    }

    // AsyncTask to read MJPEG stream and process frames
    private class MjpegStreamReader extends AsyncTask<String, Bitmap, Void> {
        private MjpegInputStream inputStream;

        @Override
        protected Void doInBackground(String... urls) {
            try {
                URL url = new URL(urls[0]);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setDoInput(true);
                connection.setConnectTimeout(5000); // Giữ nguyên thời gian chờ kết nối
                connection.setReadTimeout(0);       // Sửa thành 0: không giới hạn thời gian chờ đọc
                connection.connect();
                inputStream = new MjpegInputStream(connection.getInputStream());
                while (!isCancelled()) {
                    Bitmap frame = inputStream.readMjpegFrame();
                    if (frame != null) {
                        publishProgress(frame);
                    } else {
                        Log.w(TAG, "Null frame received from MJPEG stream");
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Error reading MJPEG stream", e);
                runOnUiThread(() -> Toast.makeText(OneLineControlActivity.this,
                        "Không thể kết nối với ESP32-CAM",
                        Toast.LENGTH_SHORT).show());
            }
            return null;
        }

        @Override
        protected void onProgressUpdate(Bitmap... frames) {
            for (Bitmap frame : frames) {
                if (frame != null) {
                    // Update ImageView
                    runOnUiThread(() -> {
                        imageView.setImageBitmap(frame);
                        imageView.invalidate(); // Force redraw
                        Log.d(TAG, "ImageView updated with frame: " + frame.getWidth() + "x" + frame.getHeight());
                    });
                    // Process frame with OpenCV
                    Mat mat = new Mat();
                    try {
                        Utils.bitmapToMat(frame, mat);
                        if (mat.empty()) {
                            Log.e(TAG, "Converted Mat is empty");
                            return;
                        }
                        Log.d(TAG, "Mat converted: " + mat.cols() + "x" + mat.rows());
                        processFrame(mat);
                    } catch (Exception e) {
                        Log.e(TAG, "Error converting Bitmap to Mat", e);
                    } finally {
                        mat.release(); // Release Mat to avoid memory leaks
                    }
                }
            }
        }

        @Override
        protected void onCancelled() {
            super.onCancelled();
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing MJPEG stream", e);
                }
            }
        }
    }

    private void processFrame(Mat rgba) {
        // Removed frame skipping for testing
         frameCounter++;
         if (frameCounter % 3 != 0) return;

        double offset = findTapePosition(rgba); // Sử dụng hàm mới

        double pidValue;
        if (Double.isNaN(offset)) {
            steeringPID.reset();
            pidValue = 0.0;
            speedA = speedB = 0;
        } else {
            // Chuẩn hóa offset về khoảng [-1.0, 1.0]
            double normalizedOffset = offset / (rgba.cols() / 2.0);
            pidValue = steeringPID.update(normalizedOffset);

            speedA = clamp((int) (baseSpeed * (1.0 - pidValue)));
            speedB = clamp((int) (baseSpeed * (1.0 + pidValue)));
        }

        sendCommand();

        // Update UI on main thread
        final String txt = Double.isNaN(offset)
                ? String.format(Locale.US, "Không phát hiện đường: %+.2f, %+.2f, %d, %d",
                offset, pidValue, speedA, speedB)
                : String.format(Locale.US, "Offset: %+.2f, %+.2f, %d, %d",
                offset, pidValue, speedA, speedB);

        runOnUiThread(() -> offsetTextView.setText(txt));
    }

    private double findTapePosition(Mat rgba) {
        // Chuyển ảnh sang grayscale
        Mat gray = new Mat();
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);

        // Lấy kích thước ảnh
        int H = gray.rows();
        int W = gray.cols();

        // Xác định vùng 10% dưới cùng
        int roiHeight = (int)(H * 0.1);
        int roiY = H - roiHeight;

        // Tìm tất cả pixel đen trong vùng quan tâm
        List<Integer> blackPixels = new ArrayList<>();

        for (int y = roiY; y < H; y++) {
            // Lấy từng hàng trong vùng quan tâm
            byte[] rowData = new byte[W];
            gray.row(y).get(0, 0, rowData);

            for (int x = 0; x < W; x++) {
                int pixelValue = rowData[x] & 0xFF; // Chuyển sang giá trị 0-255
                if (pixelValue < 50) { // Ngưỡng cho pixel đen
                    blackPixels.add(x);
                }
            }
        }

        if (blackPixels.isEmpty()) {
            return Double.NaN;
        }

        // Tính toán vị trí trung bình
        double sum = 0;
        for (int x : blackPixels) {
            sum += x;
        }
        double xMean = sum / blackPixels.size();

        return xMean - (W / 2.0); // Độ lệch so với trung tâm
    }

    public static double getLineOffset(Mat rgba,
                                       Scalar lower_hsv,
                                       Scalar upper_hsv,
                                       int morph_kernel_size) {
        // 1) Get image dimensions
        int h = rgba.rows();
        int w = rgba.cols();
        double img_center_x = w / 2.0;

        // 2) To HSV
        Mat hsv = new Mat();
        Imgproc.cvtColor(rgba, hsv, Imgproc.COLOR_RGB2HSV);

        // 3) Threshold for black
        Mat mask = new Mat();
        Core.inRange(hsv, lower_hsv, upper_hsv, mask);

        // 4) Morphology
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE,
                new Size(morph_kernel_size, morph_kernel_size));
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel, new Point(-1, -1), 2);
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel, new Point(-1, -1), 2);

        // 5) Find largest contour
        List<MatOfPoint> cnts = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(mask, cnts, hierarchy,
                Imgproc.RETR_EXTERNAL,
                Imgproc.CHAIN_APPROX_NONE);
        if (cnts.isEmpty()) {
            hsv.release();
            mask.release();
            hierarchy.release();
            return Double.NaN;
        }

        double maxA = 0;
        MatOfPoint largest = null;
        for (MatOfPoint c : cnts) {
            double A = Imgproc.contourArea(c);
            if (A > maxA) {
                maxA = A;
                largest = c;
            }
        }
        if (largest == null || maxA < 100) {
            hsv.release();
            mask.release();
            hierarchy.release();
            return Double.NaN;
        }

        // 6) Fit infinite line
        Mat line = new Mat();
        Imgproc.fitLine(new MatOfPoint2f(largest.toArray()),
                line,
                Imgproc.DIST_L2, 0, 0.01, 0.01);
        float[] v = new float[4];
        line.get(0, 0, v);
        float vx = v[0], vy = v[1], x0 = v[2], y0 = v[3];

        // 7) Intersection with bottom
        if (Math.abs(vy) < 1e-6) {
            hsv.release();
            mask.release();
            hierarchy.release();
            line.release();
            return Double.NaN;
        }
        double yb = h - 1;
        double xb = x0 + (yb - y0) * (vx / vy);

        // 8) Release Mats
        hsv.release();
        mask.release();
        hierarchy.release();
        line.release();

        // 9) Offset from center
        return (xb - img_center_x) / img_center_x;
    }

    public static double getLineOffset(Mat rgba) {
        Scalar lower_hsv = new Scalar(0, 0, 0);      // Adjusted for black
        Scalar upper_hsv = new Scalar(180, 255, 30); // Low value for black
        int morph_kernel_size = 5;
        return getLineOffset(rgba, lower_hsv, upper_hsv, morph_kernel_size);
    }

    private int clamp(int val) {
        return Math.max(0, Math.min(val, maxSteeringSpeed));
    }

    private void sendCommand() {
        String cmd = "forward," + speedA + "," + speedB + "\n";
        new Thread(() -> {
            try {
                if (socket != null && !socket.isClosed()) {
                    byte[] data = cmd.getBytes(StandardCharsets.UTF_8);
                    InetAddress addr = InetAddress.getByName(esp32Ip);
                    DatagramPacket packet = new DatagramPacket(data, data.length, addr, ESP32_PORT);
                    socket.send(packet);
                    Log.d(TAG, "Sent command: " + cmd);
                } else {
                    Log.e(TAG, "Socket is null or closed");
                }
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
    }
}