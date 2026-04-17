package io.mineway.forge;

import io.mineway.core.PlatformAdapter;
import io.mineway.core.config.TunnelConfig;
import io.mineway.core.tunnel.TunnelClient;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;

@Mod("mineway")
public class MinewayForge implements PlatformAdapter {

    private static final Logger LOGGER = LogManager.getLogger("mineway");
    private static MinewayForge INSTANCE;

    private TunnelClient tunnelClient;
    private Map<String, Object> config = new HashMap<>();
    private MinecraftServer mcServer;

    public MinewayForge() {
        INSTANCE = this;
        loadConfig();
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        this.mcServer = event.getServer();
        startTunnel();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (tunnelClient != null) tunnelClient.stop();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("mineway")
                .requires(src -> src.hasPermission(4))
                .then(Commands.literal("status").executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(getStatusText()), false);
                    return 1;
                }))
                .then(Commands.literal("reload").executes(ctx -> {
                    reloadTunnel();
                    ctx.getSource().sendSuccess(() -> Component.literal("§a[Mineway] Reload สำเร็จ"), false);
                    return 1;
                }))
                .then(Commands.literal("stop").executes(ctx -> {
                    if (tunnelClient != null) tunnelClient.stop();
                    ctx.getSource().sendSuccess(() -> Component.literal("§c[Mineway] หยุดแล้ว"), false);
                    return 1;
                }))
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(getStatusText()), false);
                    return 1;
                })
        );
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
            Path dir = FMLPaths.CONFIGDIR.get().resolve("mineway");
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

    @Override public void logInfo(String m)  { LOGGER.info(m); }
    @Override public void logWarn(String m)  { LOGGER.warn(m); }
    @Override public void logError(String m) { LOGGER.error(m); }
    @Override public void logDebug(String m) { if (bool("debug", false)) LOGGER.info("[DEBUG] " + m); }
    @Override public void runOnMainThread(Runnable t) { if (mcServer != null) mcServer.execute(t); else t.run(); }
    @Override public void runAsync(Runnable t) { Thread th = new Thread(t, "Mineway-Async"); th.setDaemon(true); th.start(); }
    @Override public String getPlatformName()  { return "Forge/NeoForge"; }
    @Override public String getServerVersion() { return mcServer != null ? mcServer.getServerVersion() : "unknown"; }

    private String  str(String k, String d)  { Object v = config.get(k); return v != null ? v.toString() : d; }
    private int     num(String k, int d)     { Object v = config.get(k); return v instanceof Number ? ((Number)v).intValue() : d; }
    private boolean bool(String k, boolean d){ Object v = config.get(k); return v instanceof Boolean ? (Boolean)v : d; }

    public static MinewayForge getInstance() { return INSTANCE; }
}
