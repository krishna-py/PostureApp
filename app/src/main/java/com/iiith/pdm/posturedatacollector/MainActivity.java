package com.iiith.pdm.posturedatacollector;

import static android.content.ContentValues.TAG;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {

    private TextView deviceId;
    private TextView deviceStatus;
    private TextView logSummary;
    private Switch startCapture;
    private CheckBox isNeutralSpine;

    private enum CurrentActivity {
        SITTING,
        STANDING,
        WALKING,
        CYCLING,
        DRIVINGBIKE,
        DRIVINGCAR
    }

    private CurrentActivity currentActivity = null;

    private final String deviceName = "HC-05";
    private final String deviceAddr = "00:22:12:01:72:6A";
    public static BluetoothSocket btSocket = null;
    public static ConnectedThread connectedThread = null;
    public static Uri csvFilePath;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        deviceId = (TextView) findViewById(R.id.deviceId);
        deviceId.setText(deviceName + " @ " + deviceAddr);

        deviceStatus = (TextView) findViewById(R.id.deviceStatus);
        deviceStatus.setText("Searching ...");

        logSummary = (TextView) findViewById(R.id.logSummary);
        logSummary.setText("...");

        startCapture = (Switch) findViewById(R.id.startCapture);
        startCapture.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                if (connectedThread != null) {
                    connectedThread.record(b);
                }

                if (b)
                    Toast.makeText(MainActivity.this, "Recording to file ...", Toast.LENGTH_SHORT).show();
                else
                    Toast.makeText(MainActivity.this, "Recording stopped", Toast.LENGTH_SHORT).show();
            }
        });

        isNeutralSpine = (CheckBox) findViewById(R.id.idNeutralPosture);
        RadioButton activitySitting = (RadioButton) findViewById(R.id.optSitting);
        RadioButton activityStanding = (RadioButton) findViewById(R.id.optStanding);
        RadioButton activityWalk = (RadioButton) findViewById(R.id.optWalking);
        RadioButton activityCycling = (RadioButton) findViewById(R.id.optCycling);
        RadioButton activityBike = (RadioButton) findViewById(R.id.optBikeRiding);
        RadioButton activityCar = (RadioButton) findViewById(R.id.optCarDriving);

        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
//            ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 2);
            return;
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        Thread btConnect = new Thread(new BTConnectingThread());
        btConnect.start();
    }

    public class BTConnectingThread implements Runnable {

        private final int MAX_RETRY = 3;
        private int numRetry = 0;
        private boolean is_successful = false;

        @Override
        public void run() {
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            BluetoothDevice bluetoothDevice = bluetoothAdapter.getRemoteDevice(deviceAddr);
            deviceStatus.setText("Connecting ...");

            UUID uuid = bluetoothDevice.getUuids()[0].getUuid();
            is_successful = false;
            while (btSocket == null || !btSocket.isConnected()) {
                try {
                    bluetoothAdapter.cancelDiscovery();
                    btSocket = bluetoothDevice.createRfcommSocketToServiceRecord(uuid);
                    btSocket.connect();
                    deviceStatus.setText("Connected");
                    logSummary.setText("Device Connected");
                    is_successful = true;
                } catch (IOException e) {
                    numRetry += 1;
                    Log.e(TAG, "Bluetooth Connection Error", e);
                    logSummary.setText("Failed to Connect to device. Trying again in 1 sec. (" + numRetry + "/" + MAX_RETRY + ")");

                    if (numRetry < MAX_RETRY) {
                        SystemClock.sleep(1000);
                    } else {
                        logSummary.setText("Failed to Connect to device. Restart App and Try again. (" + numRetry + "/" + MAX_RETRY + ")");
                        break;
                    }
                }
            }

            if (is_successful) {
                // create a local file
                createFile();

                // start BT connection and writing to log file
                try {
                    connectedThread = new ConnectedThread(btSocket);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                connectedThread.start();
            } else {
                startCapture.setEnabled(false);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        connectedThread.cancel();
        try {
            connectedThread.join(1000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    // Request code for creating a PDF document.
    private static final int CREATE_FILE = 1;

    private void createFile() {
        Long dateValueInLong = System.currentTimeMillis();
        Calendar calendar = Calendar.getInstance();
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("dd.MM.yyyy-HH:mm:ss-aaa-z");
        String dateTime = simpleDateFormat.format(calendar.getTime()).toString();

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_TITLE, "Dataset-Session-" + dateTime + ".csv");
        startActivityForResult(intent, CREATE_FILE);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode,
                                 Intent resultData) {
        if (requestCode == CREATE_FILE && resultCode == Activity.RESULT_OK) {
            Uri uri = null;
            if (resultData != null) {
                csvFilePath = resultData.getData();
                logSummary.setText("log: " + csvFilePath);
            }
        }
    }

    /* =============================== Thread for Data Transfer =========================================== */
    public static class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;
        private final BufferedWriter writer;

        private final AtomicBoolean running = new AtomicBoolean(false);
        private final AtomicBoolean recording = new AtomicBoolean(false);

        public ConnectedThread(BluetoothSocket socket) throws IOException {
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            // Get the input and output streams, using temp objects because
            // member streams are final
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
            }

            mmInStream = tmpIn;
            mmOutStream = tmpOut;

            writer = new BufferedWriter(new FileWriter(new File(csvFilePath.toString())));
        }

        public void run() {
            running.set(true);

            byte[] buffer = new byte[1024];  // buffer store for the stream
            int bytes = 0; // bytes returned from read()
            // Keep listening to the InputStream until an exception occurs
            while (running.get()) {
                try {
                    /*
                    Read from the InputStream from Arduino until termination character is reached.
                    Then send the whole String message to GUI Handler.
                     */
                    buffer[bytes] = (byte) mmInStream.read();
                    String readMessage;
                    if (buffer[bytes] == '\n') {
                        readMessage = new String(buffer, 0, bytes);
                        Log.i("Arduino Message", readMessage);
                        if (recording.get())
                            writer.write(readMessage);

                        bytes = 0;
                    } else {
                        bytes++;
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    break;
                }
            }

            try {
                mmSocket.close();
                writer.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

        }

        /* Call this from the main activity to shutdown the connection */
        public void cancel() {
            running.set(false);
        }

        public void record(boolean v) {
            recording.set(v);
        }
    }

}
