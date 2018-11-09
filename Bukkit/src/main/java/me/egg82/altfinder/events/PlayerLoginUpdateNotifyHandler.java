package me.egg82.altfinder.events;

import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import me.egg82.altfinder.extended.Configuration;
import me.egg82.altfinder.utils.LogUtil;
import ninja.egg82.service.ServiceLocator;
import ninja.egg82.service.ServiceNotFoundException;
import ninja.egg82.updater.SpigotUpdater;
import org.bukkit.ChatColor;
import org.bukkit.event.player.PlayerLoginEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PlayerLoginUpdateNotifyHandler implements Consumer<PlayerLoginEvent> {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    public void accept(PlayerLoginEvent event) {
        if (!event.getPlayer().hasPermission("altfinder.admin")) {
            return;
        }

        Configuration config;
        SpigotUpdater updater;

        try {
            config = ServiceLocator.get(Configuration.class);
            updater = ServiceLocator.get(SpigotUpdater.class);
        } catch (InstantiationException | IllegalAccessException | ServiceNotFoundException ex) {
            logger.error(ex.getMessage(), ex);
            return;
        }

        updater.isUpdateAvailable().thenAccept(v -> {
            if (!v) {
                return;
            }

            if (config.getNode("update", "notify").getBoolean(true)) {
                try {
                    event.getPlayer().sendMessage(LogUtil.getHeading() + ChatColor.AQUA + " (Bukkit) has an " + ChatColor.GREEN + "update" + ChatColor.AQUA + " available! New version: " + ChatColor.YELLOW + updater.getLatestVersion().get());
                } catch (ExecutionException ex) {
                    logger.error(ex.getMessage(), ex);
                } catch (InterruptedException ex) {
                    logger.error(ex.getMessage(), ex);
                    Thread.currentThread().interrupt();
                }
            }
        });
    }
}
