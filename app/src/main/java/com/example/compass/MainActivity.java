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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

    private static final int MODE_NONE        = 0;
    private static final int MODE_ROT_VEC    = 1; // fused rotation vector (needs gyro)
    private static final int MODE_GEO_VEC    = 2; // geomagnetic rotation vector (no gyro)
    private static final int MODE_ORIENTATION = 3; // deprecated TYPE_ORIENTATION, direct azimuth
    private static final int MODE_ACCEL_MAG  = 4; // raw accel + magnetometer

    private SensorManager sensorManager;
    private Sensor rotationSensor;    // TYPE_ROTATION_VECTOR or TYPE_GEOMAGNETIC_ROTATION_VECTOR
    private Sensor accelSensor;
    private Sensor magnetSensor;
    private int sensorMode = MODE_NONE;

    private CompassView compassView;
    private TextView statusText;
    private EditText ipEditText;
    private EditText portEditText;
    private Button sendToggleButton;

    // Sensor data for accel+mag fallback
    private final float[] gravity = new float[3];
    private final float[] geomagnetic = new float[3];
    private boolean hasGravity = false;
    private boolean hasGeomagnetic = false;

    // Smoothed azimuth
    private float smoothedAzimuth = 0f;
    private boolean firstReading = true;
    private static final float ALPHA = 0.15f;

    // Sending state
    private boolean isSending = false;
    private final Handler sendHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final int SEND_INTERVAL_MS = 500; // 2 Hz

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

    @SuppressWarnings("deprecation")
    private void setupSensors() {
        // 1. Fused rotation vector (needs gyroscope)
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (rotationSensor != null) {
            sensorMode = MODE_ROT_VEC;
            statusText.setText("Sensor: rotation vector");
            return;
        }

        // 2. Geomagnetic rotation vector (no gyroscope needed)
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR);
        if (rotationSensor != null) {
            sensorMode = MODE_GEO_VEC;
            statusText.setText("Sensor: geomagnetic vector");
            return;
        }

        // 3. Deprecated TYPE_ORIENTATION — gives azimuth directly, works on any real compass chip
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION);
        if (rotationSensor != null) {
            sensorMode = MODE_ORIENTATION;
            statusText.setText("Sensor: orientation");
            return;
        }

        // 4. Raw accelerometer + magnetometer
        accelSensor  = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        magnetSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        if (accelSensor != null && magnetSensor != null) {
            sensorMode = MODE_ACCEL_MAG;
            statusText.setText("Sensor: accel+mag");
            return;
        }

        // Nothing matched — list all available sensors for debugging
        sensorMode = MODE_NONE;
        List<Sensor> all = sensorManager.getSensorList(Sensor.TYPE_ALL);
        StringBuilder sb = new StringBuilder("No compass sensor. Available (").append(all.size()).append("):");
        for (Sensor s : all) {
            sb.append("\n  type=").append(s.getType()).append(" ").append(s.getName());
        }
        statusText.setText(sb.toString());
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
                    getString(R.string.sent_to, azimuth, ip, port)));
        } catch (Exception e) {
            runOnUiThread(() -> statusText.setText(
                    getString(R.string.send_error, e.getMessage())));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        switch (sensorMode) {
            case MODE_ROT_VEC:
            case MODE_GEO_VEC:
            case MODE_ORIENTATION:
                sensorManager.registerListener(this, rotationSensor,
                        SensorManager.SENSOR_DELAY_UI);
                break;
            case MODE_ACCEL_MAG:
                sensorManager.registerListener(this, accelSensor,
                        SensorManager.SENSOR_DELAY_UI);
                sensorManager.registerListener(this, magnetSensor,
                        SensorManager.SENSOR_DELAY_UI);
                break;
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
        float azimuth = Float.NaN;

        switch (event.sensor.getType()) {
            case Sensor.TYPE_ROTATION_VECTOR:
            case Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR: {
                float[] rotMatrix = new float[9];
                float[] orientation = new float[3];
                SensorManager.getRotationMatrixFromVector(rotMatrix, event.values);
                SensorManager.getOrientation(rotMatrix, orientation);
                azimuth = (float) Math.toDegrees(orientation[0]);
                break;
            }
            case Sensor.TYPE_ORIENTATION:
                // values[0] is already azimuth in degrees [0, 360)
                azimuth = event.values[0];
                break;
            case Sensor.TYPE_ACCELEROMETER:
                System.arraycopy(event.values, 0, gravity, 0, 3);
                hasGravity = true;
                break;
            case Sensor.TYPE_MAGNETIC_FIELD:
                System.arraycopy(event.values, 0, geomagnetic, 0, 3);
                hasGeomagnetic = true;
                break;
        }

        if (Float.isNaN(azimuth) && hasGravity && hasGeomagnetic) {
            float[] rotMatrix = new float[9];
            float[] incMatrix = new float[9];
            if (SensorManager.getRotationMatrix(rotMatrix, incMatrix, gravity, geomagnetic)) {
                float[] orientation = new float[3];
                SensorManager.getOrientation(rotMatrix, orientation);
                azimuth = (float) Math.toDegrees(orientation[0]);
            }
        }

        if (Float.isNaN(azimuth)) return;

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
