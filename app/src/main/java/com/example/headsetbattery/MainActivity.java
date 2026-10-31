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
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;


import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Standalone Technics EAH-AZ battery reader and conservative RACE noise-mode controller.
 * Bluetooth is queried while this full battery screen is in the foreground, or after the
 * user explicitly presses Refresh / a noise-mode button. The Wear Tile performs its own live read.
 */
public class MainActivity extends Activity {
    private static final int REQUEST_BT = 100;
    private static final byte[] READER_END = new byte[0];
    private static final long QUERY_TIMEOUT_MS = 1600;
    private static final long CASE_TIMEOUT_MS = 1300;
    private static final long WRITE_INTERVAL_MS = 120;
    private static final UUID[] RACE_UUIDS = new UUID[] {
            UUID.fromString("00000000-0000-0000-0099-aabbccddeeff"),
            UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    };

    private static final byte[] RIGHT_QUERY = hex("05 5A 03 00 D6 0C 00");
    private static final byte[] LEFT_QUERY  = hex("05 5A 03 00 D6 0C 01");
    private static final byte[] CASE_QUERY  = hex("05 5A 02 00 40 00");
    private static final byte[] NOISE_QUERY = hex("05 5A 02 00 0A 00");

    private static final Pattern EAH_AZ_NAME = Pattern.compile(
            "(?i)(?:EAH[- ]?AZ\\d{2,3}[A-Z0-9]*|TECHNICS\\s+AZ\\d{2,3}[A-Z0-9]*|^AZ\\d{2,3}[A-Z0-9]*$)"
    );

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final AtomicReference<BluetoothSocket> activeSocket = new AtomicReference<>();
    private volatile boolean screenVisible = false;
    private volatile boolean pendingRefresh = false;

