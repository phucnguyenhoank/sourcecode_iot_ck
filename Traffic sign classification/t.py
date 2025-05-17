import numpy as np
import pandas as pd
import matplotlib.pyplot as plt
import os
from PIL import Image
from pathlib import Path
from sklearn.model_selection import train_test_split
from sklearn.metrics import accuracy_score
import tensorflow as tf
import cv2

# In phiên bản TensorFlow để kiểm tra
print(f"TensorFlow Version: {tf.__version__}")
tf.random.set_seed(42)

# Các hằng số
ORIGINAL_NUM_CLASSES = 43  # Tổng số lớp trong dataset gốc
allowed_labels = {14, 17, 33, 34, 35}  # Các nhãn được phép
label_map = {14: 0, 17: 1, 33: 2, 34: 3, 35: 4}  # Ánh xạ nhãn về 0-4
NUM_CLASSES = len(allowed_labels)  # Số lớp sau khi lọc
IMG_SIZE = (30, 30)  # Kích thước ảnh đầu vào
cur_path = Path.cwd()  # Đường dẫn thư mục hiện tại

### 1. Load và tiền xử lý dữ liệu huấn luyện
data = []
labels = []

for i in range(ORIGINAL_NUM_CLASSES):
    if i not in allowed_labels:
        continue

    folder_path = cur_path / 'train' / str(i)
    if not folder_path.exists():
        print(f"Folder {folder_path} không tồn tại!")
        continue

    for img_name in os.listdir(folder_path):
        try:
            image = Image.open(folder_path / img_name)
            image = image.resize(IMG_SIZE)
            data.append(np.array(image))
            labels.append(label_map[i])
        except Exception as e:
            print(f"Lỗi khi load ảnh {img_name}: {e}")

data = np.array(data)
labels = np.array(labels)
print("Kích thước dữ liệu huấn luyện:", data.shape)
print("Kích thước nhãn huấn luyện:", labels.shape)

# Chia dữ liệu thành tập train và validation
X_train, X_val, y_train, y_val = train_test_split(
    data, labels, test_size=0.2, random_state=42
)

# One-hot encode nhãn
y_train = tf.keras.utils.to_categorical(y_train, NUM_CLASSES)
y_val = tf.keras.utils.to_categorical(y_val, NUM_CLASSES)

# Chuẩn hóa dữ liệu ảnh (0-1)
X_train = X_train.astype('float32') / 255.0
X_val = X_val.astype('float32') / 255.0

### 2. Xây dựng và huấn luyện model
model = tf.keras.Sequential([
    tf.keras.Input(shape=X_train.shape[1:]),  # Shape: (30, 30, 3)
    tf.keras.layers.Conv2D(32, (5, 5), activation='relu'),
    tf.keras.layers.Conv2D(32, (5, 5), activation='relu'),
    tf.keras.layers.MaxPooling2D((2, 2)),
    tf.keras.layers.Dropout(0.25),
    tf.keras.layers.Conv2D(64, (3, 3), activation='relu'),
    tf.keras.layers.Conv2D(64, (3, 3), activation='relu'),
    tf.keras.layers.MaxPooling2D((2, 2)),
    tf.keras.layers.Dropout(0.25),
    tf.keras.layers.Flatten(),
    tf.keras.layers.Dense(256, activation='relu'),
    tf.keras.layers.Dropout(0.5),
    tf.keras.layers.Dense(NUM_CLASSES, activation='softmax')
])

model.compile(
    loss='categorical_crossentropy',
    optimizer='adam',
    metrics=['accuracy']
)

# Huấn luyện model
history = model.fit(
    X_train,
    y_train,
    batch_size=32,
    epochs=15,
    validation_data=(X_val, y_val)
)

### 3. Lưu model dưới dạng .keras
model.save("my_model.keras")
print("Đã lưu model thành công dưới dạng my_model.keras")

### 4. Load model và chuyển đổi sang TensorFlow Lite
loaded_model = tf.keras.models.load_model("my_model.keras")
converter = tf.lite.TFLiteConverter.from_keras_model(loaded_model)
tflite_model = converter.convert()

# Lưu file TensorFlow Lite
with open("my_model.tflite", "wb") as f:
    f.write(tflite_model)
print("Đã chuyển đổi và lưu model thành my_model.tflite")

### 5. Vẽ biểu đồ accuracy và loss (tùy chọn)
plt.figure()
plt.plot(history.history['accuracy'], label='Train Accuracy')
plt.plot(history.history['val_accuracy'], label='Validation Accuracy')
plt.title('Độ chính xác của Model')
plt.xlabel('Epoch')
plt.ylabel('Accuracy')
plt.legend()
plt.show()

plt.figure()
plt.plot(history.history['loss'], label='Train Loss')
plt.plot(history.history['val_loss'], label='Validation Loss')
plt.title('Hàm mất mát của Model')
plt.xlabel('Epoch')
plt.ylabel('Loss')
plt.legend()
plt.show()

### 6. Đánh giá trên tập test (tùy chọn)
test_df = pd.read_csv('Test.csv')
test_df = test_df[test_df["ClassId"].isin(allowed_labels)]
true_labels = test_df["ClassId"].values
true_labels_mapped = [label_map[int(lbl)] for lbl in true_labels]
image_paths = test_df["Path"].values

test_data = []
for img_path in image_paths:
    try:
        image = Image.open(img_path)
        image = image.resize(IMG_SIZE)
        test_data.append(np.array(image))
    except Exception as e:
        print(f"Lỗi khi load ảnh test {img_path}: {e}")

X_test = np.array(test_data).astype('float32') / 255.0
predictions = loaded_model.predict(X_test)
predicted_labels = np.argmax(predictions, axis=1)
accuracy = accuracy_score(true_labels_mapped, predicted_labels)
print(f"Độ chính xác trên tập test: {accuracy * 100:.2f}%")

### 7. Sử dụng camera với TensorFlow Lite
classes = {0: 'Stop', 1: 'No Entry', 2: 'Turn Right Ahead', 3: 'Turn Left Ahead', 4: 'Straight Only'}

cap = cv2.VideoCapture(0)
if not cap.isOpened():
    print("Cannot open camera!")
    exit()

# Load TensorFlow Lite model
interpreter = tf.lite.Interpreter(model_path="my_model.tflite")
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