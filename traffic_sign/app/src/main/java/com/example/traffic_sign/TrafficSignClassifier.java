package com.example.traffic_sign;

import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.util.Pair;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

public class TrafficSignClassifier {
    private final Interpreter interpreter;

    public TrafficSignClassifier(AssetManager assetManager, String modelPath) throws IOException {
        interpreter = new Interpreter(loadModelFile(assetManager, modelPath));
    }

    private MappedByteBuffer loadModelFile(AssetManager assetManager, String path) throws IOException {
        AssetFileDescriptor fileDescriptor = assetManager.openFd(path);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    public int predict(Bitmap bitmap) {
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, 30, 30, true);

        ByteBuffer inputBuffer = ByteBuffer.allocateDirect(1 * 30 * 30 * 3 * 4); // float32
        inputBuffer.order(ByteOrder.nativeOrder());

        for (int y = 0; y < 30; y++) {
            for (int x = 0; x < 30; x++) {
                int pixel = resized.getPixel(x, y);
                inputBuffer.putFloat(((pixel >> 16) & 0xFF) / 255.0f); // R
                inputBuffer.putFloat(((pixel >> 8) & 0xFF) / 255.0f);  // G
                inputBuffer.putFloat((pixel & 0xFF) / 255.0f);         // B
            }
        }

        float[][] output = new float[1][4]; // 4 class
        interpreter.run(inputBuffer, output);

        int predictedClass = -1;
        float maxProb = -1;
        for (int i = 0; i < 4; i++) {
            if (output[0][i] > maxProb) {
                maxProb = output[0][i];
                predictedClass = i;
            }
        }
        return predictedClass;
    }

    public Pair<Integer, Float> predictWithProb(Bitmap bitmap) {
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, 30, 30, true);

        ByteBuffer inputBuffer = ByteBuffer.allocateDirect(1 * 30 * 30 * 3 * 4); // float32
        inputBuffer.order(ByteOrder.nativeOrder());

        for (int y = 0; y < 30; y++) {
            for (int x = 0; x < 30; x++) {
                int pixel = resized.getPixel(x, y);
                inputBuffer.putFloat(((pixel >> 16) & 0xFF) / 255.0f); // R
                inputBuffer.putFloat(((pixel >> 8) & 0xFF) / 255.0f);  // G
                inputBuffer.putFloat((pixel & 0xFF) / 255.0f);         // B
            }
        }

        float[][] output = new float[1][4]; // 4 classes
        interpreter.run(inputBuffer, output);

        int predictedClass = -1;
        float maxProb = -1f;
        for (int i = 0; i < 4; i++) {
            if (output[0][i] > maxProb) {
                maxProb = output[0][i];
                predictedClass = i;
            }
        }

        return new Pair<>(predictedClass, maxProb);
    }
}

