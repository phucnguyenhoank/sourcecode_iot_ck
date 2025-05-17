from flask import Flask, Response
import cv2

app = Flask(__name__)

def gen_frames():
    """Capture frames from webcam and encode as JPEG for MJPEG streaming."""
    cap = cv2.VideoCapture(0)
    if not cap.isOpened():
        raise RuntimeError("Could not open video capture device")
    while True:
        success, frame = cap.read()
        if not success:
            break
        # Resize if desired
        # frame = cv2.resize(frame, (640, 480))
        ret, buffer = cv2.imencode('.jpg', frame)
        if not ret:
            continue
        frame_bytes = buffer.tobytes()
        yield (b'--frame\r\n'
               b'Content-Type: image/jpeg\r\n\r\n' + frame_bytes + b'\r\n')

@app.route('/stream')
def video_feed():
    """Route for streaming the video feed."""
    return Response(gen_frames(),
                    mimetype='multipart/x-mixed-replace; boundary=frame')

if __name__ == '__main__':
    # Run the server on all interfaces, port 81
    app.run(host='0.0.0.0', port=81, threaded=True)
