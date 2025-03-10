/*
 * SkinsRestorer
 * Copyright (C) 2024  SkinsRestorer Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package net.skinsrestorer.shared.plugin;

import ch.jalu.configme.SettingsManager;
import ch.jalu.configme.SettingsManagerBuilder;
import ch.jalu.injector.Injector;
import lombok.Getter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.skinsrestorer.api.SkinsRestorer;
import net.skinsrestorer.api.SkinsRestorerProvider;
import net.skinsrestorer.api.connections.MineSkinAPI;
import net.skinsrestorer.api.connections.MojangAPI;
import net.skinsrestorer.api.property.SkinApplier;
import net.skinsrestorer.api.storage.CacheStorage;
import net.skinsrestorer.api.storage.PlayerStorage;
import net.skinsrestorer.api.storage.SkinStorage;
import net.skinsrestorer.builddata.BuildData;
import net.skinsrestorer.shared.api.PlatformWrapper;
import net.skinsrestorer.shared.api.SharedSkinApplier;
import net.skinsrestorer.shared.api.SharedSkinsRestorer;
import net.skinsrestorer.shared.api.SkinApplierAccess;
import net.skinsrestorer.shared.api.event.EventBusImpl;
import net.skinsrestorer.shared.commands.ProxyGUICommand;
import net.skinsrestorer.shared.commands.SRCommand;
import net.skinsrestorer.shared.commands.ServerGUICommand;
import net.skinsrestorer.shared.commands.SkinCommand;
import net.skinsrestorer.shared.commands.library.CommandManager;
import net.skinsrestorer.shared.config.*;
import net.skinsrestorer.shared.connections.MineSkinAPIImpl;
import net.skinsrestorer.shared.connections.MojangAPIImpl;
import net.skinsrestorer.shared.connections.ServiceCheckerService;
import net.skinsrestorer.shared.exception.InitializeException;
import net.skinsrestorer.shared.floodgate.FloodgateUtil;
import net.skinsrestorer.shared.log.SRChatColor;
import net.skinsrestorer.shared.log.SRLogger;
import net.skinsrestorer.shared.storage.CacheStorageImpl;
import net.skinsrestorer.shared.storage.CooldownStorage;
import net.skinsrestorer.shared.storage.PlayerStorageImpl;
import net.skinsrestorer.shared.storage.SkinStorageImpl;
import net.skinsrestorer.shared.storage.adapter.AdapterReference;
import net.skinsrestorer.shared.storage.adapter.file.FileAdapter;
import net.skinsrestorer.shared.storage.adapter.mysql.MySQLAdapter;
import net.skinsrestorer.shared.storage.adapter.mysql.MySQLProvider;
import net.skinsrestorer.shared.subjects.SRPlayer;
import net.skinsrestorer.shared.subjects.SRProxyPlayer;
import net.skinsrestorer.shared.subjects.messages.Message;
import net.skinsrestorer.shared.subjects.messages.MessageLoader;
import net.skinsrestorer.shared.subjects.messages.SkinsRestorerLocale;
import net.skinsrestorer.shared.subjects.permissions.PermissionRegistry;
import net.skinsrestorer.shared.update.UpdateCheckInit;
import net.skinsrestorer.shared.utils.MetricsCounter;
import net.skinsrestorer.shared.utils.ReflectionUtil;
import net.skinsrestorer.shared.utils.SRHelpers;
import org.bstats.MetricsBase;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class SRPlugin {
    private static final String USER_AGENT = "SkinsRestorer/%s (%s)";
    @Getter
    private static final boolean unitTest = System.getProperty("sr.unit.test") != null;
    private final SRPlatformAdapter<?, ?> adapter;
    private final SRLogger logger;
    @Getter
    private final Path dataFolder;
    private final Injector injector;
    @Getter
    private final List<Runnable> shutdownHooks = new ArrayList<>();
    @Getter
    private boolean outdated = false;
    @Getter
    private boolean updaterInitialized = false;

    public SRPlugin(Injector injector, Path dataFolder) {
        injector.register(SRPlugin.class, this);

        this.injector = injector;
        this.adapter = injector.getSingleton(SRPlatformAdapter.class);
        this.logger = injector.getSingleton(SRLogger.class);
        this.dataFolder = dataFolder;
    }

    public void initCommands() {
        CommandManager<?> manager = new CommandManager<>(adapter, logger,
                injector.getSingleton(SkinsRestorerLocale.class),
                injector.getSingleton(SettingsManager.class));
        injector.register(CommandManager.class, manager);

        registerConditions(manager);

        adapter.runRepeatAsync(injector.getSingleton(CooldownStorage.class)::cleanup, 60, 60, TimeUnit.SECONDS);

        manager.registerCommand(injector.newInstance(SRCommand.class));

        SettingsManager settings = injector.getSingleton(SettingsManager.class);
        if (!settings.getProperty(CommandConfig.DISABLE_SKIN_COMMAND)) {
            manager.registerCommand(injector.newInstance(SkinCommand.class));
        }

        if (!settings.getProperty(CommandConfig.DISABLE_GUI_COMMAND)) {
            if (injector.getIfAvailable(SRServerPlugin.class) != null) {
                manager.registerCommand(injector.newInstance(ServerGUICommand.class));
            } else if (injector.getIfAvailable(SRProxyPlugin.class) != null) {
                manager.registerCommand(injector.newInstance(ProxyGUICommand.class));
            } else {
                throw new IllegalStateException("Unknown platform");
            }
        }
    }

    private void registerConditions(CommandManager<?> manager) {
        SettingsManager settings = injector.getSingleton(SettingsManager.class);
        CooldownStorage cooldownStorage = injector.getSingleton(CooldownStorage.class);

        manager.registerCondition("allowed-server", sender -> {
            if (!(sender instanceof SRProxyPlayer)) {
                return true;
            }

            if (!settings.getProperty(ProxyConfig.NOT_ALLOWED_COMMAND_SERVERS_ENABLED)) {
                return true;
            }

            Optional<String> optional = ((SRProxyPlayer) sender).getCurrentServer();
            if (optional.isEmpty()) {
                if (!settings.getProperty(ProxyConfig.NOT_ALLOWED_COMMAND_SERVERS_IF_NONE_BLOCK_COMMAND)) {
                    sender.sendMessage(Message.NOT_CONNECTED_TO_SERVER);
                    return false;
                }

                return true;
            }

            String server = optional.get();
            boolean inList = settings.getProperty(ProxyConfig.NOT_ALLOWED_COMMAND_SERVERS).contains(server);
            boolean shouldBlock = settings.getProperty(ProxyConfig.NOT_ALLOWED_COMMAND_SERVERS_ALLOWLIST) != inList;

            if (shouldBlock) {
                sender.sendMessage(Message.COMMAND_SERVER_NOT_ALLOWED_MESSAGE, Placeholder.unparsed("server", server));
                return false;
            }

            return true;
        });

        manager.registerCondition("cooldown", sender -> {
            if (sender instanceof SRPlayer) {
                UUID senderUUID = ((SRPlayer) sender).getUniqueId();
                if (!sender.hasPermission(PermissionRegistry.BYPASS_COOLDOWN) && cooldownStorage.hasCooldown(senderUUID)) {
                    sender.sendMessage(Message.SKIN_COOLDOWN, Placeholder.unparsed("time", String.valueOf(cooldownStorage.getCooldownSeconds(senderUUID))));

                    return false;
                }
            }

            return true;
        });
    }

    public void loadConfig() {
        SettingsManager settings = injector.getIfAvailable(SettingsManager.class);
        if (settings == null) {
            settings = SettingsManagerBuilder
                    .withYamlFile(dataFolder.resolve("config.yml"))
                    .configurationData(
                            CommentsConfig.class,
                            MessageConfig.class,
                            DatabaseConfig.class,
                            CommandConfig.class,
                            GUIConfig.class,
                            StorageConfig.class,
                            ProxyConfig.class,
                            ServerConfig.class,
                            LoginConfig.class,
                            APIConfig.class,
                            AdvancedConfig.class,
                            DevConfig.class
                    )
                    .migrationService(injector.getSingleton(ConfigMigratorService.class))
                    .create();
            injector.register(SettingsManager.class, settings);
        } else {
            settings.reload();
        }

        logger.setDebug(settings.getProperty(DevConfig.DEBUG) || unitTest);

        revertSettings(settings);
    }

    private void revertSettings(SettingsManager settings) {
        if (settings.getProperty(StorageConfig.DEFAULT_SKINS_ENABLED) && settings.getProperty(StorageConfig.DEFAULT_SKINS).isEmpty()) {
            logger.warning("[Config] No DefaultSkins configured! Disabling DefaultSkins.");
            settings.setProperty(StorageConfig.DEFAULT_SKINS_ENABLED, false);
        }

        if (settings.getProperty(CommandConfig.DISABLED_SKINS_ENABLED) && settings.getProperty(CommandConfig.DISABLED_SKINS).isEmpty()) {
            logger.warning("[Config] No DisabledSkins configured! Disabling DisabledSkins.");
            settings.setProperty(CommandConfig.DISABLED_SKINS_ENABLED, false);
        }

        if (settings.getProperty(CommandConfig.RESTRICT_SKIN_URLS_ENABLED) && settings.getProperty(CommandConfig.RESTRICT_SKIN_URLS_LIST).isEmpty()) {
            logger.warning("[Config] No RestrictSkinUrls configured! Disabling RestrictSkinUrls.");
            settings.setProperty(CommandConfig.RESTRICT_SKIN_URLS_ENABLED, false);
        }

        if (settings.getProperty(CommandConfig.FORCE_DEFAULT_PERMISSIONS)) {
            if (adapter.supportsDefaultPermissions()) {
                logger.debug("Disabling enforcing default permissions");
                settings.setProperty(CommandConfig.FORCE_DEFAULT_PERMISSIONS, false);
            } else {
                logger.info("Enforcing default permissions plugin-side due to platform limitations.");
            }
        }

        if (!settings.getProperty(GUIConfig.CUSTOM_GUI_ENABLED)) {
            settings.setProperty(GUIConfig.CUSTOM_GUI_ONLY, false);
        }

        if (!settings.getProperty(ServerConfig.DISMOUNT_PLAYER_ON_UPDATE)) {
            settings.setProperty(ServerConfig.REMOUNT_PLAYER_ON_UPDATE, false);
        }
    }

    public void loadLocales() throws IOException {
        injector.getSingleton(MessageLoader.class).loadMessages();
    }

    public void moveOldFiles() {
        try {
            SRHelpers.renameFile(dataFolder, "Archive", "archive"); // Now lowercase
        } catch (IOException e) {
            e.printStackTrace();
        }

        moveToArchive(dataFolder.resolve("messages.yml"));
        moveToArchive(dataFolder.resolve("command-messages.properties"));
        moveToArchive(dataFolder.resolve("command.properties"));
        moveToArchive(dataFolder.resolve("languages"));
    }

    public void moveToArchive(Path path) {
        if (!Files.exists(path)) {
            return;
        }

        logger.info("Moving old file " + path.getFileName() + " to archive folder.");
        Path archive = dataFolder.resolve("archive");
        Path target = archive.resolve(path.getFileName().toString() + "_" + SRHelpers.getEpochSecond());

        try {
            Files.createDirectories(archive);
            Files.move(path, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void loadStorage() throws InitializeException {
        // Initialise SkinStorage
        SkinStorageImpl skinStorage = injector.getSingleton(SkinStorageImpl.class);
        SettingsManager settings = injector.getSingleton(SettingsManager.class);
        try {
            if (settings.getProperty(DatabaseConfig.MYSQL_ENABLED)) {
                MySQLProvider mySQLProvider = injector.getSingleton(MySQLProvider.class);

                mySQLProvider.initPool();

                MySQLAdapter adapter = injector.getSingleton(MySQLAdapter.class);

                adapter.init();

                logger.info("Connected to MySQL!");

                injector.getSingleton(AdapterReference.class).setAdapter(adapter);
            } else {
                injector.getSingleton(AdapterReference.class).setAdapter(injector.getSingleton(FileAdapter.class));
            }

            // Preload default skins
            adapter.runAsync(skinStorage::preloadDefaultSkins);
        } catch (SQLException e) {
            logger.severe("§cCan't connect to MySQL! Disabling SkinsRestorer.", e);
            throw new InitializeException(e);
        }
    }

    public void initUpdateCheck(UpdateCheckInit.InitCause cause) {
        if (updaterInitialized) {
            return;
        }

        updaterInitialized = true;
        Path updaterDisabled = dataFolder.resolve("noupdate.txt");
        if (Files.exists(updaterDisabled)) {
            logger.info("Updater Disabled");
            return;
        }

        injector.getSingleton(UpdateCheckInit.class).run(cause);
    }

    public void setOutdated() {
        outdated = true;
    }

    public void registerAPI() {
        SkinsRestorer api = injector.getSingleton(SharedSkinsRestorer.class);
        SkinsRestorerProvider.setApi(api);
        injector.register(SkinsRestorer.class, api);
    }

    public <P> void registerSkinApplier(SkinApplierAccess<P> skinApplier, Class<P> playerClass, PlatformWrapper<P> platformWrapper) {
        SharedSkinApplier<P> sharedSkinApplier = new SharedSkinApplier<>(playerClass, skinApplier, platformWrapper,
                injector.getSingleton(PlayerStorageImpl.class), injector.getSingleton(SkinStorageImpl.class));
        injector.register(SharedSkinApplier.class, sharedSkinApplier);
        injector.register(SkinApplier.class, sharedSkinApplier);
    }

    public void registerMetrics(Object metricsParent) {
        MetricsBase metrics;
        try {
            Field field = metricsParent.getClass().getDeclaredField("metricsBase");
            field.setAccessible(true);
            metrics = (MetricsBase) field.get(metricsParent);
        } catch (ReflectiveOperationException e) {
            e.printStackTrace();
            return;
        }

        MetricsCounter metricsCounter = injector.getSingleton(MetricsCounter.class);
        metrics.addCustomChart(new SingleLineChart("mineskin_calls", () -> metricsCounter.collect(MetricsCounter.Service.MINE_SKIN)));
        metrics.addCustomChart(new SingleLineChart("minetools_calls", () -> metricsCounter.collect(MetricsCounter.Service.MINE_TOOLS)));
        metrics.addCustomChart(new SingleLineChart("mojang_calls", () -> metricsCounter.collect(MetricsCounter.Service.MOJANG)));
        metrics.addCustomChart(new SingleLineChart("ashcon_calls", () -> metricsCounter.collect(MetricsCounter.Service.ASHCON)));
        metrics.addCustomChart(new SimplePie("uses_mysql", metricsCounter::usesMySQL));
        metrics.addCustomChart(new SimplePie("proxy_mode", metricsCounter::isProxyMode));
    }

    public void startup(Class<? extends SRPlatformInit> initClass) throws InitializeException {
        SRServerPlugin serverPlugin = injector.getIfAvailable(SRServerPlugin.class);
        SRProxyPlugin proxyPlugin = injector.getIfAvailable(SRProxyPlugin.class);

        // Load config (Also configures logger)
        loadConfig();

        if (!unitTest) {
            registerMetrics(adapter.createMetricsInstance());
        }

        injector.getSingleton(EventBusImpl.class);

        if (serverPlugin != null) {
            // Check if we are running in proxy mode
            serverPlugin.checkProxyMode();
        }

        try {
            moveOldFiles();
            loadLocales();
        } catch (IOException e) {
            throw new InitializeException(e);
        }

        // Instantiate API classes and assign them to their interfaces
        injector.register(MineSkinAPI.class, injector.getSingleton(MineSkinAPIImpl.class));
        injector.register(MojangAPI.class, injector.getSingleton(MojangAPIImpl.class));

        injector.register(CacheStorage.class, injector.getSingleton(CacheStorageImpl.class));
        injector.register(SkinStorage.class, injector.getSingleton(SkinStorageImpl.class));
        injector.register(PlayerStorage.class, injector.getSingleton(PlayerStorageImpl.class));

        SRPlatformInit platformInit = injector.newInstance(initClass);
        platformInit.initSkinApplier();

        platformInit.checkPluginSupport();

        platformInit.prePlatformInit();

        if (serverPlugin != null) {
            serverPlugin.startupPlatform((SRServerPlatformInit) platformInit);
        } else if (proxyPlugin != null) {
            proxyPlugin.startupPlatform((SRProxyPlatformInit) platformInit);
        } else {
            throw new IllegalStateException("No platform class available!");
        }

        if (ReflectionUtil.classExists("org.geysermc.floodgate.api.FloodgateApi")) {
            FloodgateUtil.registerListener(injector);
        }

        runJavaCheck();

        initUpdateCheck(UpdateCheckInit.InitCause.STARTUP);

        if (serverPlugin == null || !serverPlugin.isProxyMode()) {
            adapter.runAsync(this::runServiceCheck);
        }
    }

    private void runJavaCheck() {
        String specVersion = System.getProperty("java.specification.version");

        try {
            int version = getJavaVersion(specVersion);
            if (version < 17) {
                logger.warning(SRChatColor.YELLOW + "Your Java version \"" + specVersion + "\" is not recommended! SkinsRestorer now uses Java 17 primarily.");
                logger.warning(SRChatColor.YELLOW + "The plugin was \"downgraded\" to Java 1.8 (Java 8) to ensure compatibility with your server, but it may cause issues.");
                logger.warning(SRChatColor.YELLOW + "The plugin still works, but it may have Java version related issues.");
                logger.warning(SRChatColor.YELLOW + "Please update your server Java version to 17 or higher to get the best performance, security and to avoid issues with SkinsRestorer.");
            }
        } catch (Exception e) {
            logger.warning("Failed to parse Java version: " + specVersion, e);
        }
    }

    private static int getJavaVersion(String specVersion) {
        String[] split = specVersion.split("\\.");

        String majorVersion;
        if (split.length == 0 || split.length > 2) {
            throw new IllegalArgumentException("Invalid Java version: " + specVersion);
        } else if (split.length == 1) {
            majorVersion = split[0];
        } else if (split[0].equals("1")) {
            majorVersion = split[1];
        } else {
            throw new IllegalArgumentException("Invalid Java version: " + specVersion);
        }

        return Integer.parseInt(majorVersion);
    }

    private void runServiceCheck() {
        ServiceCheckerService.ServiceCheckResponse response = injector.getSingleton(ServiceCheckerService.class).checkServices();

        if (response.getWorkingUUID() == 0 || response.getWorkingProfile() == 0) {
            logger.info("§c[§4Critical§c] ------------------[§2SkinsRestorer §cis §c§l§nOFFLINE§r§c] -------------------------");
            logger.info("§c[§4Critical§c] §cPlugin currently can't fetch new skins due to blocked connection!");
            logger.info("§c[§4Critical§c] §cSee https://skinsrestorer.net/firewall for steps to resolve your issue!");
            logger.info("§c[§4Critical§c] ----------------------------------------------------------------------");
        }
    }

    public String getUserAgent() {
        return String.format(USER_AGENT, BuildData.VERSION, adapter.getPlatform());
    }

    public void shutdown() {
        adapter.shutdownCleanup();
        shutdownHooks.forEach(Runnable::run);
    }
}
