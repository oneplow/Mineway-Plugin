package io.mineway.proxy.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import io.mineway.core.PlatformAdapter;
import io.mineway.core.config.TunnelConfig;
import io.mineway.core.tunnel.TunnelClient;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.file.*;
import java.util.Map;

@Plugin(
    id          = "mineway",
    name        = "Mineway",
    version     = "1.0.0",
    description = "Mineway — expose your Minecraft server via secure tunnel",
    url         = "https://mineway.cloud",
    authors     = {"Mineway"}
)
public class MinewayVelocity implements PlatformAdapter {

    private final ProxyServer proxy;
    private final Logger      logger;
    private final Path        dataDir;

    private TunnelClient tunnelClient;
    private Map<String, Object> config;

    @Inject
    public MinewayVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDir) {
        this.proxy   = proxy;
        this.logger  = logger;
        this.dataDir = dataDir;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        loadConfig();

        TunnelConfig tunnelConfig = buildConfig();
        try {
            tunnelConfig.validate();
        } catch (IllegalStateException e) {
            logger.error(e.getMessage());
            return;
        }

        tunnelClient = new TunnelClient(tunnelConfig, this);
        tunnelClient.start();

        // Register command
        proxy.getCommandManager().register(
            proxy.getCommandManager().metaBuilder("mineway").aliases("mct").build(),
            new VelocityCommand(this)
        );
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (tunnelClient != null) tunnelClient.stop();
    }

    @SuppressWarnings("unchecked")
    private void loadConfig() {
        try {
            Files.createDirectories(dataDir);
            Path configFile = dataDir.resolve("config.yml");
            if (!Files.exists(configFile)) {
                try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
                    if (in != null) Files.copy(in, configFile);
                }
            }
            try (InputStream in = Files.newInputStream(configFile)) {
                config = new Yaml().load(in);
            }
        } catch (IOException e) {
            logger.error("ไม่สามารถโหลด config.yml: " + e.getMessage());
            config = new java.util.HashMap<>();
        }
    }

    private TunnelConfig buildConfig() {
        return TunnelConfig.builder()
            .apiKey(getString("api_key", ""))
            .autoReconnect(getBool("auto_reconnect", true))
            .reconnectDelaySeconds(getInt("reconnect_delay", 5))
            .debug(getBool("debug", false))
            .build();
    }

    // ─── PlatformAdapter ──────────────────────────────────────────────
    @Override public void logInfo(String msg)  { logger.info(msg); }
    @Override public void logWarn(String msg)  { logger.warn(msg); }
    @Override public void logError(String msg) { logger.error(msg); }
    @Override public void logDebug(String msg) { if (getBool("debug", false)) logger.info("[DEBUG] " + msg); }
    @Override public void runOnMainThread(Runnable t) { proxy.getScheduler().buildTask(this, t).schedule(); }
    @Override public void runAsync(Runnable t)        { proxy.getScheduler().buildTask(this, t).schedule(); }
    @Override public String getPlatformName()  { return "Velocity"; }
    @Override public String getServerVersion() { return proxy.getVersion().getVersion(); }

    // Getters for VelocityCommand
    public TunnelClient getTunnelClient() { return tunnelClient; }
    public ProxyServer  getProxy()        { return proxy; }
    public void         reloadAndRestart() {
        if (tunnelClient != null) tunnelClient.stop();
        loadConfig();
        TunnelConfig nc = buildConfig();
        nc.validate();
        tunnelClient = new TunnelClient(nc, this);
        tunnelClient.start();
    }

    // ─── Config helpers ───────────────────────────────────────────────
    private String  getString(String k, String def)  { Object v = config.get(k); return v != null ? v.toString() : def; }
    private int     getInt(String k, int def)        { Object v = config.get(k); return v instanceof Number ? ((Number) v).intValue() : def; }
    private boolean getBool(String k, boolean def)   { Object v = config.get(k); return v instanceof Boolean ? (Boolean) v : def; }
}
