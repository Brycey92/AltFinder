package me.egg82.altfinder;

import co.aikar.commands.ConditionFailedException;
import co.aikar.commands.PaperCommandManager;
import co.aikar.commands.RegisteredCommand;
import co.aikar.taskchain.BukkitTaskChainFactory;
import co.aikar.taskchain.TaskChainFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.SetMultimap;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import me.egg82.altfinder.commands.AltFinderCommand;
import me.egg82.altfinder.commands.SeenCommand;
import me.egg82.altfinder.core.SQLFetchResult;
import me.egg82.altfinder.enums.SQLType;
import me.egg82.altfinder.events.AsyncPlayerPreLoginCacheHandler;
import me.egg82.altfinder.events.PlayerLoginUpdateNotifyHandler;
import me.egg82.altfinder.extended.CachedConfigValues;
import me.egg82.altfinder.extended.Configuration;
import me.egg82.altfinder.extended.RabbitMQReceiver;
import me.egg82.altfinder.extended.RedisSubscriber;
import me.egg82.altfinder.hooks.PlayerAnalyticsHook;
import me.egg82.altfinder.hooks.PluginHook;
import me.egg82.altfinder.services.Redis;
import me.egg82.altfinder.sql.MySQL;
import me.egg82.altfinder.sql.SQLite;
import me.egg82.altfinder.utils.ConfigurationFileUtil;
import me.egg82.altfinder.utils.LogUtil;
import me.egg82.altfinder.utils.ValidationUtil;
import ninja.egg82.events.BukkitEventSubscriber;
import ninja.egg82.events.BukkitEvents;
import ninja.egg82.service.ServiceLocator;
import ninja.egg82.service.ServiceNotFoundException;
import ninja.egg82.updater.SpigotUpdater;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AltFinder {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final ExecutorService workPool = Executors.newFixedThreadPool(2, new ThreadFactoryBuilder().setNameFormat("AltFinder-%d").build());

    private TaskChainFactory taskFactory;
    private PaperCommandManager commandManager;

    private List<BukkitEventSubscriber<?>> events = new ArrayList<>();

    private Metrics metrics = null;

    private final Plugin plugin;
    private final boolean isBukkit;

    public AltFinder(Plugin plugin) {
        isBukkit = Bukkit.getName().equals("Bukkit") || Bukkit.getName().equals("CraftBukkit");
        this.plugin = plugin;
    }

    public void onLoad() {
        if (!Bukkit.getName().equals("Paper") && !Bukkit.getName().equals("PaperSpigot")) {
            log(Level.INFO, ChatColor.AQUA + "====================================");
            log(Level.INFO, ChatColor.YELLOW + "AltFinder runs better on Paper!");
            log(Level.INFO, ChatColor.YELLOW + "https://whypaper.emc.gs/");
            log(Level.INFO, ChatColor.AQUA + "====================================");
        }

        if (Bukkit.getBukkitVersion().startsWith("1.8") || Bukkit.getBukkitVersion().startsWith("1.8.8")) {
            log(Level.INFO, ChatColor.AQUA + "====================================");
            log(Level.INFO, ChatColor.DARK_RED + "DEAR LORD why are you on 1.8???");
            log(Level.INFO, ChatColor.DARK_RED + "Have you tried ViaVersion or ProtocolSupport lately?");
            log(Level.INFO, ChatColor.AQUA + "====================================");
        }
    }

    public void onEnable() {
        taskFactory = BukkitTaskChainFactory.create(plugin);
        commandManager = new PaperCommandManager(plugin);
        commandManager.enableUnstableAPI("help");

        loadServices();
        loadSQL();
        loadCommands();
        loadEvents();
        loadHooks();
        loadMetrics();

        plugin.getServer().getConsoleSender().sendMessage(LogUtil.getHeading() + ChatColor.GREEN + "Enabled");

        plugin.getServer().getConsoleSender().sendMessage(LogUtil.getHeading()
                + ChatColor.YELLOW + "[" + ChatColor.AQUA + "Version " + ChatColor.WHITE + plugin.getDescription().getVersion() + ChatColor.YELLOW +  "] "
                + ChatColor.YELLOW + "[" + ChatColor.WHITE + commandManager.getRegisteredRootCommands().size() + ChatColor.GOLD + " Commands" + ChatColor.YELLOW +  "] "
                + ChatColor.YELLOW + "[" + ChatColor.WHITE + events.size() + ChatColor.BLUE + " Events" + ChatColor.YELLOW +  "]"
        );

        checkUpdate();
    }

    public void onDisable() {
        taskFactory.shutdown(8, TimeUnit.SECONDS);
        commandManager.unregisterCommands();

        for (BukkitEventSubscriber<?> event : events) {
            event.cancel();
        }
        events.clear();

        unloadHooks();
        unloadServices();

        plugin.getServer().getConsoleSender().sendMessage(LogUtil.getHeading() + ChatColor.DARK_RED + "Disabled");
    }

    private void loadServices() {
        ConfigurationFileUtil.reloadConfig(plugin);

        Configuration config;
        CachedConfigValues cachedConfig;

        try {
            config = ServiceLocator.get(Configuration.class);
            cachedConfig = ServiceLocator.get(CachedConfigValues.class);
        } catch (InstantiationException | IllegalAccessException | ServiceNotFoundException ex) {
            logger.error(ex.getMessage(), ex);
            return;
        }

        workPool.submit(() -> new RedisSubscriber(cachedConfig.getRedisPool(), config.getNode("redis")));
        ServiceLocator.register(new RabbitMQReceiver(cachedConfig.getRabbitConnectionFactory()));
        ServiceLocator.register(new SpigotUpdater(plugin, 57678));
    }

    private void loadSQL() {
        Configuration config;
        CachedConfigValues cachedConfig;

        try {
            config = ServiceLocator.get(Configuration.class);
            cachedConfig = ServiceLocator.get(CachedConfigValues.class);
        } catch (InstantiationException | IllegalAccessException | ServiceNotFoundException ex) {
            logger.error(ex.getMessage(), ex);
            return;
        }

        if (cachedConfig.getSQLType() == SQLType.MySQL) {
            MySQL.createTables(cachedConfig.getSQL(), config.getNode("storage")).thenRun(() ->
                    MySQL.loadInfo(cachedConfig.getSQL(), config.getNode("storage")).thenAccept(v -> {
                        Redis.updateFromQueue(v, cachedConfig.getRedisPool(), config.getNode("redis"));
                        updateSQL();
                    })
            );
        } else if (cachedConfig.getSQLType() == SQLType.SQLite) {
            SQLite.createTables(cachedConfig.getSQL(), config.getNode("storage")).thenRun(() ->
                    SQLite.loadInfo(cachedConfig.getSQL(), config.getNode("storage")).thenAccept(v -> {
                        Redis.updateFromQueue(v, cachedConfig.getRedisPool(), config.getNode("redis"));
                        updateSQL();
                    })
            );
        }
    }

    private void updateSQL() {
        workPool.submit(() -> {
            try {
                Thread.sleep(10L * 1000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }

            Configuration config;
            CachedConfigValues cachedConfig;

            try {
                config = ServiceLocator.get(Configuration.class);
                cachedConfig = ServiceLocator.get(CachedConfigValues.class);
            } catch (InstantiationException | IllegalAccessException | ServiceNotFoundException ex) {
                logger.error(ex.getMessage(), ex);
                return;
            }

            SQLFetchResult result = null;

            try {
                if (cachedConfig.getSQLType() == SQLType.MySQL) {
                    result = MySQL.fetchQueue(cachedConfig.getSQL(), config.getNode("storage")).get();
                }

                if (result != null) {
                    Redis.updateFromQueue(result, cachedConfig.getRedisPool(), config.getNode("redis")).get();
                }
            } catch (ExecutionException ex) {
                logger.error(ex.getMessage(), ex);
            } catch (InterruptedException ex) {
                logger.error(ex.getMessage(), ex);
                Thread.currentThread().interrupt();
            }

            updateSQL();
        });
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

        commandManager.registerCommand(new AltFinderCommand(plugin, taskFactory));
        commandManager.registerCommand(new SeenCommand(commandManager, taskFactory));
    }

    private void loadEvents() {
        events.add(BukkitEvents.subscribe(AsyncPlayerPreLoginEvent.class, EventPriority.HIGH).handler(e -> new AsyncPlayerPreLoginCacheHandler().accept(e)));
        events.add(BukkitEvents.subscribe(PlayerLoginEvent.class, EventPriority.LOW).handler(e -> new PlayerLoginUpdateNotifyHandler(plugin).accept(e)));
    }

    private void loadHooks() {
        PluginManager manager = plugin.getServer().getPluginManager();

        if (manager.getPlugin("Plan") != null) {
            plugin.getServer().getConsoleSender().sendMessage(LogUtil.getHeading() + ChatColor.GREEN + "Enabling support for Plan.");
            ServiceLocator.register(new PlayerAnalyticsHook());
        } else {
            plugin.getServer().getConsoleSender().sendMessage(LogUtil.getHeading() + ChatColor.YELLOW + "Plan was not found. Personal analytics support has been disabled.");
        }
    }

    private void loadMetrics() {
        metrics = new Metrics(plugin);
        metrics.addCustomChart(new Metrics.SimplePie("sql", () -> {
            Configuration config;
            try {
                config = ServiceLocator.get(Configuration.class);
            } catch (InstantiationException | IllegalAccessException | ServiceNotFoundException ex) {
                logger.error(ex.getMessage(), ex);
                return null;
            }

            if (!config.getNode("stats", "usage").getBoolean(true)) {
                return null;
            }

            return config.getNode("storage", "method").getString("sqlite");
        }));
        metrics.addCustomChart(new Metrics.SimplePie("redis", () -> {
            Configuration config;
            try {
                config = ServiceLocator.get(Configuration.class);
            } catch (InstantiationException | IllegalAccessException | ServiceNotFoundException ex) {
                logger.error(ex.getMessage(), ex);
                return null;
            }

            if (!config.getNode("stats", "usage").getBoolean(true)) {
                return null;
            }

            return config.getNode("redis", "enabled").getBoolean(false) ? "yes" : "no";
        }));
        metrics.addCustomChart(new Metrics.SimplePie("rabbitmq", () -> {
            Configuration config;
            try {
                config = ServiceLocator.get(Configuration.class);
            } catch (InstantiationException | IllegalAccessException | ServiceNotFoundException ex) {
                logger.error(ex.getMessage(), ex);
                return null;
            }

            if (!config.getNode("stats", "usage").getBoolean(true)) {
                return null;
            }

            return config.getNode("rabbitmq", "enabled").getBoolean(false) ? "yes" : "no";
        }));
    }

    private void checkUpdate() {
        Configuration config;
        SpigotUpdater updater;
        try {
            config = ServiceLocator.get(Configuration.class);
            updater = ServiceLocator.get(SpigotUpdater.class);
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
                    plugin.getServer().getConsoleSender().sendMessage(LogUtil.getHeading() + ChatColor.AQUA + " has an " + ChatColor.GREEN + "update" + ChatColor.AQUA + " available! New version: " + ChatColor.YELLOW + updater.getLatestVersion().get());
                } catch (ExecutionException ex) {
                    logger.error(ex.getMessage(), ex);
                } catch (InterruptedException ex) {
                    logger.error(ex.getMessage(), ex);
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    private void unloadHooks() {
        Optional<? extends PluginHook> plan;
        try {
            plan = ServiceLocator.getOptional(PlayerAnalyticsHook.class);
        } catch (InstantiationException | IllegalAccessException ex) {
            logger.error(ex.getMessage(), ex);
            plan = Optional.empty();
        }
        plan.ifPresent(v -> v.cancel());
    }

    private void unloadServices() {
        CachedConfigValues cachedConfig;
        RabbitMQReceiver rabbitReceiver;

        try {
            cachedConfig = ServiceLocator.get(CachedConfigValues.class);
            rabbitReceiver = ServiceLocator.get(RabbitMQReceiver.class);
        } catch (InstantiationException | IllegalAccessException | ServiceNotFoundException ex) {
            logger.error(ex.getMessage(), ex);
            return;
        }

        cachedConfig.getSQL().close();

        if (cachedConfig.getRedisPool() != null) {
            cachedConfig.getRedisPool().close();
        }

        try {
            rabbitReceiver.close();
        } catch (IOException | TimeoutException ignored) {}

        if (!workPool.isShutdown()) {
            workPool.shutdown();
            try {
                if (!workPool.awaitTermination(8L, TimeUnit.SECONDS)) {
                    workPool.shutdownNow();
                }
            } catch (InterruptedException ignored) {
                workPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private void log(Level level, String message) {
        plugin.getServer().getLogger().log(level, (isBukkit) ? ChatColor.stripColor(message) : message);
    }
}
