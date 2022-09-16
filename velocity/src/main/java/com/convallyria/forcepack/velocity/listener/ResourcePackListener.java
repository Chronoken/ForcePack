package com.convallyria.forcepack.velocity.listener;

import com.convallyria.forcepack.api.resourcepack.ResourcePack;
import com.convallyria.forcepack.api.utils.ClientVersion;
import com.convallyria.forcepack.api.utils.GeyserUtil;
import com.convallyria.forcepack.velocity.ForcePackVelocity;
import com.convallyria.forcepack.velocity.config.VelocityConfig;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.PlayerResourcePackStatusEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class ResourcePackListener {

    private final ForcePackVelocity plugin;
    private final Map<UUID, Long> lastStatus;

    public ResourcePackListener(final ForcePackVelocity plugin) {
        this.plugin = plugin;
        this.lastStatus = new HashMap<>();
    }

    @Subscribe(order = PostOrder.EARLY)
    public void onPackStatus(PlayerResourcePackStatusEvent event) {
        final Player player = event.getPlayer();
        final Optional<ServerConnection> currentServer = player.getCurrentServer();
        if (currentServer.isEmpty()) {
            plugin.log(player.getUsername() + "'s server does not exist.");
            return;
        }

        // Check if the server they're on has a resource pack
        final String serverName = currentServer.get().getServerInfo().getName();
        final Optional<ResourcePack> packByServer = plugin.getPackByServer(serverName);
        if (packByServer.isEmpty()) {
            plugin.log(serverName + " does not have a ResourcePack, ignoring.");
            return;
        }

        final long diff = System.currentTimeMillis() - lastStatus.getOrDefault(player.getUniqueId(), 0L);
        this.lastStatus.put(player.getUniqueId(), System.currentTimeMillis());

        if (diff < 300) {
            player.disconnect(Component.text("Sending resource pack statuses too fast", NamedTextColor.RED));
            return;
        }

        final PlayerResourcePackStatusEvent.Status status = event.getStatus();
        boolean geyser = plugin.getConfig().getBoolean("geyser") && GeyserUtil.isBedrockPlayer(player.getUniqueId());
        boolean canBypass = player.hasPermission("ForcePack.bypass") && plugin.getConfig().getBoolean("bypass-permission");
        if (!canBypass && !geyser) {
            if (event.getStatus() == PlayerResourcePackStatusEvent.Status.SUCCESSFUL) {
                if (plugin.getConfig().getBoolean("enable-mc-252082-fix-bug-fix", false)) {
                    // 1.19.1+ fixes MC-252082 but this introduces another bug:
                    // If you change just the hash of an updated resource pack file, the client will tell the server
                    // it accepted and successfully loaded (very fast) even though it fails to apply the pack
                    // for some reason. Sending again means the pack applies correctly?
                    if (player.getProtocolVersion().getProtocol() >= 760 && diff < 1000) {
                        currentServer.get().sendPluginMessage(MinecraftChannelIdentifier.create("forcepack", "status"), "FAILED_DOWNLOAD".getBytes(StandardCharsets.UTF_8));
                        onJoin(new ServerPostConnectEvent(player, null));
                        return;
                    }
                }

                // No longer applying, remove them from the list
                currentServer.get().sendPluginMessage(MinecraftChannelIdentifier.create("forcepack", "status"), "SUCCESSFULLY_LOADED".getBytes(StandardCharsets.UTF_8));
                plugin.getPackHandler().getApplying().remove(player.getUniqueId());
            }
            plugin.log(player.getUsername() + " sent status: " + event.getStatus());

            final VelocityConfig root;
            if (packByServer.get().getServer().equals(ForcePackVelocity.GLOBAL_SERVER_NAME)) {
                root = plugin.getConfig().getConfig("global-pack");
            } else {
                root = plugin.getConfig().getConfig("servers").getConfig(serverName);
            }

            final VelocityConfig actions = root.getConfig("actions").getConfig(status.name());
            for (String cmd : actions.getStringList("commands")) {
                final CommandSource console = plugin.getServer().getConsoleCommandSource();
                plugin.getServer().getCommandManager().executeAsync(console, cmd);
            }

            final boolean kick = actions.getBoolean("kick");
            final String text = actions.getString("message");
            if (text == null) return;

            final Component component = plugin.getMiniMessage().deserialize(text);
            if (kick) {
                player.disconnect(component);
            } else {
                player.sendMessage(component);
            }
        } else {
            plugin.log("Ignoring player " + player.getUsername() + " as they do not have permissions or are a geyser player.");
        }
    }

    @Subscribe(order = PostOrder.EARLY)
    public void onJoin(ServerPostConnectEvent event) {
        final Player player = event.getPlayer();
        final Optional<ServerConnection> currentServer = player.getCurrentServer();
        if (currentServer.isEmpty()) return;

        // Find whether the config contains this server
        final ServerInfo serverInfo = currentServer.get().getServerInfo();

        boolean geyser = plugin.getConfig().getBoolean("geyser") && GeyserUtil.isBedrockPlayer(player.getUniqueId());
        boolean canBypass = player.hasPermission("ForcePack.bypass") && plugin.getConfig().getBoolean("bypass-permission");
        if (!canBypass && !geyser) {
            plugin.getPackHandler().setPack(player, serverInfo);
        }
    }

    @Subscribe
    public void onLeave(DisconnectEvent event) {
        final Player player = event.getPlayer();
        lastStatus.remove(player.getUniqueId());
    }
}
