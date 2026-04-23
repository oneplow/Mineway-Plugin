package io.mineway.core.tunnel;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.mineway.core.PlatformAdapter;
import io.mineway.core.config.TunnelConfig;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TunnelClient — หัวใจของ plugin (Raw TCP Binary Protocol)
 *
 * Binary Frame: [Type(1B)] [Length(4B BE)] [Payload(N bytes)]
 *
 * ทำหน้าที่:
 * 1. เชื่อม Raw TCP กับ VPS Tunnel Server
 * 2. ส่ง AUTH frame พร้อม API key
 * 3. รับ PLAYER_CONNECT / PLAYER_DATA / PLAYER_DISCONNECT (binary)
 * 4. เปิด TCP connection ไปหา localhost:25565 แต่ละ player
 * 5. Pipe ข้อมูล 2 ทาง (player ↔ MC server)
 * 6. Auto-reconnect พร้อม exponential backoff ถ้าหลุด
 */
public class TunnelClient {

    // ─── Frame Types (ต้องตรงกับ Node.js server) ─────────────────────
    private static final byte FRAME_AUTH             = 0x01;
    private static final byte FRAME_AUTH_OK          = 0x02;
    private static final byte FRAME_AUTH_FAIL        = 0x03;
    private static final byte FRAME_PLAYER_CONNECT   = 0x10;
    private static final byte FRAME_PLAYER_DATA      = 0x11;
    private static final byte FRAME_PLAYER_DISCONNECT= 0x12;
    private static final byte FRAME_PING             = 0x20;
    private static final byte FRAME_PONG             = 0x21;
    private static final byte FRAME_TUNNEL_READY     = 0x30;
    private static final byte FRAME_SUSPENDED        = 0x31;
    private static final byte FRAME_RESUMED          = 0x32;

    // ─── Exponential Backoff Config ───────────────────────────────────
    /** delay เริ่มต้น (วิ) */
    private static final int  BACKOFF_BASE_SECONDS = 5;
    /** delay สูงสุด (วิ) — ไม่ spam log มากกว่านี้ */
    private static final int  BACKOFF_MAX_SECONDS  = 300;
    /** ตัวคูณแต่ละรอบ */
    private static final double BACKOFF_MULTIPLIER = 2.0;

    private TunnelConfig config;
    private final PlatformAdapter platform;
    private final Gson gson = new Gson();

    // connId -> Pipe (TCP or UDP)
    private final Map<String, Pipe> pipes = new ConcurrentHashMap<>();

    // Buffer for data that arrives before pipe is ready (race condition fix)
    private final Map<String, java.util.List<byte[]>> pendingData = new ConcurrentHashMap<>();

    private volatile Socket       socket;
    private volatile OutputStream outputStream;
    private final    Object       writeLock    = new Object();
    private final AtomicBoolean   running      = new AtomicBoolean(false);
    private final AtomicBoolean   reconnecting = new AtomicBoolean(false);

    /** นับจำนวนครั้งที่ reconnect ติดต่อกัน — reset เป็น 0 เมื่อ auth สำเร็จ */
    private final AtomicInteger reconnectAttempt = new AtomicInteger(0);

    /** ป้องกัน log ซ้ำซ้อนขณะรอ backoff */
    private volatile boolean suppressRetryLog = false;

    private ScheduledExecutorService scheduler;
    private volatile long lastMessageTime = 0;

    // ข้อมูลที่ได้จาก auth_ok
    private volatile String tunnelId;
    private volatile String hostname;
    private volatile int    tcpPort;

    public TunnelClient(TunnelConfig config, PlatformAdapter platform) {
        this.config   = config;
        this.platform = platform;
    }

