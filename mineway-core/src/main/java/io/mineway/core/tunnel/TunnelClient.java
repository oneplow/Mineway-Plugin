package io.mineway.core.tunnel;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.mineway.core.PlatformAdapter;
import io.mineway.core.config.TunnelConfig;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TunnelClient — หัวใจของ plugin
 *
 * ทำหน้าที่:
 * 1. เชื่อม WebSocket กับ VPS Tunnel Server
 * 2. ส่ง auth message พร้อม API key
 * 3. รับ player_connect / player_data / player_disconnect
 * 4. เปิด TCP connection ไปหา localhost:25565 แต่ละ player
 * 5. Pipe ข้อมูล 2 ทาง (player ↔ MC server)
 * 6. Auto-reconnect ถ้าหลุด
 */
public class TunnelClient {

    private final TunnelConfig config;
    private final PlatformAdapter platform;
    private final Gson gson = new Gson();

    // connId -> Pipe (TCP or UDP)
    private final Map<String, Pipe> pipes = new ConcurrentHashMap<>();

    // Buffer for data that arrives before pipe is ready (race condition fix)
    private final Map<String, java.util.List<byte[]>> pendingData = new ConcurrentHashMap<>();

    private volatile MWWebSocket ws;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);

    private ScheduledExecutorService scheduler;
    private volatile long lastMessageTime = 0;

    // ข้อมูลที่ได้จาก auth_ok
    private volatile String tunnelId;
    private volatile String hostname;
    private volatile int tcpPort;

    public TunnelClient(TunnelConfig config, PlatformAdapter platform) {
        this.config = config;
        this.platform = platform;
    }

    // ─── Start / Stop ────────────────────────────────────────────────
    public void start() {
        if (running.getAndSet(true))
            return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Mineway-Scheduler");
            t.setDaemon(true);
            return t;
        });

        // Health check task (replaces WebSocketClient's ConnectionLostChecker thread)
        scheduler.scheduleAtFixedRate(() -> {
            if (ws != null && ws.isOpen()) {
                if (System.currentTimeMillis() - lastMessageTime > 45_000) {
                    platform.logWarn("No heartbeat from server. Connection might be dead. Reconnecting...");
                    ws.close(); // Triggers onClose, which will schedule reconnect
                }
            }
        }, 15, 15, TimeUnit.SECONDS);

        connect();
    }

    public void stop() {
        running.set(false);
        if (scheduler != null)
            scheduler.shutdownNow();
        closeAllPipes();
        if (ws != null) {
            try {
                ws.closeBlocking();
            } catch (Exception ignored) {
            }
        }
        platform.logInfo("Mineway tunnel service stopped.");
    }

    // ─── Connect ─────────────────────────────────────────────────────
    private void connect() {
        if (!running.get())
            return;
        try {
            URI uri = URI.create(config.getWebSocketUri());
            ws = new MWWebSocket(uri);
            ws.connect();
        } catch (Exception e) {
            platform.logError("Failed to connect to edge network: " + e.getMessage());
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        scheduleReconnect(config.getReconnectDelaySeconds());
    }

    private void scheduleReconnect(int delaySeconds) {
        if (!running.get() || !config.isAutoReconnect())
            return;
        if (reconnecting.getAndSet(true))
            return;

        platform.logWarn("Reconnecting in " + delaySeconds + " seconds...");

        scheduler.schedule(() -> {
            reconnecting.set(false);
            connect();
        }, delaySeconds, TimeUnit.SECONDS);
    }

    // ─── Send helpers ─────────────────────────────────────────────────
    private void send(JsonObject obj) {
        if (ws != null && ws.isOpen()) {
            ws.send(gson.toJson(obj));
        }
    }

    private void sendMcData(String connId, byte[] data) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "mc_data");
        msg.addProperty("connId", connId);
        msg.addProperty("data", java.util.Base64.getEncoder().encodeToString(data));
        send(msg);
    }

    private void sendMcDisconnect(String connId) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "mc_disconnect");
        msg.addProperty("connId", connId);
        send(msg);
    }

    // ─── Handle messages from tunnel server ──────────────────────────
    private void handleMessage(String raw) {
        lastMessageTime = System.currentTimeMillis();
        JsonObject msg;
        try {
            msg = gson.fromJson(raw, JsonObject.class);
        } catch (Exception e) {
            platform.logDebug("Invalid JSON from server: " + raw);
            return;
        }

        String type = msg.get("type").getAsString();

        switch (type) {
            case "auth_ok":
                tunnelId = msg.get("tunnelId").getAsString();
                hostname = msg.get("hostname").getAsString();
                tcpPort = msg.get("tcpPort").getAsInt();
                boolean isCustomPort = msg.has("isCustomPort") && msg.get("isCustomPort").getAsBoolean();
                
                String displayAddress = isCustomPort ? hostname : (hostname + ":" + tcpPort);
                
                platform.logInfo("--------------------------------------------------");
                platform.logInfo(" Secure Tunnel Established Successfully!");
                platform.logInfo(" Node:      " + hostname);
                platform.logInfo(" Java:      " + displayAddress);
                platform.logInfo(" Bedrock:   " + displayAddress);
                platform.logInfo(" Dashboard: https://mineway.cloud/");
                platform.logInfo("--------------------------------------------------");
                break;

            case "auth_failed":
                String reason = msg.has("reason") ? msg.get("reason").getAsString() : "unknown";
                
                if (reason.equals("invalid_or_inactive") || reason.equals("key_deleted_by_web")) {
                    platform.logWarn("API Key is currently inactive. Standing by...");
                    scheduleReconnect(5);
                } else if (reason.equals("invalid_format") || reason.equals("not_found")) {
                    platform.logError("Authentication failed: " + reason);
                    platform.logError("Please verify your API key in config.yml.");
                    running.set(false);
                } else if (reason.startsWith("key_")) {
                    // Other unexpected key errors
                    platform.logError("Authentication failed: " + reason);
                    running.set(false);
                } else {
                    platform.logError("Authentication failed: " + reason);
                    scheduleReconnect();
                }
                break;

            case "tunnel_ready":
                // server พร้อมแล้ว (ส่งหลัง auth_ok ในบางกรณี)
                platform.logDebug("Tunnel ready signal received");
                break;

            case "suspended":
                platform.logWarn("⚠ Tunnel has been suspended from the Web Dashboard.");
                platform.logWarn("  All player connections have been dropped.");
                platform.logWarn("  Re-enable the API key on the dashboard to resume.");
                closeAllPipes();
                break;

            case "resumed":
                platform.logInfo("✓ Tunnel has been resumed from the Web Dashboard!");
                if (msg.has("tcpPort")) {
                    tcpPort = msg.get("tcpPort").getAsInt();
                }
                platform.logInfo("  Players can connect again.");
                break;

            case "player_connect": {
                String connId = msg.get("connId").getAsString();
                String protocol = msg.has("protocol") ? msg.get("protocol").getAsString() : "tcp";
                platform.logDebug("Player connecting: " + connId + " [" + protocol + "]");
                // Pre-create buffer BEFORE async pipe open so incoming data doesn't get lost
                pendingData.put(connId, java.util.Collections.synchronizedList(new java.util.ArrayList<>()));
                openPipe(connId, protocol);
                break;
            }

            case "player_data": {
                String connId = msg.get("connId").getAsString();
                byte[] data = java.util.Base64.getDecoder().decode(msg.get("data").getAsString());
                Pipe pipe = pipes.get(connId);
                if (pipe != null) {
                    pipe.writeToMc(data);
                } else {
                    // Pipe not ready yet — buffer data until pipe connects
                    java.util.List<byte[]> pending = pendingData.get(connId);
                    if (pending != null) {
                        pending.add(data);
                        platform.logDebug("Buffered data for " + connId + " (" + data.length + " bytes)");
                    }
                }
                break;
            }

            case "player_disconnect": {
                String connId = msg.get("connId").getAsString();
                closePipe(connId);
                break;
            }

            case "ping": {
                JsonObject pong = new JsonObject();
                pong.addProperty("type", "pong");
                send(pong);
                break;
            }

            default:
                platform.logDebug("Unknown message type: " + type);
        }
    }

    // ─── Player Pipe — TCP/UDP connection ไปหา MC server ─────────────────
    private void openPipe(String connId, String protocol) {
        platform.runAsync(() -> {
            try {
                Pipe pipe;

                if ("udp".equalsIgnoreCase(protocol)) {
                    // Bedrock UDP
                    java.net.DatagramSocket sock = new java.net.DatagramSocket();
                    pipe = new UdpPipe(connId, sock, 19132, 
                            (id, data) -> sendMcData(id, data),
                            (id) -> {
                                pipes.remove(id);
                                sendMcDisconnect(id);
                            });
                } else {
                    // Java TCP
                    Socket sock = new Socket();
                    sock.connect(new InetSocketAddress("127.0.0.1", 25565), 3000);
                    pipe = new PlayerPipe(connId, sock,
                            (id, data) -> sendMcData(id, data),
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
                    platform.logDebug("Flushing " + pending.size() + " buffered chunks for " + connId);
                    for (byte[] buffered : pending) {
                        pipe.writeToMc(buffered);
                    }
                }

                platform.logDebug("Pipe opened: " + connId + " [" + protocol + "]");
            } catch (Exception e) {
                platform.logWarn("Unable to reach local Minecraft server on port 25565: " + e.getMessage());
                sendMcDisconnect(connId);
            }
        });
    }

    private void closePipe(String connId) {
        Pipe pipe = pipes.remove(connId);
        if (pipe != null)
            pipe.close();
    }

    private void closeAllPipes() {
        for (Pipe pipe : pipes.values())
            pipe.close();
        pipes.clear();
    }

    // ─── Status ──────────────────────────────────────────────────────
    public boolean isConnected() {
        return ws != null && ws.isOpen();
    }

    public String getTunnelId() {
        return tunnelId;
    }

    public String getHostname() {
        return hostname;
    }

    public int getActivePipes() {
        return pipes.size();
    }

    // ─── Inner WebSocket class ────────────────────────────────────────
    private class MWWebSocket extends WebSocketClient {

        MWWebSocket(URI uri) {
            super(uri);
            setConnectionLostTimeout(0); // Disabled builtin checker to avoid extra thread
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            platform.logInfo("Establishing secure tunnel connection...");
            lastMessageTime = System.currentTimeMillis();
            // ส่ง auth message ทันที
            JsonObject auth = new JsonObject();
            auth.addProperty("key", config.getApiKey());
            // สำคัญ: tunnel server รับ type:"auth" ไม่ใช่ "key"
            auth.addProperty("type", "auth");
            send(gson.toJson(auth));
        }

        @Override
        public void onMessage(String message) {
            handleMessage(message);
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            platform.logWarn("Connection lost: " + reason + " (Code: " + code + ")");
            closeAllPipes();
            if (running.get() && code != 4004) { // 4004 = auth failed, ไม่ reconnect
                scheduleReconnect();
            }
        }

        @Override
        public void onError(Exception ex) {
            platform.logDebug("WebSocket error: " + ex.getMessage());
        }
    }
}
