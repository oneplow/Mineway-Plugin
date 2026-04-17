package io.mineway.proxy.bungee;

import io.mineway.core.PlatformAdapter;
import io.mineway.core.config.TunnelConfig;
import io.mineway.core.tunnel.TunnelClient;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;

import java.io.*;
import java.nio.file.Files;

public class MinewayBungee extends Plugin implements PlatformAdapter {

    private TunnelClient tunnelClient;
    private Configuration config;

    @Override
    public void onEnable() {
        loadConfig();

        TunnelConfig tunnelConfig = buildConfig();
        try {
            tunnelConfig.validate();
        } catch (IllegalStateException e) {
            getLogger().severe(e.getMessage());
            return;
        }

        tunnelClient = new TunnelClient(tunnelConfig, this);
        tunnelClient.start();

        getProxy().getPluginManager().registerCommand(this, new MWCommand());
        getLogger().info("Mineway เปิดใช้งานแล้ว");
    }

    @Override
    public void onDisable() {
        if (tunnelClient != null) tunnelClient.stop();
    }

    private void loadConfig() {
        if (!getDataFolder().exists()) getDataFolder().mkdir();
        File f = new File(getDataFolder(), "config.yml");
        if (!f.exists()) {
            try (InputStream in = getResourceAsStream("config.yml")) {
                Files.copy(in, f.toPath());
            } catch (IOException e) {
                getLogger().severe("ไม่สามารถสร้าง config.yml: " + e.getMessage());
            }
        }
        try {
            config = ConfigurationProvider.getProvider(YamlConfiguration.class).load(f);
        } catch (IOException e) {
            getLogger().severe("ไม่สามารถอ่าน config.yml: " + e.getMessage());
        }
    }

    private TunnelConfig buildConfig() {
        return TunnelConfig.builder()
            .apiKey(config.getString("api_key", ""))
            .autoReconnect(config.getBoolean("auto_reconnect", true))
            .reconnectDelaySeconds(config.getInt("reconnect_delay", 5))
            .debug(config.getBoolean("debug", false))
            .build();
    }

    // ─── PlatformAdapter ──────────────────────────────────────────────
    @Override public void logInfo(String msg)  { getLogger().info(msg); }
    @Override public void logWarn(String msg)  { getLogger().warning(msg); }
    @Override public void logError(String msg) { getLogger().severe(msg); }
    @Override public void logDebug(String msg) {
        if (config != null && config.getBoolean("debug", false)) getLogger().info("[DEBUG] " + msg);
    }
    @Override public void runOnMainThread(Runnable t) { getProxy().getScheduler().runAsync(this, t); }
    @Override public void runAsync(Runnable t)        { getProxy().getScheduler().runAsync(this, t); }
    @Override public String getPlatformName()   { return "BungeeCord/Waterfall"; }
    @Override public String getServerVersion()  { return getProxy().getVersion(); }

    // ─── Command ─────────────────────────────────────────────────────
    private class MWCommand extends Command {
        MWCommand() { super("mineway", "mineway.admin", "mct"); }

        @Override
        public void execute(CommandSender sender, String[] args) {
            String sub = args.length > 0 ? args[0].toLowerCase() : "status";
            String prefix = ChatColor.YELLOW + "[Mineway] ";
            switch (sub) {
                case "status":
                    sender.sendMessage(prefix + (tunnelClient != null && tunnelClient.isConnected()
                        ? ChatColor.GREEN + "ออนไลน์ — " + tunnelClient.getHostname()
                        : ChatColor.RED   + "ออฟไลน์"));
                    break;
                case "reload":
                    if (tunnelClient != null) tunnelClient.stop();
                    loadConfig();
                    TunnelConfig nc = buildConfig();
                    try {
                        nc.validate();
                        tunnelClient = new TunnelClient(nc, MinewayBungee.this);
                        tunnelClient.start();
                        sender.sendMessage(prefix + ChatColor.GREEN + "Reload สำเร็จ");
                    } catch (IllegalStateException e) {
                        sender.sendMessage(prefix + ChatColor.RED + e.getMessage());
                    }
                    break;
                default:
                    sender.sendMessage(prefix + "คำสั่ง: status | reload");
            }
        }
    }
}
