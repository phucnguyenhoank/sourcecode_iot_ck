import pandas as pd
import os

# Đọc CSV
df = pd.read_csv("Test.csv")

# Danh sách các class cần giữ lại
valid_class_ids = {14, 33, 34, 35}

# Lọc các dòng KHÔNG nằm trong class hợp lệ
invalid_rows = df[~df['ClassId'].isin(valid_class_ids)]

# Xóa ảnh tương ứng
for path in invalid_rows['Path']:
    if os.path.exists(path):
        os.remove(path)
        print(f"Đã xóa: {path}")
    else:
        print(f"Không tìm thấy: {path}")

print("Hoàn tất xóa ảnh không thuộc class hợp lệ.")
