package com.example.headsetbattery;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQUEST_BT = 100;
    private static final UUID[] RACE_UUIDS = new UUID[] {
            UUID.fromString("00000000-0000-0000-0099-aabbccddeeff"),
            UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    };
    private static final byte[] RIGHT_QUERY = hex("05 5A 03 00 D6 0C 00");
    private static final byte[] LEFT_QUERY  = hex("05 5A 03 00 D6 0C 01");
    private static final byte[] CASE_QUERY  = hex("05 5A 02 00 40 00");
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout root;
    private TextView subtitle, leftValue, rightValue, caseValue, detail;
    private TextView refreshButton;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(0);
        buildUi();
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_BT);
        } else refresh();
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(18, 12, 18, 12);
        root.setBackgroundColor(Color.BLACK);
        TextView title = text("AZ60 耳机电量", 18, Color.WHITE, true);
        subtitle = text("准备连接耳机…", 11, Color.LTGRAY, false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        leftValue = batteryColumn(row, "左耳");
        rightValue = batteryColumn(row, "右耳");
        caseValue = batteryColumn(row, "充电盒");
        refreshButton = text("点按读取 / 刷新", 12, Color.LTGRAY, false);
        refreshButton.setPadding(0, 12, 0, 4);
        refreshButton.setOnClickListener(v -> refresh());
        detail = text("独立直连模式 · 不需要手机转发", 9, Color.GRAY, false);
        root.addView(title, params(-1, -2));
        root.addView(subtitle, params(-1, -2));
        root.addView(row, params(-1, -2));
        root.addView(refreshButton, params(-1, -2));
        root.addView(detail, params(-1, -2));
        setContentView(root);
    }

    private TextView batteryColumn(LinearLayout parent, String label) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setPadding(3, 14, 3, 4);
        TextView value = text("--%", 23, Color.rgb(74, 222, 128), true);
        TextView caption = text(label, 10, Color.LTGRAY, false);
        col.addView(value, params(-2, -2));
        col.addView(caption, params(-2, -2));
        parent.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        return value;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value); t.setTextColor(color); t.setTextSize(size);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }
    private LinearLayout.LayoutParams params(int w, int h) { return new LinearLayout.LayoutParams(w, h); }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == REQUEST_BT) {
            if (Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) refresh();
            else setStatus("需要允许附近设备 / 蓝牙权限");
        }
    }

    private void refresh() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_BT); return;
        }
        refreshButton.setEnabled(false);
        setStatus("正在查找已配对的 Technics AZ60…");
        worker.execute(() -> {
            String resultStatus;
            int left = -1, right = -1, box = -1;
            try {
                BluetoothManager manager = (BluetoothManager)getSystemService(Context.BLUETOOTH_SERVICE);
                BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
                if (adapter == null || !adapter.isEnabled()) throw new IOException("手表蓝牙未开启");
                Set<BluetoothDevice> devices = adapter.getBondedDevices();
                BluetoothDevice target = null;
                for (BluetoothDevice d : devices) {
                    String name = d.getName();
                    if (name != null && (name.toUpperCase(Locale.ROOT).contains("AZ60") ||
                            name.toUpperCase(Locale.ROOT).contains("TECHNICS") && name.toUpperCase(Locale.ROOT).contains("EAH"))) {
                        target = d; break;
                    }
                }
                if (target == null) {
                    // Only fall back to a single connected-like candidate if its name is clearly an AZ60 variant.
                    throw new IOException("未找到已配对的 AZ60；请先在手表蓝牙设置中配对耳机");
                }
                String deviceName = target.getName();
                BatteryTriplet values = queryRace(target);
                left = values.left; right = values.right; box = values.box;
                if (left < 0 && right < 0 && box < 0) {
                    int system = readHiddenBattery(target);
                    if (system >= 0) { right = system; resultStatus = deviceName + " · 系统电量回退"; }
                    else resultStatus = deviceName + " · 已连接协议端点，但未识别电量响应";
                } else resultStatus = deviceName + " · AZ60 私有协议直读";
            } catch (SecurityException e) {
                resultStatus = "蓝牙权限不足，请允许附近设备权限";
            } catch (Exception e) {
                resultStatus = e.getMessage() == null ? "连接失败：" + e.getClass().getSimpleName() : e.getMessage();
            }
            final int l = left, r = right, c = box;
            final String status = resultStatus;
            runOnUiThread(() -> {
                leftValue.setText(l >= 0 ? l + "%" : "--%");
                rightValue.setText(r >= 0 ? r + "%" : "--%");
                caseValue.setText(c >= 0 ? c + "%" : "--%");
                subtitle.setText(status);
                refreshButton.setEnabled(true);
                getSharedPreferences("headset_battery", MODE_PRIVATE).edit()
                        .putInt("left", l).putInt("right", r).putInt("case", c)
                        .putString("status", status).putLong("updated", System.currentTimeMillis()).apply();
            });
        });
    }

    private BatteryTriplet queryRace(BluetoothDevice device) throws IOException {
        IOException last = null;
        for (UUID uuid : RACE_UUIDS) {
            BluetoothSocket socket = null;
            try {
                socket = device.createRfcommSocketToServiceRecord(uuid);
                socket.connect();
                socket.setSoTimeout(350);
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                BatteryTriplet values = new BatteryTriplet();
                values.right = queryComponent(in, out, RIGHT_QUERY, 0x0CD6, 0, 1600);
                values.left  = queryComponent(in, out, LEFT_QUERY, 0x0CD6, 1, 1600);
                values.box   = queryComponent(in, out, CASE_QUERY, 0x0040, -1, 1300);
                if (values.left >= 0 || values.right >= 0 || values.box >= 0) return values;
                last = new IOException("已建立 RFCOMM 连接，但未收到有效 RACE 电量帧");
            } catch (IOException e) {
                last = e;
            } finally {
                if (socket != null) try { socket.close(); } catch (IOException ignored) {}
            }
        }
        throw last == null ? new IOException("无法连接 AZ60 RACE 端点") : last;
    }

    private int queryComponent(InputStream in, OutputStream out, byte[] query, int raceId, int component, long timeoutMs) throws IOException {
        out.write(query); out.flush();
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            byte[] frame;
            try { frame = readFrame(in, end); }
            catch (java.net.SocketTimeoutException e) { continue; }
            if (frame == null) break;
            int type = frame[1] & 0xff;
            int length = (frame[2] & 0xff) | ((frame[3] & 0xff) << 8);
            if (length < 2 || frame.length < 4 + length) continue;
            int id = (frame[4] & 0xff) | ((frame[5] & 0xff) << 8);
            if (id != raceId) continue;
            if (raceId == 0x0CD6 && type == 0x5D && length >= 5) {
                int status = frame[6] & 0xff;
                int role = frame[7] & 0xff;
                int percent = frame[8] & 0xff;
                if (status == 0 && role == component && percent <= 100) return percent;
            } else if (raceId == 0x0040 && type == 0x5B && length >= 4) {
                int status = frame[6] & 0xff;
                int percent = frame[7] & 0xff;
                if (status == 0 && percent <= 100) return percent;
            }
        }
        return -1;
    }

    private byte[] readFrame(InputStream in, long end) throws IOException {
        int first;
        do {
            if (System.currentTimeMillis() >= end) return null;
            first = in.read();
        } while (first != 0x05 && first >= 0);
        if (first < 0) return null;
        int type = readByte(in, end), lo = readByte(in, end), hi = readByte(in, end);
        if (type < 0 || lo < 0 || hi < 0) return null;
        int len = lo | (hi << 8);
        if (len < 2 || len > 512) return new byte[]{0, (byte)type, (byte)lo, (byte)hi};
        byte[] frame = new byte[4 + len];
        frame[0] = 0x05; frame[1] = (byte)type; frame[2] = (byte)lo; frame[3] = (byte)hi;
        for (int i = 4; i < frame.length; i++) {
            int b = readByte(in, end); if (b < 0) return null; frame[i] = (byte)b;
        }
        return frame;
    }
    private int readByte(InputStream in, long end) throws IOException {
        while (System.currentTimeMillis() < end) {
            try { return in.read(); } catch (java.net.SocketTimeoutException e) { /* retry until deadline */ }
        }
        return -1;
    }
    private int readHiddenBattery(BluetoothDevice device) {
        try {
            Method m = BluetoothDevice.class.getDeclaredMethod("getBatteryLevel");
            m.setAccessible(true);
            Object value = m.invoke(device);
            if (value instanceof Integer) {
                int n = (Integer)value;
                if (n >= 0 && n <= 100) return n;
            }
        } catch (Throwable ignored) {}
        return -1;
    }
    private void setStatus(String s) { subtitle.setText(s); }
    private static byte[] hex(String s) {
        String[] parts = s.split("\\s+"); byte[] out = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = (byte)Integer.parseInt(parts[i], 16);
        return out;
    }
    private static class BatteryTriplet { int left = -1, right = -1, box = -1; }

    @Override protected void onDestroy() { super.onDestroy(); worker.shutdownNow(); }
}
