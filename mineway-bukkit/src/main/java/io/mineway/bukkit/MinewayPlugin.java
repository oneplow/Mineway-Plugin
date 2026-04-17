package io.mineway.bukkit;

import io.mineway.core.PlatformAdapter;
import io.mineway.core.config.TunnelConfig;
import io.mineway.core.tunnel.TunnelClient;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class MinewayPlugin extends JavaPlugin implements PlatformAdapter, Listener {

    private TunnelClient tunnelClient;

    // ─── JavaPlugin lifecycle ─────────────────────────────────────────
    @Override
    public void onEnable() {
        saveDefaultConfig();

        TunnelConfig config = buildConfig();
        try {
            config.validate();
        } catch (IllegalStateException e) {
            getLogger().severe(e.getMessage());
            getLogger().severe("Plugin จะหยุดทำงาน กรุณาแก้ config.yml แล้วรัน /mineway reload");
            return;
        }

        tunnelClient = new TunnelClient(config, this);
        
        // Register server load event to start tunnel after "Done!"
        getServer().getPluginManager().registerEvents(this, this);
    }
    
    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        if (tunnelClient != null && !tunnelClient.isConnected()) {
            tunnelClient.start();
        }
    }

    @Override
    public void onDisable() {
        if (tunnelClient != null) {
            tunnelClient.stop();
        }
    }

    // ─── Commands ─────────────────────────────────────────────────────
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("mineway")) return false;

        if (!sender.hasPermission("mineway.admin")) {
            sender.sendMessage("§cคุณไม่มีสิทธิ์ใช้คำสั่งนี้");
            return true;
        }

        String sub = args.length > 0 ? args[0].toLowerCase() : "status";

        switch (sub) {
            case "status":
                if (tunnelClient == null) {
                    sender.sendMessage("§e[Mineway] §cไม่ได้ทำงาน (config ไม่ถูกต้อง)");
                } else if (tunnelClient.isConnected()) {
                    sender.sendMessage("§e[Mineway] §aออนไลน์");
                    sender.sendMessage("§e  Hostname: §f" + tunnelClient.getHostname());
                    sender.sendMessage("§e  ผู้เล่น: §f" + tunnelClient.getActivePipes() + " คน");
                } else {
                    sender.sendMessage("§e[Mineway] §cออฟไลน์ / กำลัง reconnect...");
                }
                break;

            case "reload":
                reloadConfig();
                if (tunnelClient != null) tunnelClient.stop();
                TunnelConfig newConfig = buildConfig();
                try {
                    newConfig.validate();
                    tunnelClient = new TunnelClient(newConfig, this);
                    tunnelClient.start();
                    sender.sendMessage("§e[Mineway] §aReload สำเร็จ");
                } catch (IllegalStateException e) {
                    sender.sendMessage("§e[Mineway] §cConfig ไม่ถูกต้อง: " + e.getMessage());
                }
                break;

            case "stop":
                if (tunnelClient != null) {
                    tunnelClient.stop();
                    sender.sendMessage("§e[Mineway] §cหยุดแล้ว");
                }
                break;

            case "start":
                if (tunnelClient != null && !tunnelClient.isConnected()) {
                    tunnelClient.start();
                    sender.sendMessage("§e[Mineway] §aเริ่มแล้ว");
                }
                break;

            default:
                sender.sendMessage("§e[Mineway] §7คำสั่ง: status | reload | stop | start");
        }

        return true;
    }

    // ─── PlatformAdapter ──────────────────────────────────────────────
    @Override public void logInfo(String msg)  { getLogger().info(msg); }
    @Override public void logWarn(String msg)  { getLogger().warning(msg); }
    @Override public void logError(String msg) { getLogger().severe(msg); }
    @Override public void logDebug(String msg) {
        if (getConfig().getBoolean("debug", false)) getLogger().info("[DEBUG] " + msg);
    }

    @Override
    public void runOnMainThread(Runnable task) {
        getServer().getScheduler().runTask(this, task);
    }

    @Override
    public void runAsync(Runnable task) {
        getServer().getScheduler().runTaskAsynchronously(this, task);
    }

    @Override public String getPlatformName()   { return "Bukkit/Spigot/Paper"; }
    @Override public String getServerVersion()  { return getServer().getVersion(); }

    // ─── Helper ───────────────────────────────────────────────────────
    private TunnelConfig buildConfig() {
        return TunnelConfig.builder()
            .apiKey(getConfig().getString("api_key", ""))
            .autoReconnect(getConfig().getBoolean("auto_reconnect", true))
            .reconnectDelaySeconds(getConfig().getInt("reconnect_delay", 5))
            .debug(getConfig().getBoolean("debug", false))
            .build();
    }
}
