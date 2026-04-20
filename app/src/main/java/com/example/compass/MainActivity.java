package com.example.compass;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

    private SensorManager sensorManager;
    private Sensor rotationSensor;
    private Sensor accelSensor;
    private Sensor magnetSensor;

    private CompassView compassView;
    private TextView statusText;
    private EditText ipEditText;
    private EditText portEditText;
    private Button sendToggleButton;

    // Sensor data
    private float[] gravity = new float[3];
    private float[] geomagnetic = new float[3];
    private boolean hasGravity = false;
    private boolean hasGeomagnetic = false;
    private boolean useRotationVector = false;

    // Smoothed azimuth
    private float smoothedAzimuth = 0f;
    private boolean firstReading = true;
    private static final float ALPHA = 0.15f;

    // Sending state
    private boolean isSending = false;
    private final Handler sendHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final int SEND_INTERVAL_MS = 100; // 10 Hz

    private final Runnable sendRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isSending) return;

            final String ip = ipEditText.getText().toString().trim();
            final String portStr = portEditText.getText().toString().trim();
            final float azimuth = smoothedAzimuth;

            if (ip.isEmpty()) {
                statusText.setText("No IP set");
                sendHandler.postDelayed(this, SEND_INTERVAL_MS);
                return;
            }

            int port;
            try {
                port = Integer.parseInt(portStr);
            } catch (NumberFormatException e) {
                statusText.setText("Invalid port");
                sendHandler.postDelayed(this, SEND_INTERVAL_MS);
                return;
            }

            final int finalPort = port;
            executor.execute(() -> sendUdp(ip, finalPort, azimuth));
            sendHandler.postDelayed(this, SEND_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Keep screen on while the app is active
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        compassView = findViewById(R.id.compassView);
        statusText = findViewById(R.id.statusText);
        ipEditText = findViewById(R.id.ipEditText);
        portEditText = findViewById(R.id.portEditText);
        sendToggleButton = findViewById(R.id.sendToggleButton);

        sendToggleButton.setOnClickListener(v -> toggleSending());

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        setupSensors();
    }

    private void setupSensors() {
        // Prefer TYPE_ROTATION_VECTOR (fused, more stable)
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (rotationSensor != null) {
            useRotationVector = true;
        } else {
            // Fallback: accelerometer + magnetometer
            accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            magnetSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
            if (accelSensor == null || magnetSensor == null) {
                Toast.makeText(this, "Compass sensor not available", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void toggleSending() {
        isSending = !isSending;
        if (isSending) {
            sendToggleButton.setText(R.string.stop_sending);
            sendToggleButton.setBackgroundColor(getResources().getColor(R.color.stop_color));
            sendHandler.post(sendRunnable);
            statusText.setText(R.string.sending);
        } else {
            sendToggleButton.setText(R.string.start_sending);
            sendToggleButton.setBackgroundColor(getResources().getColor(R.color.start_color));
            statusText.setText(R.string.idle);
        }
    }

    private void sendUdp(String ip, int port, float azimuth) {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(500);
            String message = String.format("%.2f\n", azimuth);
            byte[] data = message.getBytes("UTF-8");
            InetAddress address = InetAddress.getByName(ip);
            DatagramPacket packet = new DatagramPacket(data, data.length, address, port);
            socket.send(packet);
            runOnUiThread(() -> statusText.setText(
                    getString(R.string.sent_to, ip, port, azimuth)));
        } catch (Exception e) {
            runOnUiThread(() -> statusText.setText(
                    getString(R.string.send_error, e.getMessage())));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (useRotationVector && rotationSensor != null) {
            sensorManager.registerListener(this, rotationSensor,
                    SensorManager.SENSOR_DELAY_UI);
        } else {
            if (accelSensor != null)
                sensorManager.registerListener(this, accelSensor,
                        SensorManager.SENSOR_DELAY_UI);
            if (magnetSensor != null)
                sensorManager.registerListener(this, magnetSensor,
                        SensorManager.SENSOR_DELAY_UI);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
        // Keep sending stopped when paused
        if (isSending) toggleSending();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float azimuth = -1f;

        if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            float[] rotMatrix = new float[9];
            float[] orientation = new float[3];
            SensorManager.getRotationMatrixFromVector(rotMatrix, event.values);
            SensorManager.getOrientation(rotMatrix, orientation);
            azimuth = (float) Math.toDegrees(orientation[0]);

        } else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, gravity, 0, 3);
            hasGravity = true;

        } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, geomagnetic, 0, 3);
            hasGeomagnetic = true;
        }

        if (!useRotationVector && hasGravity && hasGeomagnetic) {
            float[] rotMatrix = new float[9];
            float[] incMatrix = new float[9];
            boolean success = SensorManager.getRotationMatrix(
                    rotMatrix, incMatrix, gravity, geomagnetic);
            if (success) {
                float[] orientation = new float[3];
                SensorManager.getOrientation(rotMatrix, orientation);
                azimuth = (float) Math.toDegrees(orientation[0]);
            }
        }

        if (azimuth < 0f && azimuth != -1f) azimuth += 360f;
        if (azimuth < 0f) return; // no valid reading yet

        // Normalize to [0, 360)
        azimuth = ((azimuth % 360f) + 360f) % 360f;

        // Smooth with wrap-around handling
        if (firstReading) {
            smoothedAzimuth = azimuth;
            firstReading = false;
        } else {
            float delta = azimuth - smoothedAzimuth;
            if (delta > 180f) delta -= 360f;
            if (delta < -180f) delta += 360f;
            smoothedAzimuth += ALPHA * delta;
            smoothedAzimuth = ((smoothedAzimuth % 360f) + 360f) % 360f;
        }

        final float finalAzimuth = smoothedAzimuth;
        runOnUiThread(() -> compassView.setAzimuth(finalAzimuth));
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Not needed
    }
}
