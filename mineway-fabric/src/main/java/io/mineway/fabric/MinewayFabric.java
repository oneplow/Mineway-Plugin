package io.mineway.fabric;

import io.mineway.core.PlatformAdapter;
import io.mineway.core.config.TunnelConfig;
import io.mineway.core.tunnel.TunnelClient;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;

import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class MinewayFabric implements DedicatedServerModInitializer, PlatformAdapter {

    public static final Logger LOGGER = LoggerFactory.getLogger("mineway");
    private static MinewayFabric INSTANCE;

    private TunnelClient tunnelClient;
    private Map<String, Object> config = new HashMap<>();
    private MinecraftServer mcServer;

    @Override
    public void onInitializeServer() {
        INSTANCE = this;
        loadConfig();

        // Commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) ->
            dispatcher.register(
                literal("mineway")
                    .requires(src -> src.hasPermissionLevel(4))
                    .then(literal("status").executes(ctx -> {
                        ctx.getSource().sendFeedback(() -> Text.literal(getStatusText()), false);
                        return 1;
                    }))
                    .then(literal("reload").executes(ctx -> {
                        reloadTunnel();
                        ctx.getSource().sendFeedback(() -> Text.literal("§a[Mineway] Reload สำเร็จ"), false);
                        return 1;
                    }))
                    .then(literal("stop").executes(ctx -> {
                        if (tunnelClient != null) tunnelClient.stop();
                        ctx.getSource().sendFeedback(() -> Text.literal("§c[Mineway] หยุดแล้ว"), false);
                        return 1;
                    }))
                    .executes(ctx -> {
                        ctx.getSource().sendFeedback(() -> Text.literal(getStatusText()), false);
                        return 1;
                    })
            )
        );

        // Lifecycle
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            this.mcServer = server;
            startTunnel();
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (tunnelClient != null) tunnelClient.stop();
        });
    }

    private void startTunnel() {
        TunnelConfig tc = buildConfig();
        try { tc.validate(); }
        catch (IllegalStateException e) { LOGGER.error(e.getMessage()); return; }
        tunnelClient = new TunnelClient(tc, this);
        tunnelClient.start();
    }

    private void reloadTunnel() {
        if (tunnelClient != null) tunnelClient.stop();
        loadConfig();
        startTunnel();
    }

    private String getStatusText() {
        if (tunnelClient != null && tunnelClient.isConnected())
            return "§a[Mineway] ออนไลน์ — " + tunnelClient.getHostname()
                + " | ผู้เล่น: " + tunnelClient.getActivePipes();
        return "§c[Mineway] ออฟไลน์";
    }

    @SuppressWarnings("unchecked")
    private void loadConfig() {
        try {
            Path dir = FabricLoader.getInstance().getConfigDir().resolve("mineway");
            Files.createDirectories(dir);
            Path file = dir.resolve("config.yml");
            if (!Files.exists(file)) {
                try (InputStream in = getClass().getResourceAsStream("/mineway-config.yml")) {
                    if (in != null) Files.copy(in, file);
                }
            }
            try (InputStream in = Files.newInputStream(file)) {
                Map<String, Object> loaded = new Yaml().load(in);
                if (loaded != null) config = loaded;
            }
        } catch (IOException e) { LOGGER.error("ไม่สามารถโหลด config: " + e.getMessage()); }
    }

    private TunnelConfig buildConfig() {
        return TunnelConfig.builder()
            .apiKey(str("api_key", ""))
            .autoReconnect(bool("auto_reconnect", true))
            .reconnectDelaySeconds(num("reconnect_delay", 5))
            .debug(bool("debug", false))
            .build();
    }

    // ─── PlatformAdapter ─────────────────────────────────────────────
    @Override public void logInfo(String m)  { LOGGER.info(m); }
    @Override public void logWarn(String m)  { LOGGER.warn(m); }
    @Override public void logError(String m) { LOGGER.error(m); }
    @Override public void logDebug(String m) { if (bool("debug", false)) LOGGER.info("[DEBUG] " + m); }
    @Override public void runOnMainThread(Runnable t) { if (mcServer != null) mcServer.execute(t); else t.run(); }
    @Override public void runAsync(Runnable t) { Thread th = new Thread(t, "Mineway-Async"); th.setDaemon(true); th.start(); }
    @Override public String getPlatformName()  { return "Fabric/Quilt"; }
    @Override public String getServerVersion() { return mcServer != null ? mcServer.getVersion() : "unknown"; }

    private String  str(String k, String d)  { Object v = config.get(k); return v != null ? v.toString() : d; }
    private int     num(String k, int d)     { Object v = config.get(k); return v instanceof Number ? ((Number)v).intValue() : d; }
    private boolean bool(String k, boolean d){ Object v = config.get(k); return v instanceof Boolean ? (Boolean)v : d; }

    public static MinewayFabric getInstance() { return INSTANCE; }
}