    private TextView subtitle, deviceLabel, leftValue, rightValue, caseValue, modeStatus, refreshButton;
    private TextView offButton, ancButton, ambientButton;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(0);
        buildUi();
        restoreCachedValues();
        if (hasBluetoothPermission()) {
            // Actual refresh is tied to the visible full battery screen in onResume().
        } else if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_BT);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        screenVisible = true;
        if (hasBluetoothPermission()) {
            if (busy.get()) {
                // A previous foreground read may still be unwinding after a quick screen switch.
                pendingRefresh = true;
            } else {
                pendingRefresh = false;
                refresh();
            }
        }
    }

    @Override protected void onPause() {
        screenVisible = false;
        // Do not keep a private headset session alive after the battery screen disappears.
        BluetoothSocket socket = activeSocket.getAndSet(null);
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) { }
        }
        super.onPause();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(16, 10, 16, 10);
        root.setBackgroundColor(Color.BLACK);

        TextView title = text("Technics 耳机电量", 18, Color.WHITE, true);
        subtitle = text("正在准备…", 10, Color.LTGRAY, false);
        deviceLabel = text("未连接设备", 9, Color.GRAY, false);

        LinearLayout batteryRow = new LinearLayout(this);
        batteryRow.setOrientation(LinearLayout.HORIZONTAL);
        batteryRow.setGravity(Gravity.CENTER);
        leftValue = batteryColumn(batteryRow, "左耳");
        rightValue = batteryColumn(batteryRow, "右耳");
        caseValue = batteryColumn(batteryRow, "充电盒");

        modeStatus = text("环境声模式：等待设备协议确认", 10, Color.LTGRAY, false);
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setGravity(Gravity.CENTER);
        offButton = modeButton(modeRow, "关闭", 0);
        ancButton = modeButton(modeRow, "降噪", 1);
        ambientButton = modeButton(modeRow, "通透", 2);
        setNoiseButtonsEnabled(false, -1);

        refreshButton = text("点按刷新电量", 12, Color.WHITE, true);
        refreshButton.setGravity(Gravity.CENTER);
        refreshButton.setPadding(12, 10, 12, 10);
        refreshButton.setBackground(pill(Color.rgb(42, 42, 42)));
        refreshButton.setOnClickListener(v -> refresh());

        TextView foot = text("本页面前台自动刷新；Tile 进入时独立实时读取", 8, Color.DKGRAY, false);

        root.addView(title, params(-1, -2));
        root.addView(subtitle, params(-1, -2));
        root.addView(deviceLabel, params(-1, -2));
        root.addView(batteryRow, params(-1, -2));
        root.addView(modeStatus, params(-1, -2));
        root.addView(modeRow, params(-1, -2));
        root.addView(refreshButton, params(-1, -2));
        root.addView(foot, params(-1, -2));
        setContentView(root);
    }

    private TextView batteryColumn(LinearLayout parent, String label) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setPadding(2, 10, 2, 7);
        TextView value = text("--%", 22, Color.rgb(74, 222, 128), true);
        TextView caption = text(label, 9, Color.LTGRAY, false);
        col.addView(value, params(-2, -2));
        col.addView(caption, params(-2, -2));
        parent.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        return value;
    }

    private TextView modeButton(LinearLayout parent, String label, int mode) {
        TextView button = text(label, 11, Color.WHITE, true);
        button.setPadding(8, 9, 8, 9);
        button.setBackground(pill(Color.rgb(55, 55, 55)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.setMargins(3, 4, 3, 4);
        parent.addView(button, lp);
        button.setOnClickListener(v -> setNoiseMode(mode));
        return button;
    }

    private GradientDrawable pill(int color) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(40f);
        bg.setStroke(1, Color.rgb(90, 90, 90));
        return bg;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextColor(color);
        t.setTextSize(size);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private LinearLayout.LayoutParams params(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    private boolean hasBluetoothPermission() {
        return Build.VERSION.SDK_INT < 31 ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == REQUEST_BT) {
            // onResume() also runs when the permission dialog closes. Let that callback
            // own the first refresh; avoid starting a second RFCOMM session if it is busy.
            if (hasBluetoothPermission()) {
                if (screenVisible && !busy.get()) refresh();
            } else {
                setStatus("需要允许“附近设备 / 蓝牙”权限");
            }
        }
    }

    private void restoreCachedValues() {
        // Stored values are retained for diagnostics only. Never present them as current on entry.
        setPercent(leftValue, -1);
        setPercent(rightValue, -1);
        setPercent(caseValue, -1);
        deviceLabel.setText("正在查找已配对耳机");
        subtitle.setText("进入页面后实时读取");
        modeStatus.setText("环境声模式：等待本次设备回读");
        setNoiseButtonsEnabled(false, -1);
    }

    private void setPercent(TextView view, int value) {
        view.setText(value >= 0 && value <= 100 ? value + "%" : "--%");
    }

    private void refresh() { runOperation(-1); }

    private void setNoiseMode(int desiredMode) {
        if (!hasBluetoothPermission()) {
            if (Build.VERSION.SDK_INT >= 31) requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_BT);
            return;
        }
        runOperation(desiredMode);
    }

    private void runOperation(int desiredMode) {
        if (!hasBluetoothPermission()) {
            if (Build.VERSION.SDK_INT >= 31) requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_BT);
            return;
        }
        if (!busy.compareAndSet(false, true)) return;
        refreshButton.setEnabled(false);
        setNoiseButtonsEnabled(false, -1);
        setPercent(leftValue, -1);
        setPercent(rightValue, -1);
        setPercent(caseValue, -1);
        setStatus(desiredMode >= 0 ? "正在切换环境声模式…" : "正在查找已配对的 Technics EAH-AZ 耳机…");

        worker.execute(() -> {
            String status;
            String deviceName = "";
            DeviceSnapshot snapshot = null;
            int overall = -1;
            try {
                BluetoothManager manager = (BluetoothManager)getSystemService(Context.BLUETOOTH_SERVICE);
                BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
                if (adapter == null || !adapter.isEnabled()) throw new IOException("手表蓝牙未开启");
                BluetoothDevice target = findTechnicsAzDevice(adapter.getBondedDevices());
                if (target == null) throw new IOException("未找到已配对的 Technics EAH-AZ 真无线耳机");
                deviceName = safeDeviceName(target);

                try {
                    snapshot = queryDevice(target, desiredMode);
                } catch (IOException protocolFailure) {
                    // Battery-only fallback must never disguise a failed ANC mode write as success.
                    overall = desiredMode < 0 ? readHiddenBattery(target) : -1;
                    if (overall < 0) throw protocolFailure;
                    snapshot = DeviceSnapshot.empty();
                }
                if (snapshot.left < 0 && snapshot.right < 0 && snapshot.box < 0 && overall < 0) {
                    overall = readHiddenBattery(target);
                }
                if (desiredMode >= 0) {
                    status = deviceName + " · 已收到设备回读：" + noiseModeName(snapshot.noise.mode);
                } else if (snapshot.hasComponentBattery()) {
                    status = deviceName + " · RACE 私有组件电量";
                } else if (overall >= 0) {
                    status = deviceName + " · 系统整机电量回退（非单耳）";
                } else if (snapshot.noise.supported) {
                    status = deviceName + " · 已识别环境声协议，但电量暂不可读";
                } else {
                    throw new IOException("已连接设备，但没有收到合法电量响应");
                }
            } catch (SecurityException e) {
                status = "蓝牙权限不足，请允许附近设备权限";
            } catch (Exception e) {
                status = e.getMessage() == null ? "操作失败：" + e.getClass().getSimpleName() : e.getMessage();
            }

            final DeviceSnapshot result = snapshot;
            final int systemBattery = overall;
            final String finalStatus = status;
            final String finalDeviceName = deviceName;
            runOnUiThread(() -> {
                busy.set(false);
                if (pendingRefresh && screenVisible) {
                    pendingRefresh = false;
                    refresh();
                    return;
                }
                if (result != null && (result.hasAnyNewData() || systemBattery >= 0)) {
                    applySnapshot(result, systemBattery, finalDeviceName, finalStatus);
                } else {
                    subtitle.setText(finalStatus);
                    setPercent(leftValue, -1);
                    setPercent(rightValue, -1);
                    setPercent(caseValue, -1);
                    setNoiseButtonsEnabled(false, -1);
                }
                refreshButton.setEnabled(true);
            });
        });
    }

    private void applySnapshot(DeviceSnapshot s, int overall, String deviceName, String status) {
        android.content.SharedPreferences prefs = getSharedPreferences("headset_battery", MODE_PRIVATE);
        String previousDevice = prefs.getString("device_name", "");
        boolean changedDevice = !deviceName.equals(previousDevice);
        // A newly completed read is authoritative: missing components are shown as --,
        // never silently left over from a previous read or a different model.
        setPercent(leftValue, s.left);
        setPercent(rightValue, s.right);
        setPercent(caseValue, s.box);
        if (overall >= 0 && !s.hasComponentBattery() && !s.noise.supported) {
            // Whole-device fallback is not misrepresented as an individual component.
            modeStatus.setText("系统整机电量回退：" + overall + "%");
        } else if (s.noise.supported) {
            modeStatus.setText("环境声模式：" + noiseModeName(s.noise.mode) +
                    (overall >= 0 && !s.hasComponentBattery() ? " · 整机 " + overall + "%" : ""));
        } else {
            modeStatus.setText("环境声控制：此设备 / 固件未通过协议确认");
        }
        setNoiseButtonsEnabled(s.noise.supported, s.noise.mode);
        subtitle.setText(status);
        deviceLabel.setText(deviceName);

        android.content.SharedPreferences.Editor edit = prefs.edit();
        // Save only fields confirmed by this query. Partial responses must not leave stale values.
        if (s.left < 0) edit.remove("left");
        if (s.right < 0) edit.remove("right");
        if (s.box < 0) edit.remove("case");
        if (changedDevice || (overall >= 0 && !s.hasComponentBattery())) {
            edit.remove("left").remove("right").remove("case");
        }
        edit.putString("device_name", deviceName)
                .putString("status", status)
                .putInt("overall", overall)
                .putInt("noise_mode", s.noise.supported ? s.noise.mode : -1)
                .putBoolean("noise_supported", s.noise.supported);
        if (s.left >= 0) edit.putInt("left", s.left);
        if (s.right >= 0) edit.putInt("right", s.right);
        if (s.box >= 0) edit.putInt("case", s.box);
        edit.putLong("updated", System.currentTimeMillis()).apply();

    }

    private void setNoiseButtonsEnabled(boolean enabled, int currentMode) {
        TextView[] buttons = {offButton, ancButton, ambientButton};
        for (int i = 0; i < buttons.length; i++) {
            TextView b = buttons[i];
            if (b == null) continue;
            b.setEnabled(enabled && !busy.get());
            b.setAlpha(enabled && !busy.get() ? 1f : 0.38f);
            b.setBackground(pill(enabled && i == currentMode ? Color.rgb(26, 90, 60) : Color.rgb(55, 55, 55)));
        }
    }

    private void setStatus(String status) { if (subtitle != null) subtitle.setText(status); }

    private static String noiseModeName(int mode) {
        switch (mode) {
            case 0: return "关闭";
            case 1: return "降噪";
            case 2: return "通透";
            default: return "未知";
        }
    }

    private BluetoothDevice findTechnicsAzDevice(Set<BluetoothDevice> bonded) throws IOException {
        if (bonded == null || bonded.isEmpty()) return null;
        List<BluetoothDevice> candidates = new ArrayList<>();
        for (BluetoothDevice d : bonded) {
            String name = safeDeviceName(d);
            if (EAH_AZ_NAME.matcher(name).find()) candidates.add(d);
        }
        if (candidates.isEmpty()) return null;
        Collections.sort(candidates, new Comparator<BluetoothDevice>() {
            @Override public int compare(BluetoothDevice a, BluetoothDevice b) {
                boolean a60 = safeDeviceName(a).toUpperCase(Locale.ROOT).contains("AZ60");
                boolean b60 = safeDeviceName(b).toUpperCase(Locale.ROOT).contains("AZ60");
                if (a60 != b60) return a60 ? -1 : 1;
                return safeDeviceName(a).compareToIgnoreCase(safeDeviceName(b));
            }
        });
        return candidates.get(0);
    }

    private String safeDeviceName(BluetoothDevice device) {
        try {
            String name = device.getName();
            return name == null || name.trim().isEmpty() ? "Technics EAH-AZ" : name.trim();
        } catch (SecurityException e) {
            return "Technics EAH-AZ";
        }
    }

    /**
     * Tries the documented vendor and SIG SPP endpoints. The model name is only a candidate
     * selector; component and noise capabilities are exposed only after valid RACE replies.
     */
    private DeviceSnapshot queryDevice(BluetoothDevice device, int desiredNoiseMode) throws IOException {
        IOException last = null;
        for (UUID uuid : RACE_UUIDS) {
            if (!screenVisible || Thread.currentThread().isInterrupted()) {
                throw new IOException("已离开电量页面，取消蓝牙读取");
            }
            RaceSession session = null;
            boolean modeWriteAttempted = false;
            try {
                session = openRaceSession(device, uuid);
                NoiseState noise = queryNoiseState(session);
                if (desiredNoiseMode >= 0) {
                    // Hard capability gate: never send ANC writes until a legal 0x000A GET reply.
                    if (noise == null) {
                        last = new IOException("未收到合法环境声状态响应；为安全起见不发送模式切换指令");
                        continue;
                    }
                    modeWriteAttempted = true;
                    writeNoiseMode(session, desiredNoiseMode);
                    NoiseState confirmed = queryNoiseState(session);
                    if (confirmed == null || confirmed.mode != desiredNoiseMode) {
                        // Do not retry the write on another endpoint after issuing any write.
                        throw new IOException("已发送模式指令，但没有收到目标模式的有效设备回读");
                    }
                    noise = confirmed;
                }

                DeviceSnapshot snapshot = new DeviceSnapshot();
                snapshot.noise = noise == null ? NoiseState.unsupported() : noise;
                try { snapshot.right = queryBatteryComponent(session, RIGHT_QUERY, 0x0CD6, 0, QUERY_TIMEOUT_MS); }
                catch (IOException e) { if (!snapshot.hasComponentBattery()) last = e; }
                try { snapshot.left = queryBatteryComponent(session, LEFT_QUERY, 0x0CD6, 1, QUERY_TIMEOUT_MS); }
                catch (IOException e) { if (!snapshot.hasComponentBattery()) last = e; }
                try { snapshot.box = queryBatteryComponent(session, CASE_QUERY, 0x0040, -1, CASE_TIMEOUT_MS); }
                catch (IOException e) { if (!snapshot.hasComponentBattery()) last = e; }

                if (snapshot.hasAnyNewData()) return snapshot;
                last = new IOException("RFCOMM 已连接，但未收到合法 RACE 电量 / 模式响应");
            } catch (IOException e) {
                last = e;
                // If a mode write has begun, queryDevice throws immediately and does not retry it.
                if (modeWriteAttempted) {
                    // A write may have reached the earbuds. Never replay it through another UUID.
                    throw e;
                }
            } finally {
                if (session != null) {
                    session.close();
                    activeSocket.compareAndSet(session.socket, null);
                }
            }
        }
        throw last == null ? new IOException("无法建立 Technics RACE RFCOMM 连接") : last;
    }

    private RaceSession openRaceSession(BluetoothDevice device, UUID uuid) throws IOException {
        BluetoothSocket socket = device.createRfcommSocketToServiceRecord(uuid);
        activeSocket.set(socket);
        try {
            socket.connect();
            if (!screenVisible || Thread.currentThread().isInterrupted()) {
                throw new IOException("已离开电量页面，取消蓝牙读取");
            }
            RaceSession session = new RaceSession(socket);
            session.startReader();
            return session;
        } catch (IOException | RuntimeException e) {
            try { socket.close(); } catch (IOException ignored) {}
            activeSocket.compareAndSet(socket, null);
            if (e instanceof IOException) throw (IOException)e;
            throw new IOException("建立 RFCOMM 连接失败：" + e.getClass().getSimpleName(), e);
        }
    }

    private NoiseState queryNoiseState(RaceSession session) throws IOException {
        writeCommand(session.out, NOISE_QUERY);
        long end = System.currentTimeMillis() + QUERY_TIMEOUT_MS;
        while (System.currentTimeMillis() < end) {
            byte[] frame = pollFrame(session, end);
            if (frame == null) return null;
            if (frame == READER_END) return null;
            if (!isRacFrame(frame)) continue;
            int type = frame[1] & 0xff;
            int length = unsignedShortLE(frame, 2);
            int id = unsignedShortLE(frame, 4);
            if (type != 0x5B || id != 0x000A || length < 6 || frame.length < 10) continue;
            int status = frame[6] & 0xff;
            int mode = frame[7] & 0xff;
            int ancLevel = frame[8] & 0xff;
            int ambientLevel = frame[9] & 0xff;
            if (status == 0 && mode >= 0 && mode <= 2 && ancLevel <= 100 && ambientLevel <= 100) {
                return new NoiseState(true, mode, ancLevel, ambientLevel);
            }
        }
        return null;
    }

    private void writeNoiseMode(RaceSession session, int mode) throws IOException {
        if (mode < 0 || mode > 2) throw new IOException("不支持的环境声模式");
        try {
            // Mode initialization is part of the public RACE write sequence.
            writeCommand(session.out, hex("05 5A 03 00 68 00 00"));
            if (mode == 0) {
                writeCommand(session.out, hex("05 5A 05 00 0B 00 00 64 32"));
            } else if (mode == 1) {
                writeCommand(session.out, hex("05 5A 05 00 0B 00 01 64 32"));
                writeCommand(session.out, hex("05 5A 04 00 39 00 20 02"));
            } else {
                writeCommand(session.out, hex("05 5A 04 00 22 00 00 00"));
                writeCommand(session.out, hex("05 5A 05 00 0B 00 02 64 32"));
            }
        } catch (IOException e) {
            throw new IOException("写入环境声指令失败：" + e.getMessage(), e);
        }
    }

    private void writeCommand(OutputStream out, byte[] command) throws IOException {
        try {
            Thread.sleep(WRITE_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("蓝牙操作被中断", e);
        }
        out.write(command);
        out.flush();
    }

    private int queryBatteryComponent(RaceSession session, byte[] query, int raceId,
                                      int component, long timeoutMs) throws IOException {
        writeCommand(session.out, query);
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            byte[] frame = pollFrame(session, end);
            if (frame == null) return -1;
            if (frame == READER_END) {
                IOException failure = session.readerFailure.get();
                if (failure != null) throw new IOException("蓝牙数据流关闭：" + failure.getMessage(), failure);
                return -1;
            }
            if (!isRacFrame(frame)) continue;
            int type = frame[1] & 0xff;
            int length = unsignedShortLE(frame, 2);
            int id = unsignedShortLE(frame, 4);
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
        return -1;
    }

    private byte[] pollFrame(RaceSession session, long deadlineMs) throws IOException {
        long remaining = deadlineMs - System.currentTimeMillis();
        if (remaining <= 0) return null;
        try {
            byte[] frame = session.frames.poll(remaining, TimeUnit.MILLISECONDS);
            if (frame == null && session.readerFailure.get() != null) {
                throw new IOException("蓝牙数据流读取失败：" + session.readerFailure.get().getMessage(), session.readerFailure.get());
            }
            return frame;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("读取耳机数据时被中断", e);
        }
    }

    private boolean isRacFrame(byte[] frame) {
        if (frame == null || frame.length < 6 || (frame[0] & 0xff) != 0x05) return false;
        int type = frame[1] & 0xff;
        if (type != 0x5B && type != 0x5D) return false;
        int length = unsignedShortLE(frame, 2);
        return length >= 2 && length <= 512 && frame.length >= 4 + length;
    }

    private int unsignedShortLE(byte[] bytes, int index) {
        return (bytes[index] & 0xff) | ((bytes[index + 1] & 0xff) << 8);
    }

    private byte[] readFrame(InputStream in) throws IOException {
        while (true) {
            int first = in.read();
            if (first < 0) return null;
            if (first != 0x05) continue;
            int type = in.read();
            int lo = in.read();
            int hi = in.read();
            if (type < 0 || lo < 0 || hi < 0) return null;
            int length = lo | (hi << 8);
            if (length < 2 || length > 512) {
                // Caller rejects this short header; next call resumes stream synchronisation.
                return new byte[]{0x05, (byte)type, (byte)lo, (byte)hi};
            }
            byte[] frame = new byte[4 + length];
            frame[0] = 0x05;
            frame[1] = (byte)type;
            frame[2] = (byte)lo;
            frame[3] = (byte)hi;
            for (int i = 4; i < frame.length; i++) {
                int value = in.read();
                if (value < 0) return null;
                frame[i] = (byte)value;
            }
            return frame;
        }
    }

    private int readHiddenBattery(BluetoothDevice device) {
        try {
            Method method = BluetoothDevice.class.getDeclaredMethod("getBatteryLevel");
            method.setAccessible(true);
            Object value = method.invoke(device);
            if (value instanceof Integer) {
                int percent = (Integer)value;
                if (percent >= 0 && percent <= 100) return percent;
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    private static byte[] hex(String value) {
        String[] parts = value.split("\\s+");
        byte[] bytes = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) bytes[i] = (byte)Integer.parseInt(parts[i], 16);
        return bytes;
    }

    private static final class NoiseState {
        final boolean supported;
        final int mode;
        final int ancLevel;
        final int ambientLevel;
        NoiseState(boolean supported, int mode, int ancLevel, int ambientLevel) {
            this.supported = supported; this.mode = mode;
            this.ancLevel = ancLevel; this.ambientLevel = ambientLevel;
        }
        static NoiseState unsupported() { return new NoiseState(false, -1, -1, -1); }
    }

    private static final class DeviceSnapshot {
        int left = -1, right = -1, box = -1;
        NoiseState noise = NoiseState.unsupported();
        static DeviceSnapshot empty() { return new DeviceSnapshot(); }
        boolean hasComponentBattery() { return left >= 0 || right >= 0 || box >= 0; }
        boolean hasAnyNewData() { return hasComponentBattery() || noise.supported; }
    }

    private final class RaceSession {
        final BluetoothSocket socket;
        final InputStream in;
        final OutputStream out;
        final BlockingQueue<byte[]> frames = new LinkedBlockingQueue<>(64);
        final AtomicReference<IOException> readerFailure = new AtomicReference<>();
        Thread readerThread;
        RaceSession(BluetoothSocket socket) throws IOException {
            this.socket = socket;
            this.in = socket.getInputStream();
            this.out = socket.getOutputStream();
        }
        void startReader() {
            readerThread = new Thread(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        byte[] frame = readFrame(in);
                        if (frame == null) {
                            readerFailure.compareAndSet(null, new IOException("蓝牙数据流已关闭"));
                            signalEnd();
                            return;
                        }
                        if (!frames.offer(frame)) {
                            frames.poll();
                            frames.offer(frame);
                        }
                    }
                } catch (IOException e) {
                    readerFailure.compareAndSet(null, e);
                    signalEnd();
                }
            }, "technics-race-reader");
            readerThread.setDaemon(true);
            readerThread.start();
        }
        private void signalEnd() {
            if (!frames.offer(READER_END)) {
                frames.poll();
                frames.offer(READER_END);
            }
        }
        void close() {
            try { socket.close(); } catch (IOException ignored) {}
            if (readerThread != null) readerThread.interrupt();
        }
    }

    @Override protected void onDestroy() {
        BluetoothSocket socket = activeSocket.getAndSet(null);
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) { }
        }
        worker.shutdownNow();
        super.onDestroy();
    }
}
