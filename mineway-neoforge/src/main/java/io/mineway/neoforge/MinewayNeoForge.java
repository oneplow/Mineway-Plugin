package io.mineway.neoforge;

import io.mineway.core.PlatformAdapter;
import io.mineway.core.config.TunnelConfig;
import io.mineway.core.tunnel.TunnelClient;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.file.*;
import java.util.Map;

import static net.minecraft.commands.Commands.literal;

/**
 * NeoForge adapter — ใช้สำหรับ NeoForge 1.20.2+
 * แยกจาก MinewayForge เพราะ NeoForge เปลี่ยน event system หลัง 1.20.1
 *
 * ติดตั้ง: วางไฟล์ .jar ใน /mods/
 * Config: /config/mineway/config.yml
 */
@Mod("mineway")
public class MinewayNeoForge implements PlatformAdapter {

    private static final Logger LOGGER = LogManager.getLogger("mineway");

    private TunnelClient        tunnelClient;
    private MinecraftServer     mcServer;
    private Map<String, Object> config;
    private boolean             debugMode = false;

    public MinewayNeoForge(IEventBus modEventBus) {
        // Register forge event bus listeners
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);

        loadConfig();
        LOGGER.info("Mineway NeoForge mod โหลดแล้ว");
    }

    private void onServerStarted(ServerStartedEvent event) {
        this.mcServer = event.getServer();
        startTunnel();
    }

    private void onServerStopping(ServerStoppingEvent event) {
        if (tunnelClient != null) tunnelClient.stop();
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            literal("mineway")
                .requires(src -> src.hasPermission(4))
                .executes(ctx -> { printStatus(ctx.getSource()); return 1; })
                .then(literal("status")
                    .executes(ctx -> { printStatus(ctx.getSource()); return 1; }))
                .then(literal("reload")
                    .executes(ctx -> {
                        reloadTunnel();
                        ctx.getSource().sendSuccess(
                            () -> net.minecraft.network.chat.Component.literal("§e[Mineway] §aReload สำเร็จ"), false);
                        return 1;
                    }))
                .then(literal("stop")
                    .executes(ctx -> {
                        if (tunnelClient != null) tunnelClient.stop();
                        ctx.getSource().sendSuccess(
                            () -> net.minecraft.network.chat.Component.literal("§e[Mineway] §cหยุดแล้ว"), false);
                        return 1;
                    }))
        );
    }

    private void startTunnel() {
        TunnelConfig cfg = buildConfig();
        try {
            cfg.validate();
        } catch (IllegalStateException e) {
            LOGGER.error(e.getMessage());
            return;
        }
        tunnelClient = new TunnelClient(cfg, this);
        tunnelClient.start();
    }

    private void reloadTunnel() {
        if (tunnelClient != null) tunnelClient.stop();
        loadConfig();
        TunnelConfig nc = buildConfig();
        try {
            nc.validate();
            tunnelClient = new TunnelClient(nc, this);
            tunnelClient.start();
        } catch (IllegalStateException e) {
            LOGGER.error(e.getMessage());
        }
    }

    private void printStatus(net.minecraft.commands.CommandSourceStack src) {
        String msg = (tunnelClient != null && tunnelClient.isConnected())
            ? "§e[Mineway] §aออนไลน์ — " + tunnelClient.getHostname() + " | ผู้เล่น: " + tunnelClient.getActivePipes()
            : "§e[Mineway] §cออฟไลน์";
        src.sendSuccess(() -> net.minecraft.network.chat.Component.literal(msg), false);
    }

    @SuppressWarnings("unchecked")
    private void loadConfig() {
        try {
            Path dir  = FMLPaths.CONFIGDIR.get().resolve("mineway");
            Files.createDirectories(dir);
            Path file = dir.resolve("config.yml");
            if (!Files.exists(file)) {
                try (InputStream in = getClass().getResourceAsStream("/mineway-config.yml")) {
                    if (in != null) Files.copy(in, file);
                }
            }
            try (InputStream in = Files.newInputStream(file)) {
                config = new Yaml().load(in);
                if (config == null) config = new java.util.HashMap<>();
            }
            debugMode = getBool("debug", false);
        } catch (IOException e) {
            LOGGER.error("ไม่สามารถโหลด config: " + e.getMessage());
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
    @Override public void logInfo(String msg)  { LOGGER.info(msg); }
    @Override public void logWarn(String msg)  { LOGGER.warn(msg); }
    @Override public void logError(String msg) { LOGGER.error(msg); }
    @Override public void logDebug(String msg) { if (debugMode) LOGGER.info("[DEBUG] " + msg); }

    @Override
    public void runOnMainThread(Runnable task) {
        if (mcServer != null) mcServer.execute(task);
        else task.run();
    }

    @Override
    public void runAsync(Runnable task) {
        Thread t = new Thread(task, "Mineway-Async");
        t.setDaemon(true);
        t.start();
    }

    @Override public String getPlatformName()  { return "NeoForge"; }
    @Override public String getServerVersion() { return mcServer != null ? mcServer.getServerVersion() : "unknown"; }

    private String  getString(String k, String def) { Object v = config.get(k); return v != null ? v.toString() : def; }
    private int     getInt(String k, int def)       { Object v = config.get(k); return v instanceof Number ? ((Number) v).intValue() : def; }
    private boolean getBool(String k, boolean def)  { Object v = config.get(k); return v instanceof Boolean ? (Boolean) v : def; }
}
