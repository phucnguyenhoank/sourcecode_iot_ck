import numpy as np
from PIL import Image
import tensorflow as tf
import cv2

### 4. Load model và chuyển đổi sang TensorFlow Lite
# loaded_model = tf.keras.models.load_model("my_model.keras")
# converter = tf.lite.TFLiteConverter.from_keras_model(loaded_model)
# tflite_model = converter.convert()

# # Lưu file TensorFlow Lite
# with open("my_model.tflite", "wb") as f:
#     f.write(tflite_model)
# print("Đã chuyển đổi và lưu model thành my_model.tflite")


### 7. Sử dụng camera với TensorFlow Lite
classes = {0: 'Stop', 1: 'Turn Right Ahead', 2: 'Turn Left Ahead', 3: 'Straight Only'}

# cap = cv2.VideoCapture(0)
stream_url = "http://192.168.146.130:81/stream"
cap = cv2.VideoCapture(stream_url)
if not cap.isOpened():
    print("Cannot open camera!")
    exit()

# Load TensorFlow Lite model
interpreter = tf.lite.Interpreter(model_path="traffict_sign_detect.tflite")
interpreter.allocate_tensors()
input_details = interpreter.get_input_details()
output_details = interpreter.get_output_details()

# Hàm phát hiện và cắt biển báo hình tròn
def detect_and_crop_circular_sign(frame):
    gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
    blurred = cv2.GaussianBlur(gray, (5, 5), 0)
    circles = cv2.HoughCircles(
        blurred, cv2.HOUGH_GRADIENT, dp=1.2, minDist=50,
        param1=50, param2=30, minRadius=10, maxRadius=100
    )
    if circles is not None:
        circles = np.round(circles[0, :]).astype("int")
        x, y, r = circles[0]
        margin = int(r * 1.2)
        x_min = max(x - margin, 0)
        x_max = min(x + margin, frame.shape[1])
        y_min = max(y - margin, 0)
        y_max = min(y + margin, frame.shape[0])
        cropped = frame[y_min:y_max, x_min:x_max]
        cropped_pil = Image.fromarray(cv2.cvtColor(cropped, cv2.COLOR_BGR2RGB))
        cropped_resized = cropped_pil.resize((30, 30))
        return np.array(cropped_resized), (x, y, r)
    return None, None

# Vòng lặp xử lý camera
while True:
    
    ret, frame = cap.read()
    if not ret:
        print("No frame received from camera")
        break

    cropped_sign, circle_info = detect_and_crop_circular_sign(frame)
    if cropped_sign is not None:
        img_array = cropped_sign.astype('float32') / 255.0
        img_array = np.expand_dims(img_array, axis=0)
        
        # Dự đoán với TensorFlow Lite
        interpreter.set_tensor(input_details[0]['index'], img_array)
        interpreter.invoke()
        output_data = interpreter.get_tensor(output_details[0]['index'])
        predicted_label = int(np.argmax(output_data, axis=1)[0])
        label_text = classes.get(predicted_label, "Unknown")
        
        if circle_info is not None:
            x, y, r = circle_info
            cv2.circle(frame, (x, y), r, (0, 255, 0), 2)
    else:
        label_text = "No sign detected"

    print("Detected:", label_text)
    cv2.putText(frame, label_text, (10, 30), cv2.FONT_HERSHEY_SIMPLEX, 
                1, (0, 255, 0), 2, cv2.LINE_AA)
    cv2.imshow("Camera", frame)
    
    if cv2.waitKey(1) & 0xFF == ord('q'):
        break

cap.release()
cv2.destroyAllWindows()