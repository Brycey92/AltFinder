package me.egg82.altfinder.events;

import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import me.egg82.altfinder.extended.Configuration;
import me.egg82.altfinder.utils.LogUtil;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.event.PostLoginEvent;
import ninja.egg82.service.ServiceLocator;
import ninja.egg82.service.ServiceNotFoundException;
import ninja.egg82.updater.BungeeUpdater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PostLoginUpdateNotifyHandler implements Consumer<PostLoginEvent> {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    public PostLoginUpdateNotifyHandler() {}

    public void accept(PostLoginEvent event) {
        if (!event.getPlayer().hasPermission("altfinder.admin")) {
            return;
        }

        Configuration config;
        BungeeUpdater updater;

        try {
            config = ServiceLocator.get(Configuration.class);
            updater = ServiceLocator.get(BungeeUpdater.class);
        } catch (InstantiationException | IllegalAccessException | ServiceNotFoundException ex) {
            logger.error(ex.getMessage(), ex);
            return;
        }

        if (!config.getNode("update", "check").getBoolean(true)) {
            return;
        }

        updater.isUpdateAvailable().thenAccept(v -> {
            if (!v) {
                return;
            }

            if (config.getNode("update", "notify").getBoolean(true)) {
                try {
                    event.getPlayer().sendMessage(new TextComponent(LogUtil.getHeading() + ChatColor.AQUA + " (Bungee) has an " + ChatColor.GREEN + "update" + ChatColor.AQUA + " available! New version: " + ChatColor.YELLOW + updater.getLatestVersion().get()));
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
