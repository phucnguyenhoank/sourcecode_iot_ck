// File: MjpegInputStream.java
package com.example.traffic_sign;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;

/**
 * Simple MJPEG stream reader: finds and decodes JPEG frames by SOI/EOI markers.
 */
public class MjpegInputStream extends DataInputStream {
    public MjpegInputStream(InputStream in) {
        super(new BufferedInputStream(in, 1024 * 1024));
    }

    /**
     * Reads and returns the next JPEG frame as a Bitmap.
     * Returns null if end of stream.
     */
    public Bitmap readFrame() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int prev = read();
        if (prev == -1) return null;
        int cur;
        // find SOI marker (0xFFD8)
        while ((cur = read()) != -1) {
            if (prev == 0xFF && (cur & 0xFF) == 0xD8) {
                baos.write(0xFF);
                baos.write(0xD8);
                break;
            }
            prev = cur;
        }
        if (cur == -1) return null;
        // read until EOI marker (0xFFD9)
        while ((cur = read()) != -1) {
            baos.write(cur);
            if (prev == 0xFF && (cur & 0xFF) == 0xD9) {
                break;
            }
            prev = cur;
        }
        if (cur == -1) return null;
        byte[] frameData = baos.toByteArray();
        return BitmapFactory.decodeByteArray(frameData, 0, frameData.length);
    }
}
