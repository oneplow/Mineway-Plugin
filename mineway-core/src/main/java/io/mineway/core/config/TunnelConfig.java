package io.mineway.core.config;

import java.util.Base64;
import java.nio.charset.StandardCharsets;

/**
 * Config ที่ทุก platform ส่งมาให้ Core
 * Platform adapter แต่ละตัวอ่าน config ของตัวเอง แล้วสร้าง TunnelConfig
 */
public class TunnelConfig {

    private final String apiKey;
    private final String serverHost;
    private final int    serverPort;
    private final boolean autoReconnect;
    private final int    reconnectDelaySeconds;
    private final boolean debug;

    private TunnelConfig(Builder b) {
        this.apiKey                = b.apiKey;
        this.autoReconnect         = b.autoReconnect;
        this.reconnectDelaySeconds = b.reconnectDelaySeconds;
        this.debug                 = b.debug;

        // Decode self-contained Host and Port from API Key
        String decodedHost = b.serverHost; // Fallback default
        int decodedPort = b.serverPort;    // Fallback default

        if (this.apiKey != null && (this.apiKey.startsWith("mw_live_") || this.apiKey.startsWith("mw_test_"))) {
            try {
                String prefix = this.apiKey.startsWith("mw_live_") ? "mw_live_" : "mw_test_";
                String encodedPayload = this.apiKey.substring(prefix.length());
                byte[] decodedBytes = Base64.getUrlDecoder().decode(encodedPayload);
                String payload = new String(decodedBytes, StandardCharsets.UTF_8);
                
                // Format: <host>:<port>|<secret>
                String[] parts = payload.split("\\|");
                if (parts.length >= 2) {
                    String[] hostPort = parts[0].split(":");
                    if (hostPort.length >= 2) {
                        decodedHost = hostPort[0];
                        decodedPort = Integer.parseInt(hostPort[1]);
                    }
                }
            } catch (Exception e) {
                // Ignore decoding error. This means it's an old key format or corrupted.
                // It will fallback to the defaults.
            }
        }

        this.serverHost = decodedHost;
        this.serverPort = decodedPort;
    }

    public String  getApiKey()                { return apiKey; }
    public String  getServerHost()            { return serverHost; }
    public int     getServerPort()            { return serverPort; }
    public boolean isAutoReconnect()          { return autoReconnect; }
    public int     getReconnectDelaySeconds() { return reconnectDelaySeconds; }
    public boolean isDebug()                  { return debug; }

    public String getWebSocketUri() {
        return (shouldUseSecureWebSocket() ? "wss://" : "ws://") + serverHost + ":" + serverPort;
    }

    private boolean shouldUseSecureWebSocket() {
        String normalizedHost = serverHost == null ? "" : serverHost.trim().toLowerCase();
        return !(normalizedHost.equals("localhost")
            || normalizedHost.equals("127.0.0.1")
            || normalizedHost.equals("0.0.0.0"));
    }

    /** Validate — throw ถ้าข้อมูลไม่ครบ */
    public void validate() {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("[Mineway] api_key ยังไม่ได้ตั้งค่าใน config.yml!");
        }
        if (!apiKey.startsWith("mw_live_") && !apiKey.startsWith("mw_test_")) {
            throw new IllegalStateException("[Mineway] api_key ไม่ถูกต้อง ต้องขึ้นต้นด้วย mw_live_ หรือ mw_test_");
        }
        if (serverHost == null || serverHost.isEmpty()) {
            throw new IllegalStateException("[Mineway] server_host ยังไม่ได้ตั้งค่า");
        }
    }

    // ─── Builder ──────────────────────────────────────────────────────
    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String  apiKey                = "";
        private String  serverHost            = "tunnel.mineway.cloud";
        private int     serverPort            = 8765;
        private boolean autoReconnect         = true;
        private int     reconnectDelaySeconds = 5;
        private boolean debug                 = false;

        public Builder apiKey(String v)                { this.apiKey = v; return this; }
        public Builder serverHost(String v)            { this.serverHost = v; return this; }
        public Builder serverPort(int v)               { this.serverPort = v; return this; }
        public Builder autoReconnect(boolean v)        { this.autoReconnect = v; return this; }
        public Builder reconnectDelaySeconds(int v)    { this.reconnectDelaySeconds = v; return this; }
        public Builder debug(boolean v)                { this.debug = v; return this; }

        public TunnelConfig build() { return new TunnelConfig(this); }
    }
}
