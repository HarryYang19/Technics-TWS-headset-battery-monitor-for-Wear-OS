package com.example.headsetbattery;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Read-only live battery reader shared by the app and Tile. This adapter only selects EAH-AZ
 * true-wireless candidates; the RACE response, not the advertised name, confirms private support.
 */
public final class TechnicsRaceReader {
    private static final byte[] END = new byte[0];
    // Tight per-command bounds leave room for the two documented RFCOMM UUID attempts
    // inside TileService's 8.5-second application deadline.
    private static final long COMPONENT_TIMEOUT_MS = 1000L;
    private static final long CASE_TIMEOUT_MS = 900L;
    private static final long COMMAND_GAP_MS = 100L;
    private static final UUID[] UUIDS = new UUID[] {
            UUID.fromString("00000000-0000-0000-0099-aabbccddeeff"),
            UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    };
    private static final byte[] RIGHT_QUERY = hex("05 5A 03 00 D6 0C 00");
    private static final byte[] LEFT_QUERY  = hex("05 5A 03 00 D6 0C 01");
    private static final byte[] CASE_QUERY  = hex("05 5A 02 00 40 00");
    private static final Pattern EAH_AZ_NAME = Pattern.compile(
            "(?i)(?:EAH[- ]?AZ\\d{2,3}[A-Z0-9]*|TECHNICS\\s+AZ\\d{2,3}[A-Z0-9]*|^AZ\\d{2,3}[A-Z0-9]*$)"
    );

    private TechnicsRaceReader() {}

    public static final class Result {
        public final String deviceName;
        public final int left;
        public final int right;
        public final int box;
        public final String status;
        public final boolean live;

        private Result(String name, int l, int r, int c, String message, boolean ok) {
            deviceName = name == null ? "" : name;
            left = l; right = r; box = c;
            status = message == null ? "" : message;
            live = ok;
        }

        public static Result failure(String message) {
            return new Result("", -1, -1, -1, message, false);
        }
    }

