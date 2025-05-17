package com.example.traffic_sign;

import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

public class ImageProcessingUtils {

    /**
     * Phát hiện và cắt biển báo hình tròn từ ảnh đầu vào.
     *
     * @param frame ảnh đầu vào kiểu Mat (màu, định dạng BGR)
     * @return mảng gồm [cropped_resized, circleInfo]. Nếu không phát hiện được, cả hai đều là null.
     */
    public static Object[] detectAndCropCircularSign(Mat frame) {
        // Chuyển ảnh sang grayscale
        Mat gray = new Mat();
        Imgproc.cvtColor(frame, gray, Imgproc.COLOR_BGR2GRAY);

        // Làm mờ ảnh để giảm nhiễu
        Mat blurred = new Mat();
        Imgproc.GaussianBlur(gray, blurred, new Size(5, 5), 0);

        // Phát hiện hình tròn với HoughCircles
        Mat circles = new Mat();
        Imgproc.HoughCircles(
                blurred,
                circles,
                Imgproc.HOUGH_GRADIENT,
                1.2,    // dp: tỷ lệ phân giải
                50,     // minDist: khoảng cách tối thiểu giữa các hình tròn
                50,     // param1: ngưỡng trên cho Canny
                30,     // param2: ngưỡng tích lũy để xác định trung tâm hình tròn
                10,     // minRadius
                100     // maxRadius
        );

        // Kiểm tra nếu tìm thấy ít nhất 1 hình tròn
        if (circles.cols() > 0) {
            // Lấy thông tin của hình tròn đầu tiên
            double[] circleParams = circles.get(0, 0);
            int x = (int) Math.round(circleParams[0]);
            int y = (int) Math.round(circleParams[1]);
            int r = (int) Math.round(circleParams[2]);

            // Tăng margin để cắt không sát vào hình tròn (ở đây dùng 1.5 lần bán kính)
            int margin = (int) Math.round(r * 1.2);

            // Tính giới hạn cắt, đảm bảo không vượt quá biên ảnh
            int xMin = Math.max(x - margin, 0);
            int xMax = Math.min(x + margin, frame.width());
            int yMin = Math.max(y - margin, 0);
            int yMax = Math.min(y + margin, frame.height());

            Rect roi = new Rect(xMin, yMin, xMax - xMin, yMax - yMin);
            Mat cropped = new Mat(frame, roi);

            // Resize về kích thước 30x30 để đưa vào mô hình của bạn
            Mat croppedResized = new Mat();
            Imgproc.resize(cropped, croppedResized, new Size(30, 30));

            CircleInfo circleInfo = new CircleInfo(x, y, r);
            return new Object[]{croppedResized, circleInfo};
        }

        return new Object[]{null, null};
    }
}
