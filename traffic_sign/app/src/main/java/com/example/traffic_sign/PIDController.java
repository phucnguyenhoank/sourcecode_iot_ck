package com.example.traffic_sign;

import android.util.Log;

public class PIDController {
    private final double Kp, Ki, Kd;
    private double integral = 0.0;
    private double lastError = 0.0;
    private long  lastTime   = 0;

    public PIDController(double Kp, double Ki, double Kd) {
        this.Kp = Kp;
        this.Ki = Ki;
        this.Kd = Kd;
        this.lastTime = System.currentTimeMillis();
    }

    /**
     * @param error   current error (offset)
     * @return        control output u
     */
    public double update(double error) {
        long now = System.currentTimeMillis();
        double dt = (now - lastTime) / 1000.0; // seconds
        this.lastTime = now;

        integral += error * dt;
        double derivative = dt > 0 ? (error - lastError) / dt : 0.0;
        lastError = error;
        Log.d("PIDController","Integral: " + integral + " | Derivative: " + derivative);

        return Kp * error + Ki * integral + Kd * derivative;
    }

    public void reset() {
        integral = 0;
        lastError = 0;
        lastTime = System.currentTimeMillis();
    }
}

