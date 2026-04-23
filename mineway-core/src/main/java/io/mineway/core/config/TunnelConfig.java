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
    private final int    targetTcpPort;
    private final int    targetUdpPort;

    private TunnelConfig(Builder b) {
        this.apiKey                = b.apiKey;
        this.autoReconnect         = b.autoReconnect;
        this.reconnectDelaySeconds = b.reconnectDelaySeconds;
        this.debug                 = b.debug;
        this.targetTcpPort         = b.targetTcpPort;
        this.targetUdpPort         = b.targetUdpPort;

        // Decode self-contained Host and Port from API Key
        // NEW FORMAT: mw_live_<base64url(host:tcpPort)>.<hmac_signature>
        // OLD FORMAT: mw_live_<base64url(host:tcpPort:httpPort|secret)>
        String decodedHost = b.serverHost; // Fallback default
        int decodedPort = b.serverPort;    // Fallback default

        if (this.apiKey != null && (this.apiKey.startsWith("mw_live_") || this.apiKey.startsWith("mw_test_"))) {
            try {
                String keyPrefix = this.apiKey.startsWith("mw_live_") ? "mw_live_" : "mw_test_";
                String rest = this.apiKey.substring(keyPrefix.length());

                // Check for new format (contains '.' separator between payload and signature)
                String encodedPayload;
                int dotIndex = rest.indexOf('.');
                if (dotIndex != -1) {
                    // New format — payload is before the dot, signature after (we ignore signature)
                    encodedPayload = rest.substring(0, dotIndex);
                } else {
                    // Old format — entire rest is the encoded payload
                    encodedPayload = rest;
                }

                byte[] decodedBytes = Base64.getUrlDecoder().decode(encodedPayload);
                String payload = new String(decodedBytes, StandardCharsets.UTF_8);

                // New format: "host:port" | Old format: "host:port:httpPort|secret"
                String hostPortPart = payload.contains("|") ? payload.split("\\|")[0] : payload;
                String[] hostPorts = hostPortPart.split(":");
                if (hostPorts.length >= 2) {
                    decodedHost = hostPorts[0];
                    decodedPort = Integer.parseInt(hostPorts[1]); // TCP port
                }
            } catch (Exception e) {
                // Ignore decoding error — fallback to builder defaults
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
    public int     getTargetTcpPort()         { return targetTcpPort; }
    public int     getTargetUdpPort()         { return targetUdpPort; }

    // Raw TCP — no WebSocket URI needed anymore.
    // Plugin connects directly via socket to serverHost:serverPort

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
        private int     targetTcpPort         = 25565; // Default Java port
        private int     targetUdpPort         = 19132; // Default Bedrock port

        public Builder apiKey(String v)                { this.apiKey = v; return this; }
        public Builder serverHost(String v)            { this.serverHost = v; return this; }
        public Builder serverPort(int v)               { this.serverPort = v; return this; }
        public Builder autoReconnect(boolean v)        { this.autoReconnect = v; return this; }
        public Builder reconnectDelaySeconds(int v)    { this.reconnectDelaySeconds = v; return this; }
        public Builder debug(boolean v)                { this.debug = v; return this; }
        public Builder targetTcpPort(int v)            { this.targetTcpPort = v; return this; }
        public Builder targetUdpPort(int v)            { this.targetUdpPort = v; return this; }

        public TunnelConfig build() { return new TunnelConfig(this); }
    }
}
