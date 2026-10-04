package com.example.noisetbttest;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothProfile;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.view.View;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final String MAC = "00:00:00:D1:51:8D";

    private static final UUID SERVICE =
            UUID.fromString("16186f00-0000-1000-8000-00807f9b34fb");
    private static final UUID NOTIFY_UUID =
            UUID.fromString("16186f01-0000-1000-8000-00807f9b34fb");
    private static final UUID WRITE_UUID =
            UUID.fromString("16186f02-0000-1000-8000-00807f9b34fb");
    private static final UUID CCCD =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView logView;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar;
    private BluetoothGattCharacteristic ackChar;
    private boolean connected = false;

    private final byte[] PING = hex("000000000100");
    private final byte[] ACK_OK = hex("000001010000");
    private final byte[] ACK_END = hex("000001000000");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);

        TextView title = new TextView(this);
        title.setText("Noise ColorFit Pro 5 – TBT BLE Test");
        title.setTextSize(22);
        root.addView(title);

        TextView info = new TextView(this);
        info.setText("Watch: PRO 5_518D\\nMAC: " + MAC);
        info.setTextSize(16);
        root.addView(info);

        Button connect = new Button(this);
        connect.setText("1. CONNECT WATCH");
        root.addView(connect);

        Button test = new Button(this);
        test.setText("2. SEND TBT TEST");
        test.setEnabled(false);
        root.addView(test);

        Button find = new Button(this);
        find.setText("3. FIND WATCH (VIBRATE)");
        find.setEnabled(false);
        root.addView(find);

        logView = new TextView(this);
        logView.setTextSize(13);
        logView.setTextIsSelectable(true);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);

        connect.setOnClickListener(v -> connectWatch(test, find));
        test.setOnClickListener(v -> sendTbtTest());
        find.setOnClickListener(v -> sendFrame(frame(0xA1), "FIND WATCH"));
    }

    private void connectWatch(Button test, Button find) {
        if (!hasBluetoothPermission()) {
            requestBluetoothPermission();
            return;
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            log("Bluetooth OFF. Turn it ON and retry.");
            return;
        }

        try {
            BluetoothDevice device = adapter.getRemoteDevice(MAC);
            log("Connecting to " + MAC + " ...");
            gatt = device.connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE);
        } catch (Exception e) {
            log("Connect error: " + e.getMessage());
        }
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            runOnUiThread(() -> {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connected = true;
                    log("CONNECTED. Discovering services...");
                    try { g.discoverServices(); } catch (SecurityException e) { log(e.getMessage()); }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    connected = false;
                    log("DISCONNECTED (status " + status + ")");
                }
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Service discovery failed: " + status);
                return;
            }

            android.bluetooth.BluetoothGattService svc = g.getService(SERVICE);
            if (svc == null) {
                log("ERROR: 16186f00 service not found.");
                return;
            }

            writeChar = svc.getCharacteristic(WRITE_UUID);
            ackChar = svc.getCharacteristic(NOTIFY_UUID);

            if (writeChar == null || ackChar == null) {
                log("ERROR: required characteristics not found.");
                return;
            }

            log("Found command 16186f02 + ACK 16186f01.");
            enableNotifications(g);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                log("Notifications enabled.");
                handler.postDelayed(() -> startupHandshake(), 300);
            } else {
                log("Notification enable failed: " + status);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g,
                                             BluetoothGattCharacteristic c) {
            byte[] data = c.getValue();
            log("RX: " + bytesToHex(data));
        }
    };

    private void enableNotifications(BluetoothGatt g) {
        try {
            g.setCharacteristicNotification(ackChar, true);
            BluetoothGattDescriptor d = ackChar.getDescriptor(CCCD);
            if (d == null) {
                log("No CCCD found; continuing without notification subscription.");
                handler.postDelayed(() -> startupHandshake(), 300);
                return;
            }
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            } else {
                d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                g.writeDescriptor(d);
            }
        } catch (Exception e) {
            log("Notification setup error: " + e.getMessage());
        }
    }

    private void startupHandshake() {
        if (!connected) return;
        log("Startup PING -> ACK");
        writeNoResponse(writeChar, PING);
        handler.postDelayed(() -> writeNoResponse(ackChar, ACK_OK), 400);
        handler.postDelayed(() -> log("Ready for test."), 650);
    }

    private void sendTbtTest() {
        if (!connected || writeChar == null || ackChar == null) {
            log("Not connected.");
            return;
        }

        // Notification payload documented by the public reverse-engineering write-up:
        // field 13 { field 2 { app, package, title, message, "msg" } }
        byte[] packet = buildNotification(
                "TBT",
                "com.example.noisetbttest",
                "TBT TEST 100m -> RIGHT"
        );

        sendFrame(packet, "TBT TEST");
    }

    private void sendFrame(byte[] packet, String label) {
        log("TX " + label + ": " + bytesToHex(packet));

        writeNoResponse(writeChar, PING);
        handler.postDelayed(() -> writeNoResponse(writeChar, packet), 150);
        handler.postDelayed(() -> writeNoResponse(ackChar, ACK_OK), 250);
        handler.postDelayed(() -> writeNoResponse(ackChar, ACK_END), 280);
    }

    private void writeNoResponse(BluetoothGattCharacteristic c, byte[] data) {
        if (c == null || gatt == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeCharacteristic(c, data,
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            } else {
                c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                c.setValue(data);
                gatt.writeCharacteristic(c);
            }
            log("TX: " + bytesToHex(data));
        } catch (Exception e) {
            log("Write error: " + e.getMessage());
        }
    }

    // ---------- Protocol builders ----------

    private static byte[] frame(int opcode) {
        return concat(new byte[]{0x01, 0x00, 0x08}, varint(opcode));
    }

    private static byte[] frame(int opcode, byte[] payload) {
        return concat(new byte[]{0x01, 0x00, 0x08}, varint(opcode), payload);
    }

    private static byte[] buildNotification(String app, String pkg, String message) {
        byte[] inner = concat(
                pbString(1, app.toLowerCase()),
                pbString(2, pkg),
                pbString(3, app),
                pbString(4, message),
                pbString(5, "msg")
        );
        byte[] payload = pbBytes(13, pbBytes(2, inner));
        return frame(0xB3, payload);
    }

    private static byte[] pbString(int field, String value) {
        return pbBytes(field, value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] pbBytes(int field, byte[] data) {
        return concat(varint((field << 3) | 2), varint(data.length), data);
    }

    private static byte[] varint(int n) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        while (true) {
            int b = n & 0x7F;
            n >>>= 7;
            if (n != 0) b |= 0x80;
            out.write(b);
            if (n == 0) break;
        }
        return out.toByteArray();
    }

    private static byte[] hex(String s) {
        s = s.replaceAll("\\s+", "");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    private static byte[] concat(byte[]... arrays) {
        int len = 0;
        for (byte[] a : arrays) len += a.length;
        byte[] out = new byte[len];
        int p = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, out, p, a.length);
            p += a.length;
        }
        return out;
    }

    private static String bytesToHex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02X ", x & 0xff));
        return s.toString().trim();
    }

    private void log(String s) {
        runOnUiThread(() -> {
            logView.append(s + "\\n");
            logView.post(() -> ((ScrollView) logView.getParent()).fullScroll(View.FOCUS_DOWN));
        });
    }

    private boolean hasBluetoothPermission() {
        return Build.VERSION.SDK_INT < 31 ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        == PackageManager.PERMISSION_GRANTED;
    }

    private void requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
            }, 100);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (gatt != null) gatt.close();
        } catch (Exception ignored) {}
    }
}
