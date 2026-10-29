package com.example.headsetbattery;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.ComponentName;
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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester;

public class MainActivity extends Activity {
    private static final int REQUEST_BT = 100;
    // Identity sentinel used to wake a waiting query when the Bluetooth stream reaches EOF.
    private static final byte[] READER_END = new byte[0];
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
            int left = -1, right = -1, box = -1, overall = -1;
            try {
                BluetoothManager manager = (BluetoothManager)getSystemService(Context.BLUETOOTH_SERVICE);
                BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
                if (adapter == null || !adapter.isEnabled()) throw new IOException("手表蓝牙未开启");
                Set<BluetoothDevice> devices = adapter.getBondedDevices();
                BluetoothDevice target = null;
                for (BluetoothDevice d : devices) {
                    String name = d.getName();
                    // Avoid sending the AZ60 RACE queries to other Technics models such as EAH-A800/AZ80.
                    if (name != null && name.toUpperCase(Locale.ROOT).contains("AZ60")) {
                        target = d;
                        break;
                    }
                }
                if (target == null) {
                    throw new IOException("未找到已配对的 AZ60；请先在手表蓝牙设置中配对耳机");
                }
                String deviceName = target.getName();
                IOException protocolError = null;
                try {
                    BatteryTriplet values = queryRace(target);
                    left = values.left;
                    right = values.right;
                    box = values.box;
                } catch (IOException e) {
                    protocolError = e;
                }
                if (left < 0 && right < 0 && box < 0) {
                    int system = readHiddenBattery(target);
                    if (system >= 0) {
                        overall = system;
                        resultStatus = deviceName + " · 系统整机电量回退（非单耳）";
                    } else if (protocolError != null) {
                        resultStatus = deviceName + " · 读取失败：" + protocolError.getMessage();
                    } else {
                        resultStatus = deviceName + " · 未识别到有效电量响应";
                    }
                } else {
                    resultStatus = deviceName + " · AZ60 私有协议直读";
                }
            } catch (SecurityException e) {
                resultStatus = "蓝牙权限不足，请允许附近设备权限";
            } catch (Exception e) {
                resultStatus = e.getMessage() == null ? "连接失败：" + e.getClass().getSimpleName() : e.getMessage();
            }
            final int l = left, r = right, c = box, o = overall;
            final String status = resultStatus;
            runOnUiThread(() -> {
                leftValue.setText(l >= 0 ? l + "%" : "--%");
                rightValue.setText(r >= 0 ? r + "%" : "--%");
                caseValue.setText(c >= 0 ? c + "%" : "--%");
                subtitle.setText(status);
                detail.setText(o >= 0 ? "系统整机电量：" + o + "%（非单耳电量）" : "独立直连模式 · 不需要手机转发");
                refreshButton.setEnabled(true);
                getSharedPreferences("headset_battery", MODE_PRIVATE).edit()
                        .putInt("left", l).putInt("right", r).putInt("case", c).putInt("overall", o)
                        .putString("status", status).putLong("updated", System.currentTimeMillis()).apply();
                // Ask the watch-face system to refresh any active complication using this source.
                try {
                    ComponentName source = new ComponentName(MainActivity.this, HeadsetBatteryComplicationService.class);
                    ComplicationDataSourceUpdateRequester.create(MainActivity.this, source).requestUpdateAll();
                } catch (RuntimeException ignored) {
                    // App battery readings remain available even if the watch-face system rejects the update request.
                }
            });
        });
    }

    private BatteryTriplet queryRace(BluetoothDevice device) throws IOException {
        IOException last = null;
        for (UUID uuid : RACE_UUIDS) {
            BluetoothSocket socket = null;
            Thread readerThread = null;
            try {
                socket = device.createRfcommSocketToServiceRecord(uuid);
                socket.connect();
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                BlockingQueue<byte[]> frames = new LinkedBlockingQueue<>(32);
                AtomicReference<IOException> readerFailure = new AtomicReference<>();
                readerThread = new Thread(() -> {
                    try {
                        while (!Thread.currentThread().isInterrupted()) {
                            byte[] frame = readFrame(in);
                            if (frame == null) {
                                readerFailure.compareAndSet(null, new IOException("蓝牙数据流已关闭"));
                                signalReaderEnd(frames);
                                break;
                            }
                            // Keep the newest incoming frames if a noisy endpoint floods the queue.
                            if (!frames.offer(frame)) {
                                frames.poll();
                                frames.offer(frame);
                            }
                        }
                    } catch (IOException e) {
                        readerFailure.compareAndSet(null, e);
                        signalReaderEnd(frames);
                    }
                }, "az60-race-reader");
                readerThread.setDaemon(true);
                readerThread.start();

                BatteryTriplet values = new BatteryTriplet();
                try {
                    values.right = queryComponent(out, frames, RIGHT_QUERY, 0x0CD6, 0, 1600, readerFailure);
                } catch (IOException e) {
                    if (Thread.currentThread().isInterrupted()) throw e;
                    if (readerFailure.get() != null) {
                        if (values.left >= 0 || values.right >= 0 || values.box >= 0) return values;
                        throw e;
                    }
                    last = e;
                }
                try {
                    values.left = queryComponent(out, frames, LEFT_QUERY, 0x0CD6, 1, 1600, readerFailure);
                } catch (IOException e) {
                    if (Thread.currentThread().isInterrupted()) throw e;
                    if (readerFailure.get() != null) {
                        if (values.left >= 0 || values.right >= 0 || values.box >= 0) return values;
                        throw e;
                    }
                    last = e;
                }
                try {
                    values.box = queryComponent(out, frames, CASE_QUERY, 0x0040, -1, 1300, readerFailure);
                } catch (IOException e) {
                    if (Thread.currentThread().isInterrupted()) throw e;
                    if (readerFailure.get() != null) {
                        if (values.left >= 0 || values.right >= 0 || values.box >= 0) return values;
                        throw e;
                    }
                    last = e;
                }
                // Keep any successful component even if the endpoint drops the socket mid-session.
                if (values.left >= 0 || values.right >= 0 || values.box >= 0) return values;
                IOException readError = readerFailure.get();
                if (readError != null) {
                    last = new IOException("RACE 数据流中断：" + readError.getMessage(), readError);
                } else if (last == null) {
                    last = new IOException("已建立 RFCOMM 连接，但未收到有效 RACE 电量帧");
                }
            } catch (IOException e) {
                last = e;
            } finally {
                if (socket != null) try { socket.close(); } catch (IOException ignored) {}
                if (readerThread != null) readerThread.interrupt();
            }
        }
        throw last == null ? new IOException("无法连接 AZ60 RACE 端点") : last;
    }

    private int queryComponent(
            OutputStream out,
            BlockingQueue<byte[]> frames,
            byte[] query,
            int raceId,
            int component,
            long timeoutMs,
            AtomicReference<IOException> readerFailure
    ) throws IOException {
        try {
            // The public reference implementation spaces ordered RACE writes by roughly 120 ms.
            Thread.sleep(120);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("读取 AZ60 电量时被中断", e);
        }
        out.write(query);
        out.flush();
        long end = System.currentTimeMillis() + timeoutMs;
        while (true) {
            long remaining = end - System.currentTimeMillis();
            if (remaining <= 0) return -1;

            byte[] frame;
            try {
                frame = frames.poll(remaining, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("读取 AZ60 电量时被中断", e);
            }
            if (frame == null) {
                IOException error = readerFailure.get();
                if (error != null) throw new IOException("蓝牙数据流读取失败：" + error.getMessage(), error);
                return -1;
            }
            if (frame == READER_END) {
                IOException error = readerFailure.get();
                if (error != null) throw new IOException("蓝牙连接提前关闭：" + error.getMessage(), error);
                throw new IOException("蓝牙连接提前关闭");
            }
            if (frame.length < 6 || (frame[0] & 0xff) != 0x05) continue;
            int type = frame[1] & 0xff;
            int length = (frame[2] & 0xff) | ((frame[3] & 0xff) << 8);
            if (length < 2 || length > 512 || frame.length < 4 + length) continue;
            int id = (frame[4] & 0xff) | ((frame[5] & 0xff) << 8);
            if (id != raceId) continue;
            if (raceId == 0x0CD6 && type == 0x5D && length >= 5 && frame.length >= 9) {
                int status = frame[6] & 0xff;
                int role = frame[7] & 0xff;
                int percent = frame[8] & 0xff;
                if (status == 0 && role == component && percent <= 100) return percent;
            } else if (raceId == 0x0040 && type == 0x5B && length >= 4 && frame.length >= 8) {
                int status = frame[6] & 0xff;
                int percent = frame[7] & 0xff;
                if (status == 0 && percent <= 100) return percent;
            }
        }
    }

    private static void signalReaderEnd(BlockingQueue<byte[]> frames) {
        if (!frames.offer(READER_END)) {
            // Make room for the terminal signal without discarding the whole queue of responses.
            frames.poll();
            frames.offer(READER_END);
        }
    }

    /** Reads one length-prefixed RACE frame. Called only on a dedicated reader thread. */
    private byte[] readFrame(InputStream in) throws IOException {
        while (true) {
            int first = in.read();
            if (first < 0) return null;
            if (first != 0x05) continue;

            int type = in.read();
            int lo = in.read();
            int hi = in.read();
            if (type < 0 || lo < 0 || hi < 0) return null;
            int len = lo | (hi << 8);
            if (len < 2 || len > 512) {
                // Return the invalid header so the caller can reject it and resynchronise.
                return new byte[]{0x05, (byte) type, (byte) lo, (byte) hi};
            }
            byte[] frame = new byte[4 + len];
            frame[0] = 0x05;
            frame[1] = (byte) type;
            frame[2] = (byte) lo;
            frame[3] = (byte) hi;
            for (int i = 4; i < frame.length; i++) {
                int b = in.read();
                if (b < 0) return null;
                frame[i] = (byte) b;
            }
            return frame;
        }
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
