package me.egg82.altfinder;

import co.aikar.commands.BungeeCommandManager;
import co.aikar.commands.ConditionFailedException;
import co.aikar.commands.RegisteredCommand;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.SetMultimap;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.io.File;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import me.egg82.altfinder.commands.AltFinderCommand;
import me.egg82.altfinder.commands.SeenCommand;
import me.egg82.altfinder.events.PostLoginCacheHandler;
import me.egg82.altfinder.events.PostLoginUpdateNotifyHandler;
import me.egg82.altfinder.extended.Configuration;
import me.egg82.altfinder.hooks.PlayerAnalyticsHook;
import me.egg82.altfinder.hooks.PluginHook;
import me.egg82.altfinder.services.GameAnalyticsErrorHandler;
import me.egg82.altfinder.utils.*;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.plugin.PluginManager;
import net.md_5.bungee.event.EventPriority;
import ninja.egg82.events.BungeeEventSubscriber;
import ninja.egg82.events.BungeeEvents;
import ninja.egg82.service.ServiceLocator;
import ninja.egg82.service.ServiceNotFoundException;
import ninja.egg82.updater.BungeeUpdater;
import org.bstats.bungeecord.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AltFinder {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private ExecutorService workPool = Executors.newFixedThreadPool(1, new ThreadFactoryBuilder().setNameFormat("AltFinder-%d").build());

    private BungeeCommandManager commandManager;

    private List<BungeeEventSubscriber<?>> events = new ArrayList<>();

    private Metrics metrics = null;

    private final Plugin plugin;

    public AltFinder(Plugin plugin) {
        this.plugin = plugin;
    }

    public void onLoad() {
        if (BungeeEnvironmentUtil.getEnvironment() != BungeeEnvironmentUtil.Environment.WATERFALL) {
            plugin.getProxy().getLogger().log(Level.INFO, ChatColor.AQUA + "====================================");
            plugin.getProxy().getLogger().log(Level.INFO, ChatColor.YELLOW + "AltFinder runs better on Waterfall!");
            plugin.getProxy().getLogger().log(Level.INFO, ChatColor.YELLOW + "https://whypaper.emc.gs/");
            plugin.getProxy().getLogger().log(Level.INFO, ChatColor.AQUA + "====================================");
        }
    }

    public void onEnable() {
        GameAnalyticsErrorHandler.open(ServerIDUtil.getID(new File(plugin.getDataFolder(), "stats-id.txt")), plugin.getDescription().getVersion(), plugin.getProxy().getVersion());

        commandManager = new BungeeCommandManager(plugin);
        commandManager.enableUnstableAPI("help");

        loadServices();
        loadCommands();
        loadEvents();
        loadHooks();
        loadMetrics();

        plugin.getProxy().getConsole().sendMessage(new TextComponent(LogUtil.getHeading() + ChatColor.GREEN + "Enabled"));

        plugin.getProxy().getConsole().sendMessage(new TextComponent(LogUtil.getHeading()
                + ChatColor.YELLOW + "[" + ChatColor.AQUA + "Version " + ChatColor.WHITE + plugin.getDescription().getVersion() + ChatColor.YELLOW +  "] "
                + ChatColor.YELLOW + "[" + ChatColor.WHITE + commandManager.getRegisteredRootCommands().size() + ChatColor.GOLD + " Commands" + ChatColor.YELLOW +  "] "
                + ChatColor.YELLOW + "[" + ChatColor.WHITE + events.size() + ChatColor.BLUE + " Events" + ChatColor.YELLOW +  "]"
        ));

        workPool.submit(this::checkUpdate);
    }

    public void onDisable() {
        commandManager.unregisterCommands();

        for (BungeeEventSubscriber<?> event : events) {
            event.cancel();
        }
        events.clear();

        unloadHooks();
        unloadServices();

        plugin.getProxy().getConsole().sendMessage(new TextComponent(LogUtil.getHeading() + ChatColor.DARK_RED + "Disabled"));

        GameAnalyticsErrorHandler.close();
    }

    private void loadServices() {
        ConfigurationFileUtil.reloadConfig(plugin);

        ServiceUtil.registerWorkPool();
        ServiceUtil.registerRedis();
        ServiceUtil.registerRabbit();
        ServiceUtil.registerSQL();

        ServiceLocator.register(new BungeeUpdater(plugin, 64194));
    }

    private void loadCommands() {
        commandManager.getCommandConditions().addCondition(String.class, "ip", (c, exec, value) -> {
            if (!ValidationUtil.isValidIp(value)) {
                throw new ConditionFailedException("Value must be a valid IP address.");
            }
        });

        commandManager.getCommandCompletions().registerCompletion("subcommand", c -> {
            String lower = c.getInput().toLowerCase();
            Set<String> commands = new LinkedHashSet<>();
            SetMultimap<String, RegisteredCommand> subcommands = commandManager.getRootCommand("altfinder").getSubCommands();
            for (Map.Entry<String, RegisteredCommand> kvp : subcommands.entries()) {
                if (!kvp.getValue().isPrivate() && (lower.isEmpty() || kvp.getKey().toLowerCase().startsWith(lower)) && kvp.getValue().getCommand().indexOf(' ') == -1) {
                    commands.add(kvp.getValue().getCommand());
                }
            }
            return ImmutableList.copyOf(commands);
        });

        commandManager.registerCommand(new AltFinderCommand(plugin));
        commandManager.registerCommand(new SeenCommand(commandManager));
    }

    private void loadEvents() {
        events.add(BungeeEvents.subscribe(plugin, PostLoginEvent.class, EventPriority.LOW).handler(e -> new PostLoginCacheHandler().accept(e)));
        events.add(BungeeEvents.subscribe(plugin, PostLoginEvent.class, EventPriority.LOW).handler(e -> new PostLoginUpdateNotifyHandler().accept(e)));
    }

    private void loadHooks() {
        PluginManager manager = plugin.getProxy().getPluginManager();

        if (manager.getPlugin("Plan") != null) {
            plugin.getProxy().getConsole().sendMessage(new TextComponent(LogUtil.getHeading() + ChatColor.GREEN + "Enabling support for Plan."));
            ServiceLocator.register(new PlayerAnalyticsHook(plugin));
        } else {
            plugin.getProxy().getConsole().sendMessage(new TextComponent(LogUtil.getHeading() + ChatColor.YELLOW + "Plan was not found. Personal analytics support has been disabled."));
        }
    }

    private void loadMetrics() {
        metrics = new Metrics(plugin);
        metrics.addCustomChart(new Metrics.SimplePie("sql", () -> {
            Optional<Configuration> config = ConfigUtil.getConfig();
            if (!config.isPresent()) {
                return null;
            }

            if (!config.get().getNode("stats", "usage").getBoolean(true)) {
                return null;
            }

            return config.get().getNode("storage", "method").getString("sqlite");
        }));
        metrics.addCustomChart(new Metrics.SimplePie("redis", () -> {
            Optional<Configuration> config = ConfigUtil.getConfig();
            if (!config.isPresent()) {
                return null;
            }

            if (!config.get().getNode("stats", "usage").getBoolean(true)) {
                return null;
            }

            return config.get().getNode("redis", "enabled").getBoolean(false) ? "yes" : "no";
        }));
        metrics.addCustomChart(new Metrics.SimplePie("rabbitmq", () -> {
            Optional<Configuration> config = ConfigUtil.getConfig();
            if (!config.isPresent()) {
                return null;
            }

            if (!config.get().getNode("stats", "usage").getBoolean(true)) {
                return null;
            }

            return config.get().getNode("rabbitmq", "enabled").getBoolean(false) ? "yes" : "no";
        }));
    }

    private void checkUpdate() {
        Optional<Configuration> config = ConfigUtil.getConfig();
        if (!config.isPresent()) {
            return;
        }

        BungeeUpdater updater;
        try {
            updater = ServiceLocator.get(BungeeUpdater.class);
        } catch (InstantiationException | IllegalAccessException | ServiceNotFoundException ex) {
            logger.error(ex.getMessage(), ex);
            return;
        }

        if (!config.get().getNode("update", "check").getBoolean(true)) {
            return;
        }

        updater.isUpdateAvailable().thenAccept(v -> {
            if (!v) {
                return;
            }

            try {
                plugin.getProxy().getConsole().sendMessage(new TextComponent(LogUtil.getHeading() + ChatColor.AQUA + " has an " + ChatColor.GREEN + "update" + ChatColor.AQUA + " available! New version: " + ChatColor.YELLOW + updater.getLatestVersion().get()));
            } catch (ExecutionException ex) {
                logger.error(ex.getMessage(), ex);
            } catch (InterruptedException ex) {
                logger.error(ex.getMessage(), ex);
                Thread.currentThread().interrupt();
            }
        });

        try {
            Thread.sleep(60L * 60L * 1000L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        workPool.submit(this::checkUpdate);
    }

    private void unloadHooks() {
        Set<? extends PluginHook> hooks = ServiceLocator.remove(PluginHook.class);
        for (PluginHook hook : hooks) {
            hook.cancel();
        }
    }

    public void unloadServices() {
        ServiceUtil.unregisterWorkPool();
        ServiceUtil.unregisterRedis();
        ServiceUtil.unregisterRabbit();
        ServiceUtil.unregisterSQL();
    }
}
