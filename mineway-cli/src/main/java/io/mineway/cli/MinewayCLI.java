package io.mineway.cli;

import io.mineway.core.PlatformAdapter;
import io.mineway.core.config.TunnelConfig;
import io.mineway.core.tunnel.TunnelClient;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Scanner;

public class MinewayCLI implements PlatformAdapter {

    private final String version = "1.0.0";
    private TunnelConfig currentConfig;
    private TunnelClient client;

    public static void main(String[] args) {
        new MinewayCLI().start(args);
    }

    public void start(String[] args) {
        String apiKey = null;
        int tcpPort = 25565; // Default Minecraft Java Port
        int udpPort = 19132; // Default Minecraft Bedrock Port
        boolean debug = false;

        // Simple argument parsing
        for (int i = 0; i < args.length; i++) {
            switch (args[i].toLowerCase()) {
                case "--key":
                case "-k":
                    if (i + 1 < args.length) apiKey = args[++i];
                    break;
                case "--port":
                case "-p":
                    if (i + 1 < args.length) {
                        int p = Integer.parseInt(args[++i]);
                        tcpPort = p;
                        udpPort = p; // Shorthand sets both
                    }
                    break;
                case "--tcp-port":
                    if (i + 1 < args.length) tcpPort = Integer.parseInt(args[++i]);
                    break;
                case "--udp-port":
                    if (i + 1 < args.length) udpPort = Integer.parseInt(args[++i]);
                    break;
                case "--debug":
                    debug = true;
                    break;
                case "--help":
                case "-h":
                    printHelp();
                    return;
            }
        }

        if (apiKey == null) {
            logError("Missing required argument: --key");
            printHelp();
            return;
        }

        logInfo("Starting Mineway Tunnel CLI v" + version);
        logInfo("Target TCP Port (Java): " + tcpPort);
        logInfo("Target UDP Port (Bedrock): " + udpPort);
        
        currentConfig = TunnelConfig.builder()
                .apiKey(apiKey)
                .targetTcpPort(tcpPort)
                .targetUdpPort(udpPort)
                .autoReconnect(true)
                .reconnectDelaySeconds(5)
                .debug(debug)
                .build();

        try {
            currentConfig.validate();
        } catch (Exception e) {
            logError("Config validation failed: " + e.getMessage());
            return;
        }

        client = new TunnelClient(currentConfig, this);
        client.start();

        // Keep the CLI running and reading console input
        Scanner scanner = new Scanner(System.in);
        while (true) {
            if (scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if ("stop".equalsIgnoreCase(line) || "exit".equalsIgnoreCase(line) || "quit".equalsIgnoreCase(line)) {
                    logInfo("Stopping tunnel...");
                    client.stop();
                    System.exit(0);
                } else if ("status".equalsIgnoreCase(line)) {
                    if (client.isConnected()) {
                        logInfo("Status: ONLINE | Hostname: " + client.getHostname() + " | Players: " + client.getActivePipes());
                    } else {
                        logInfo("Status: OFFLINE / Reconnecting...");
                    }
                }
            }
        }
    }

    private void printHelp() {
        System.out.println("Usage: java -jar mineway.jar [options]");
        System.out.println("Options:");
        System.out.println("  --key <apikey>    (Required) Your Mineway API Key starting with mw_live_");
        System.out.println("  --port <port>     (Optional) Sets BOTH TCP and UDP target ports shortcut");
        System.out.println("  --tcp-port <port> (Optional) Minecraft Java local target port (default: 25565)");
        System.out.println("  --udp-port <port> (Optional) Minecraft Bedrock local target port (default: 19132)");
        System.out.println("  --debug           (Optional) Enable console debug logs");
        System.out.println("  --help            Show this help message");
    }

    private String getTimestamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    // --- Platform Adapter Implementation ---

    @Override
    public void logInfo(String message) {
        System.out.println("[" + getTimestamp() + " INFO] [Mineway] " + message);
    }

    @Override
    public void logWarn(String message) {
        System.out.println("\u001B[33m[" + getTimestamp() + " WARN] [Mineway] " + message + "\u001B[0m");
    }

    @Override
    public void logError(String message) {
        System.err.println("\u001B[31m[" + getTimestamp() + " ERROR] [Mineway] " + message + "\u001B[0m");
    }

    @Override
    public void logDebug(String message) {
        if (currentConfig != null && currentConfig.isDebug()) {
            System.out.println("\u001B[36m[" + getTimestamp() + " DEBUG] [Mineway] " + message + "\u001B[0m");
        }
    }

    @Override
    public void runOnMainThread(Runnable task) {
        // In CLI, we just run immediately or use a simple thread.
        // For MinewayCore, running async is fine if thread safety isn't strictly required by Bukkit API here.
        new Thread(task).start();
    }

    @Override
    public void runAsync(Runnable task) {
        new Thread(task).start();
    }

    @Override
    public String getPlatformName() {
        return "Standalone CLI";
    }

    @Override
    public String getServerVersion() {
        return System.getProperty("os.name") + " " + System.getProperty("os.version");
    }

    @Override
    public TunnelConfig getLatestConfig() {
        return currentConfig; // CLI doesn't auto-reload config from file, requires restart or args.
    }
}