    // ─── Start / Stop ────────────────────────────────────────────────
    public void start() {
        if (running.getAndSet(true)) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Mineway-Scheduler");
            t.setDaemon(true);
            return t;
        });

        // Health check task — ตรวจ heartbeat ทุก 15 วิ
        scheduler.scheduleAtFixedRate(() -> {
            if (socket != null && !socket.isClosed()) {
                if (System.currentTimeMillis() - lastMessageTime > 45_000) {
                    platform.logWarn("[Mineway] No heartbeat received for 45s. Reconnecting...");
                    closeSocket();
                }
            }
        }, 15, 15, TimeUnit.SECONDS);

        connect();
    }

    public void stop() {
        running.set(false);
        if (scheduler != null) scheduler.shutdownNow();
        closeAllPipes();
        closeSocket();
        platform.logInfo("[Mineway] Tunnel service stopped.");
    }

    // ─── Connect ─────────────────────────────────────────────────────
    private void connect() {
        if (!running.get()) return;
        platform.runAsync(() -> {
            try {
                Socket sock = new Socket();
                sock.setTcpNoDelay(true);
                sock.setKeepAlive(true);
                sock.setSoTimeout(0);
                sock.connect(new InetSocketAddress(config.getServerHost(), config.getServerPort()), 10_000);

                socket       = sock;
                outputStream = new BufferedOutputStream(sock.getOutputStream(), 64 * 1024);
                lastMessageTime = System.currentTimeMillis();

                // เชื่อมสำเร็จ — reset suppress flag เพื่อให้ log ได้ปกติรอบหน้า
                suppressRetryLog = false;

                platform.logInfo("[Mineway] Establishing tunnel connection...");

                // Send AUTH frame
                JsonObject auth = new JsonObject();
                auth.addProperty("key", config.getApiKey());
                sendFrame(FRAME_AUTH, auth.toString().getBytes(StandardCharsets.UTF_8));

                // Start read loop (blocking)
                readLoop();

            } catch (Exception e) {
                int attempt = reconnectAttempt.incrementAndGet();
                int delay   = calcBackoffDelay(attempt);

                // log เฉพาะครั้งแรก หรือทุก 5 รอบ เพื่อไม่ spam
                if (attempt == 1 || attempt % 5 == 0 || !suppressRetryLog) {
                    platform.logWarn("[Mineway] Cannot connect to tunnel server: " + e.getMessage());
                    if (attempt > 1) {
                        platform.logWarn("[Mineway] Retry attempt #" + attempt
                                + " — next retry in " + delay + "s"
                                + " (max interval: " + BACKOFF_MAX_SECONDS + "s)");
                    }
                    suppressRetryLog = true;
                }

                scheduleReconnect(delay);
            }
        });
    }

    private void closeSocket() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (Exception ignored) {}
        socket       = null;
        outputStream = null;
    }

    // ─── Exponential Backoff ──────────────────────────────────────────

    /**
     * คำนวณ delay ตามจำนวนครั้งที่ fail ติดต่อกัน
     * attempt=1 → 5s, attempt=2 → 10s, attempt=3 → 20s, ... จนสูงสุด 300s
     */
    private int calcBackoffDelay(int attempt) {
        double delay = BACKOFF_BASE_SECONDS * Math.pow(BACKOFF_MULTIPLIER, attempt - 1);
        return (int) Math.min(delay, BACKOFF_MAX_SECONDS);
    }

    private void scheduleReconnect() {
        int attempt = reconnectAttempt.get();
        scheduleReconnect(calcBackoffDelay(Math.max(attempt, 1)));
    }

    private void scheduleReconnect(int delaySeconds) {
        if (!running.get() || !config.isAutoReconnect()) return;
        if (reconnecting.getAndSet(true)) return;

        scheduler.schedule(() -> {
            try {
                TunnelConfig latestConfig = platform.getLatestConfig();
                if (latestConfig != null) {
                    this.config = latestConfig;
                }
            } catch (Exception e) {
                platform.logWarn("[Mineway] Failed to reload config before reconnect: " + e.getMessage());
            }

            reconnecting.set(false);
            connect();
        }, delaySeconds, TimeUnit.SECONDS);
    }

    // ─── Binary Frame I/O ────────────────────────────────────────────

    /**
     * Build and send a binary frame: [type(1)] [length(4 BE)] [payload(N)]
     * Thread-safe via writeLock.
     */
    private void sendFrame(byte type, byte[] payload) {
        synchronized (writeLock) {
            try {
                OutputStream out = outputStream;
                if (out == null) return;
                int payloadLen = (payload != null) ? payload.length : 0;
                byte[] header  = new byte[5];
                header[0] = type;
                header[1] = (byte) ((payloadLen >>> 24) & 0xFF);
                header[2] = (byte) ((payloadLen >>> 16) & 0xFF);
                header[3] = (byte) ((payloadLen >>> 8)  & 0xFF);
                header[4] = (byte) (payloadLen & 0xFF);
                out.write(header);
                if (payload != null && payloadLen > 0) {
                    out.write(payload);
                }
                out.flush();
            } catch (IOException e) {
                platform.logDebug("[Mineway] Write error: " + e.getMessage());
            }
        }
    }

    private void sendFrame(byte type) {
        sendFrame(type, null);
    }

    /**
     * Optimized send for PLAYER_DATA — เขียน frame header (5B) + connId (8B) + data
     * ใน 2 write calls แทนการ allocate payload buffer ใหม่ทุกครั้ง
     * Thread-safe via writeLock.
     */
    private void sendMcDataDirect(byte[] connIdBytes, byte[] data) {
        synchronized (writeLock) {
            try {
                OutputStream out = outputStream;
                if (out == null) return;
                int payloadLen = 8 + data.length;
                // Frame header (5B) + connId (8B) รวมกันใน array เดียว = 13B
                byte[] header = new byte[13];
                header[0] = FRAME_PLAYER_DATA;
                header[1] = (byte) ((payloadLen >>> 24) & 0xFF);
                header[2] = (byte) ((payloadLen >>> 16) & 0xFF);
                header[3] = (byte) ((payloadLen >>> 8)  & 0xFF);
                header[4] = (byte) (payloadLen & 0xFF);
                System.arraycopy(connIdBytes, 0, header, 5, 8);
                out.write(header);
                out.write(data);
                out.flush();
            } catch (IOException e) {
                platform.logDebug("[Mineway] Write error: " + e.getMessage());
            }
        }
    }

    /**
     * Blocking read loop — reads binary frames until disconnected.
     */
    private void readLoop() {
        try {
            InputStream   in  = socket.getInputStream();
            DataInputStream dis = new DataInputStream(new BufferedInputStream(in, 64 * 1024));
            byte[] headerBuf  = new byte[5];

            while (running.get() && socket != null && !socket.isClosed()) {
                dis.readFully(headerBuf);
                byte type      = headerBuf[0];
                int payloadLen = ((headerBuf[1] & 0xFF) << 24)
                               | ((headerBuf[2] & 0xFF) << 16)
                               | ((headerBuf[3] & 0xFF) << 8)
                               |  (headerBuf[4] & 0xFF);

                // Safety: max 2MB
                if (payloadLen > 2 * 1024 * 1024) {
                    platform.logWarn("[Mineway] Oversized frame from server (" + payloadLen + " bytes). Disconnecting.");
                    break;
                }

                byte[] payload = new byte[payloadLen];
                if (payloadLen > 0) {
                    dis.readFully(payload);
                }

                lastMessageTime = System.currentTimeMillis();
                handleFrame(type, payload);
            }
        } catch (EOFException e) {
            platform.logWarn("[Mineway] Connection lost (server closed connection).");
        } catch (IOException e) {
            if (running.get()) {
                platform.logWarn("[Mineway] Connection lost: " + e.getMessage());
            }
        } finally {
            closeAllPipes();
            closeSocket();
            if (running.get()) {
                int attempt = reconnectAttempt.incrementAndGet();
                int delay   = calcBackoffDelay(attempt);
                platform.logWarn("[Mineway] Reconnecting in " + delay + "s... (attempt #" + attempt + ")");
                scheduleReconnect(delay);
            }
        }
    }

    // ─── Handle frames from tunnel server ────────────────────────────
    private void handleFrame(byte type, byte[] payload) {
        switch (type) {

            case FRAME_AUTH_OK: {
                // Auth สำเร็จ — reset backoff ทั้งหมด
                reconnectAttempt.set(0);
                suppressRetryLog = false;

                String     json = new String(payload, StandardCharsets.UTF_8);
                JsonObject msg  = gson.fromJson(json, JsonObject.class);

                tunnelId = msg.get("tunnelId").getAsString();
                hostname = msg.get("hostname").getAsString();
                tcpPort  = msg.get("tcpPort").getAsInt();
                boolean isCustomPort = msg.has("isCustomPort") && msg.get("isCustomPort").getAsBoolean();
                String  nodeName     = msg.has("nodeName") ? msg.get("nodeName").getAsString() : "Unknown Node";

                String displayAddress = isCustomPort ? hostname : (hostname + ":" + tcpPort);

                platform.logInfo("--------------------------------------------------");
                platform.logInfo(" Secure Tunnel Established Successfully!");
                platform.logInfo(" Node:      " + nodeName);
                platform.logInfo(" Java:      " + displayAddress);
                platform.logInfo(" Bedrock:   " + displayAddress);
                platform.logInfo(" Dashboard: https://mineway.cloud/");
                platform.logInfo("--------------------------------------------------");
                break;
            }

            case FRAME_AUTH_FAIL: {
                String     json   = new String(payload, StandardCharsets.UTF_8);
                JsonObject msg    = gson.fromJson(json, JsonObject.class);
                String     reason = msg.has("reason") ? msg.get("reason").getAsString() : "unknown";

                if (reason.equals("invalid_or_inactive") || reason.equals("key_deleted_by_web")) {
                    // API key ถูก suspend — backoff ปกติ แต่ log แค่ครั้งแรก
                    int attempt = reconnectAttempt.incrementAndGet();
                    int delay   = calcBackoffDelay(attempt);
                    if (attempt == 1 || !suppressRetryLog) {
                        platform.logWarn("[Mineway] API Key is currently inactive. Standing by...");
                        platform.logWarn("[Mineway] Will retry every " + delay + "s (up to " + BACKOFF_MAX_SECONDS + "s max).");
                        suppressRetryLog = true;
                    }
                    scheduleReconnect(delay);

                } else if (reason.equals("invalid_format") || reason.equals("not_found")) {
                    // Key ผิด format — ไม่ต้อง retry เลย
                    platform.logError("[Mineway] Authentication failed: " + reason);
                    platform.logError("[Mineway] Please verify your API key in config.yml.");
                    running.set(false);

                } else if (reason.startsWith("key_")) {
                    // Key ถูก revoke — ไม่ต้อง retry
                    platform.logError("[Mineway] Authentication failed: " + reason);
                    running.set(false);

                } else {
                    // กรณีอื่น — backoff ปกติ
                    int attempt = reconnectAttempt.incrementAndGet();
                    int delay   = calcBackoffDelay(attempt);
                    platform.logError("[Mineway] Authentication failed: " + reason);
                    scheduleReconnect(delay);
                }
                break;
            }

            case FRAME_TUNNEL_READY:
                platform.logDebug("[Mineway] Tunnel ready signal received");
                break;

            case FRAME_SUSPENDED:
                platform.logWarn("[Mineway] ⚠ Tunnel has been suspended from the Web Dashboard.");
                platform.logWarn("[Mineway]   All player connections have been dropped.");
                platform.logWarn("[Mineway]   Re-enable the API key on the dashboard to resume.");
                closeAllPipes();
                break;

            case FRAME_RESUMED: {
                platform.logInfo("[Mineway] ✓ Tunnel has been resumed from the Web Dashboard!");
                if (payload.length > 0) {
                    String     json = new String(payload, StandardCharsets.UTF_8);
                    JsonObject msg  = gson.fromJson(json, JsonObject.class);
                    if (msg.has("tcpPort")) {
                        tcpPort = msg.get("tcpPort").getAsInt();
                    }
                }
                platform.logInfo("[Mineway]   Players can connect again.");
                break;
            }

            case FRAME_PLAYER_CONNECT: {
                if (payload.length < 9) return;
                String connId   = new String(payload, 0, 8, StandardCharsets.US_ASCII);
                int    protocol = payload[8] & 0xFF;
                String protoStr = (protocol == 1) ? "udp" : "tcp";
                platform.logDebug("[Mineway] Player connecting: " + connId + " [" + protoStr + "]");
                pendingData.put(connId, java.util.Collections.synchronizedList(new java.util.ArrayList<>()));
                openPipe(connId, protoStr);
                break;
            }

            case FRAME_PLAYER_DATA: {
                if (payload.length < 8) return;
                String connId = new String(payload, 0, 8, StandardCharsets.US_ASCII);
                byte[] data   = new byte[payload.length - 8];
                System.arraycopy(payload, 8, data, 0, data.length);

                Pipe pipe = pipes.get(connId);
                if (pipe != null) {
                    pipe.writeToMc(data);
                } else {
                    java.util.List<byte[]> pending = pendingData.get(connId);
                    if (pending != null) {
                        pending.add(data);
                        platform.logDebug("[Mineway] Buffered data for " + connId + " (" + data.length + " bytes)");
                    }
                }
                break;
            }

            case FRAME_PLAYER_DISCONNECT: {
                if (payload.length < 8) return;
                String connId = new String(payload, 0, 8, StandardCharsets.US_ASCII);
                closePipe(connId);
                break;
            }

            case FRAME_PING:
                sendFrame(FRAME_PONG);
                break;

            default:
                platform.logDebug("[Mineway] Unknown frame type: 0x" + String.format("%02X", type));
        }
    }

    // ─── Send helpers ────────────────────────────────────────────────

    private void sendMcDisconnect(String connId) {
        byte[] payload = connId.getBytes(StandardCharsets.US_ASCII);
        sendFrame(FRAME_PLAYER_DISCONNECT, payload);
    }

    // ─── Player Pipe — TCP/UDP connection ไปหา MC server ─────────────
    private void openPipe(String connId, String protocol) {
        platform.runAsync(() -> {
            try {
                Pipe pipe;

                // Pre-cache connId bytes ครั้งเดียว ใช้ซ้ำใน sendMcDataDirect
                byte[] connIdBytes = connId.getBytes(StandardCharsets.US_ASCII);

                if ("udp".equalsIgnoreCase(protocol)) {
                    java.net.DatagramSocket sock = new java.net.DatagramSocket();
                    pipe = new UdpPipe(connId, sock, config.getTargetUdpPort(),
                            (id, data) -> sendMcDataDirect(connIdBytes, data),
                            (id) -> {
                                pipes.remove(id);
                                sendMcDisconnect(id);
                            });
                } else {
                    Socket sock = new Socket();
                    sock.setTcpNoDelay(true);
                    sock.connect(new InetSocketAddress("127.0.0.1", config.getTargetTcpPort()), 3000);
                    pipe = new PlayerPipe(connId, sock,
                            (id, data) -> sendMcDataDirect(connIdBytes, data),
                            (id) -> {
                                pipes.remove(id);
                                sendMcDisconnect(id);
                            });
                }

                pipes.put(connId, pipe);
                pipe.start();

                // Flush any data that arrived while pipe was connecting
                java.util.List<byte[]> pending = pendingData.remove(connId);
                if (pending != null && !pending.isEmpty()) {
                    platform.logDebug("[Mineway] Flushing " + pending.size() + " buffered chunks for " + connId);
                    for (byte[] buffered : pending) {
                        pipe.writeToMc(buffered);
                    }
                }

                platform.logDebug("[Mineway] Pipe opened: " + connId + " [" + protocol + "]");

            } catch (Exception e) {
                int errorPort = "udp".equalsIgnoreCase(protocol)
                        ? config.getTargetUdpPort()
                        : config.getTargetTcpPort();
                platform.logWarn("[Mineway] Unable to reach local target server on port " + errorPort + ": " + e.getMessage());
                sendMcDisconnect(connId);
            }
        });
    }

    private void closePipe(String connId) {
        Pipe pipe = pipes.remove(connId);
        if (pipe != null) pipe.close();
    }

    private void closeAllPipes() {
        for (Pipe pipe : pipes.values()) pipe.close();
        pipes.clear();
    }

    // ─── Status ──────────────────────────────────────────────────────
    public boolean isConnected()  { return socket != null && !socket.isClosed(); }
    public String  getTunnelId()  { return tunnelId; }
    public String  getHostname()  { return hostname; }
    public int     getActivePipes() { return pipes.size(); }
}