    public static Result readLive(Context context, AtomicReference<BluetoothSocket> activeSocket) {
        if (context == null) return Result.failure("无法访问蓝牙服务");
        if (Build.VERSION.SDK_INT >= 31 &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return Result.failure("请打开耳机电量应用并授予附近设备权限");
        }

        try {
            BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) return Result.failure("请先开启手表蓝牙");

            List<BluetoothDevice> candidates = findCandidates(adapter.getBondedDevices());
            if (candidates.isEmpty()) return Result.failure("未找到已配对的 Technics EAH-AZ 真无线耳机");

            IOException lastFailure = null;
            for (BluetoothDevice device : candidates) {
                if (Thread.currentThread().isInterrupted()) return Result.failure("实时读取已取消");
                String name = safeName(device);
                for (UUID uuid : UUIDS) {
                    if (Thread.currentThread().isInterrupted()) return Result.failure("实时读取已取消");
                    RaceSession session = null;
                    try {
                        session = openSession(device, uuid, activeSocket);
                        Snapshot values = new Snapshot();
                        try { values.right = queryComponent(session, RIGHT_QUERY, 0x0CD6, 0, COMPONENT_TIMEOUT_MS); }
                        catch (IOException e) { lastFailure = e; }
                        try { values.left = queryComponent(session, LEFT_QUERY, 0x0CD6, 1, COMPONENT_TIMEOUT_MS); }
                        catch (IOException e) { lastFailure = e; }
                        try { values.box = queryComponent(session, CASE_QUERY, 0x0040, -1, CASE_TIMEOUT_MS); }
                        catch (IOException e) { lastFailure = e; }

                        if (values.hasAny()) {
                            return new Result(name, values.left, values.right, values.box,
                                    values.hasAll() ? "实时读取 · RACE 私有组件电量" : "实时读取 · 部分组件可读", true);
                        }
                        lastFailure = new IOException("RFCOMM 已连接，但没有收到合法 RACE 电量响应");
                    } catch (IOException e) {
                        lastFailure = e;
                    } finally {
                        if (session != null) session.close();
                        BluetoothSocket socket = activeSocket.get();
                        if (socket != null && socket.isConnected()) {
                            try { socket.close(); } catch (IOException ignored) {}
                        }
                        activeSocket.set(null);
                    }
                }

            }
            String detail = lastFailure == null ? "未收到有效设备响应" : lastFailure.getMessage();
            return Result.failure("实时读取失败：" + (detail == null ? "未知蓝牙错误" : detail));
        } catch (SecurityException e) {
            return Result.failure("蓝牙权限不足，请授予附近设备权限");
        } catch (Exception e) {
            String message = e.getMessage();
            return Result.failure("实时读取失败：" + (message == null ? e.getClass().getSimpleName() : message));
        }
    }

    private static List<BluetoothDevice> findCandidates(Set<BluetoothDevice> bonded) {
        List<BluetoothDevice> candidates = new ArrayList<>();
        if (bonded == null) return candidates;
        for (BluetoothDevice device : bonded) {
            if (EAH_AZ_NAME.matcher(safeName(device)).find()) candidates.add(device);
        }
        Collections.sort(candidates, new Comparator<BluetoothDevice>() {
            @Override public int compare(BluetoothDevice a, BluetoothDevice b) {
                boolean a60 = safeName(a).toUpperCase(Locale.ROOT).contains("AZ60");
                boolean b60 = safeName(b).toUpperCase(Locale.ROOT).contains("AZ60");
                if (a60 != b60) return a60 ? -1 : 1;
                return safeName(a).compareToIgnoreCase(safeName(b));
            }
        });
        return candidates;
    }

    private static String safeName(BluetoothDevice device) {
        try {
            String value = device.getName();
            return value == null || value.trim().isEmpty() ? "Technics EAH-AZ" : value.trim();
        } catch (SecurityException e) {
            return "Technics EAH-AZ";
        }
    }

    private static RaceSession openSession(BluetoothDevice device, UUID uuid,
                                           AtomicReference<BluetoothSocket> activeSocket) throws IOException {
        BluetoothSocket socket = device.createRfcommSocketToServiceRecord(uuid);
        activeSocket.set(socket);
        try {
            socket.connect();
            return new RaceSession(socket);
        } catch (IOException | RuntimeException e) {
            try { socket.close(); } catch (IOException ignored) {}
            activeSocket.compareAndSet(socket, null);
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("RFCOMM 建连失败：" + e.getClass().getSimpleName(), e);
        }
    }

    private static int queryComponent(RaceSession session, byte[] query, int raceId,
                                      int component, long timeoutMs) throws IOException {
        writeCommand(session.out, query);
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            byte[] frame = pollFrame(session, deadline);
            if (frame == null || frame == END) return -1;
            if (!isRaceFrame(frame)) continue;
            int type = frame[1] & 0xff;
            int length = u16le(frame, 2);
            int id = u16le(frame, 4);
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

    private static void writeCommand(OutputStream out, byte[] command) throws IOException {
        try { Thread.sleep(COMMAND_GAP_MS); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("读取任务已取消", e);
        }
        out.write(command);
        out.flush();
    }

    private static byte[] pollFrame(RaceSession session, long deadline) throws IOException {
        long remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0) return null;
        try {
            byte[] frame = session.frames.poll(remaining, TimeUnit.MILLISECONDS);
            if (frame == null && session.readerFailure.get() != null) {
                throw new IOException("蓝牙数据流关闭：" + session.readerFailure.get().getMessage(), session.readerFailure.get());
            }
            return frame;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("读取任务已取消", e);
        }
    }

    private static boolean isRaceFrame(byte[] frame) {
        if (frame == null || frame.length < 6 || (frame[0] & 0xff) != 0x05) return false;
        int type = frame[1] & 0xff;
        if (type != 0x5B && type != 0x5D) return false;
        int length = u16le(frame, 2);
        return length >= 2 && length <= 512 && frame.length >= 4 + length;
    }

    private static int u16le(byte[] data, int index) {
        return (data[index] & 0xff) | ((data[index + 1] & 0xff) << 8);
    }

    private static byte[] readFrame(InputStream in) throws IOException {
        while (true) {
            int first = in.read();
            if (first < 0) return null;
            if (first != 0x05) continue;
            int type = in.read();
            int low = in.read();
            int high = in.read();
            if (type < 0 || low < 0 || high < 0) return null;
            int length = low | (high << 8);
            if (length < 2 || length > 512) return new byte[] {0x05, (byte) type, (byte) low, (byte) high};
            byte[] frame = new byte[4 + length];
            frame[0] = 0x05; frame[1] = (byte) type; frame[2] = (byte) low; frame[3] = (byte) high;
            for (int i = 4; i < frame.length; i++) {
                int value = in.read();
                if (value < 0) return null;
                frame[i] = (byte) value;
            }
            return frame;
        }
    }

    private static byte[] hex(String value) {
        String[] pieces = value.split("\\s+");
        byte[] bytes = new byte[pieces.length];
        for (int i = 0; i < pieces.length; i++) bytes[i] = (byte) Integer.parseInt(pieces[i], 16);
        return bytes;
    }

    private static final class Snapshot {
        int left = -1, right = -1, box = -1;
        boolean hasAny() { return left >= 0 || right >= 0 || box >= 0; }
        boolean hasAll() { return left >= 0 && right >= 0 && box >= 0; }
    }

    private static final class RaceSession {
        final BluetoothSocket socket;
        final InputStream in;
        final OutputStream out;
        final BlockingQueue<byte[]> frames = new LinkedBlockingQueue<>(64);
        final AtomicReference<IOException> readerFailure = new AtomicReference<>();
        Thread readerThread;

        RaceSession(BluetoothSocket socket) throws IOException {
            this.socket = socket;
            in = socket.getInputStream();
            out = socket.getOutputStream();
            startReader();
        }

        private void startReader() {
            readerThread = new Thread(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        byte[] frame = readFrame(in);
                        if (frame == null) {
                            readerFailure.compareAndSet(null, new IOException("蓝牙数据流已关闭"));
                            signalEnd();
                            return;
                        }
                        if (!frames.offer(frame)) { frames.poll(); frames.offer(frame); }
                    }
                } catch (IOException e) {
                    readerFailure.compareAndSet(null, e);
                    signalEnd();
                }
            }, "technics-tile-race-reader");
            readerThread.setDaemon(true);
            readerThread.start();
        }

        private void signalEnd() {
            if (!frames.offer(END)) { frames.poll(); frames.offer(END); }
        }

        void close() {
            try { socket.close(); } catch (IOException ignored) {}
            if (readerThread != null) readerThread.interrupt();
        }
    }
}
