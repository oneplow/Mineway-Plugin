package io.mineway.proxy.velocity;

import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public class VelocityCommand implements SimpleCommand {

    private final MinewayVelocity plugin;

    public VelocityCommand(MinewayVelocity plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation inv) {
        String[] args = inv.arguments();
        String sub = args.length > 0 ? args[0].toLowerCase() : "status";

        switch (sub) {
            case "status":
                var tc = plugin.getTunnelClient();
                if (tc != null && tc.isConnected()) {
                    inv.source().sendMessage(Component.text("[Mineway] ออนไลน์ — " + tc.getHostname(), NamedTextColor.GREEN));
                } else {
                    inv.source().sendMessage(Component.text("[Mineway] ออฟไลน์", NamedTextColor.RED));
                }
                break;
            case "reload":
                try {
                    plugin.reloadAndRestart();
                    inv.source().sendMessage(Component.text("[Mineway] Reload สำเร็จ", NamedTextColor.GREEN));
                } catch (Exception e) {
                    inv.source().sendMessage(Component.text("[Mineway] " + e.getMessage(), NamedTextColor.RED));
                }
                break;
            default:
                inv.source().sendMessage(Component.text("[Mineway] คำสั่ง: status | reload", NamedTextColor.YELLOW));
        }
    }

    @Override
    public boolean hasPermission(Invocation inv) {
        return inv.source().hasPermission("mineway.admin");
    }
}
