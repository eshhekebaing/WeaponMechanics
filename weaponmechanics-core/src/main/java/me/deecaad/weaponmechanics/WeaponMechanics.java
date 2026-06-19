package me.deecaad.weaponmechanics;

import com.cjcrafter.foliascheduler.TaskImplementation;
import com.cjcrafter.foliascheduler.util.ConstructorInvoker;
import com.cjcrafter.foliascheduler.util.ReflectionUtil;
import com.cjcrafter.foliascheduler.util.ServerVersions;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.EventManager;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import kotlin.UninitializedPropertyAccessException;
import me.deecaad.core.MechanicsCore;
import me.deecaad.core.MechanicsPlugin;
import me.deecaad.core.compatibility.CompatibilityAPI;
import me.deecaad.core.compatibility.worldguard.WorldGuardCompatibility;
import me.deecaad.core.database.Database;
import me.deecaad.core.database.MySQL;
import me.deecaad.core.database.SQLite;
import me.deecaad.core.events.QueueSerializerEvent;
import me.deecaad.core.file.*;
import me.deecaad.core.mechanics.Conditions;
import me.deecaad.core.mechanics.Mechanics;
import me.deecaad.core.mechanics.Targeters;
import me.deecaad.core.mechanics.conditions.Condition;
import me.deecaad.core.mechanics.defaultmechanics.Mechanic;
import me.deecaad.core.mechanics.targeters.Targeter;
import me.deecaad.core.utils.FileUtil;
import me.deecaad.weaponmechanics.lib.MythicMobsLoader;
import me.deecaad.weaponmechanics.lib.WeaponMechanicsPAPIExpansion;
import me.deecaad.weaponmechanics.listeners.ExplosionInteractionListeners;
import me.deecaad.weaponmechanics.listeners.AmmoModifierListener;
import me.deecaad.weaponmechanics.listeners.ResourcePackListener;
import me.deecaad.weaponmechanics.listeners.WeaponListeners;
import me.deecaad.weaponmechanics.listeners.trigger.TriggerEntityListeners;
import me.deecaad.weaponmechanics.listeners.trigger.TriggerPlayerListeners;
import me.deecaad.weaponmechanics.packetlisteners.OutAbilitiesListener;
import me.deecaad.weaponmechanics.packetlisteners.OutEntityEffectListener;
import me.deecaad.weaponmechanics.packetlisteners.OutRemoveEntityEffectListener;
import me.deecaad.weaponmechanics.packetlisteners.OutSetSlotBobFix;
import me.deecaad.weaponmechanics.WeaponMechanicsAPI;
import me.deecaad.weaponmechanics.utils.CustomTag;
import me.deecaad.weaponmechanics.weapon.WeaponHandler;
import me.deecaad.weaponmechanics.weapon.WeaponSerializer;
import me.deecaad.weaponmechanics.weapon.damage.BlockDamageData;
import me.deecaad.weaponmechanics.weapon.placeholders.WeaponPlaceholderHandlers;
import me.deecaad.weaponmechanics.weapon.projectile.FoliaProjectileSpawner;
import me.deecaad.weaponmechanics.weapon.projectile.ProjectileSpawner;
import me.deecaad.weaponmechanics.weapon.projectile.SpigotProjectileSpawner;
import me.deecaad.weaponmechanics.weapon.projectile.weaponprojectile.Projectile;
import me.deecaad.weaponmechanics.weapon.reload.ammo.Ammo;
import me.deecaad.weaponmechanics.weapon.stats.PlayerStat;
import me.deecaad.weaponmechanics.weapon.stats.WeaponStat;
import me.deecaad.weaponmechanics.wrappers.EntityWrapper;
import me.deecaad.weaponmechanics.wrappers.PlayerWrapper;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.Bukkit;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.jar.JarFile;

public class WeaponMechanics extends MechanicsPlugin {

    private static @Nullable WeaponMechanics INSTANCE;

    private final @NotNull Map<LivingEntity, EntityWrapper> entityWrappers = new HashMap<>();
    private @Nullable WeaponHandler weaponHandler;
    private @Nullable ResourcePackListener resourcePackListener;
    private @Nullable ProjectileSpawner projectileSpawner;
    private @Nullable Database database;
    private @Nullable WeaponMechanicsPAPIExpansion papiExpansion;

    private @NotNull Configuration ammoConfigurations = new FastConfiguration();
    private @NotNull Configuration projectileConfigurations = new FastConfiguration();
    private @NotNull Configuration weaponConfigurations = new FastConfiguration();

    public WeaponMechanics() {
        super(Style.style(NamedTextColor.GOLD), Style.style(NamedTextColor.GRAY), 14323);
    }

    @Override
    public void onLoad() {
        INSTANCE = this;
        super.onLoad();

        // Make sure our "extension" registries are loaded (these statements have the side effect
        // of loading the class)
        WeaponPlaceholderHandlers.WEAPON_TITLE.getKey();

        // Register all WorldGuard flags
        WorldGuardCompatibility guard = CompatibilityAPI.getWorldGuardCompatibility();
        if (guard.isInstalled()) {
            debugger.info("Detected WorldGuard, registering flags");
            guard.registerFlag("weapon-shoot", WorldGuardCompatibility.FlagType.STATE_FLAG);
            guard.registerFlag("weapon-shoot-message", WorldGuardCompatibility.FlagType.STRING_FLAG);
            guard.registerFlag("weapon-explode", WorldGuardCompatibility.FlagType.STATE_FLAG);
            guard.registerFlag("weapon-explode-message", WorldGuardCompatibility.FlagType.STRING_FLAG);
            guard.registerFlag("weapon-break-block", WorldGuardCompatibility.FlagType.STATE_FLAG);
            guard.registerFlag("weapon-damage", WorldGuardCompatibility.FlagType.STATE_FLAG);
            guard.registerFlag("weapon-damage-message", WorldGuardCompatibility.FlagType.STRING_FLAG);
        } else {
            debugger.fine("No WorldGuard detected!");
        }

        try {
            JarSearcher searcher = new JarSearcher(new JarFile(getFile()));
            searcher.findAllSubclasses(Mechanic.class, getClassLoader(), SearchMode.ON_DEMAND)
                    .stream()
                    .map(ReflectionUtil::getConstructor)
                    .map(ConstructorInvoker::newInstance)
                    .forEach(Mechanics.REGISTRY::add);
            searcher.findAllSubclasses(Targeter.class, getClassLoader(), SearchMode.ON_DEMAND)
                    .stream()
                    .map(ReflectionUtil::getConstructor)
                    .map(ConstructorInvoker::newInstance)
                    .forEach(Targeters.REGISTRY::add);
            searcher.findAllSubclasses(Condition.class, getClassLoader(), SearchMode.ON_DEMAND)
                    .stream()
                    .map(ReflectionUtil::getConstructor)
                    .map(ConstructorInvoker::newInstance)
                    .forEach(Conditions.REGISTRY::add);
        } catch (Throwable ex) {
            debugger.severe("Failed to load mechanics/targeters/conditions", ex);
        }
    }

    @Override
    public void onEnable() {
        super.onEnable();

        handleDatabase();
        handleResourcePack();  // no need to .join() on this... let it run in background

        // Shameless self-promotion
        if (Bukkit.getPluginManager().getPlugin("WeaponMechanicsCosmetics") == null)
            debugger.info("Buy WeaponMechanicsCosmetics to support our development: https://pluginify.org/resources/13/");
        if (Bukkit.getPluginManager().getPlugin("WeaponMechanicsPlus") == null)
            debugger.info("Buy WeaponMechanicsPlus to support our development: https://pluginify.org/resources/14/");
    }

    @Override
    public @NotNull CompletableFuture<Void> handleCommands() {
        WeaponMechanicsCommand.registerCommands();
        return super.handleCommands();
    }

    public @NotNull CompletableFuture<Void> handleResourcePack() {
        if (!getConfig().getBoolean("Resource_Pack_Download.Enabled"))
            return CompletableFuture.completedFuture(null);

        File pack = new File(getDataFolder(), "WeaponMechanicsResourcePack.zip");
        if (pack.exists())
            return CompletableFuture.completedFuture(null);

        return CompletableFuture.runAsync(() -> {
            String link = configuration.getString("Resource_Pack_Download.Link");
            int connectionTimeout = configuration.getInt("Resource_Pack_Download.Connection_Timeout");
            int readTimeout = configuration.getInt("Resource_Pack_Download.Read_Timeout");

            ResourcePackListener listener = this.resourcePackListener;
            if (listener == null) {
                debugger.warning("ResourcePackListener is not initialized! Cannot download resource pack.");
                return;
            }

            try {
                if ("LATEST".equals(link))
                    link = resourcePackListener.getResourcePackLink();

                FileUtil.downloadFile(pack, link, connectionTimeout, readTimeout);
            } catch (Exception e) {
                // Wrapping in CompletionException causes the returned CompletableFuture to complete exceptionally.
                throw new CompletionException("Error downloading resource pack from " + link, e);
            }
        });
    }

    @Override
    public @NotNull CompletableFuture<Void> handleConfigs() {
        // Look for all serializers/validators in the jar
        List<Serializer<?>> serializers;
        List<IValidator> validators;
        try {
            serializers = new SerializerInstancer(new JarFile(getFile())).createAllInstances(getClassLoader(), SearchMode.ON_DEMAND);
            validators = new JarInstancer(new JarFile(getFile())).createAllInstances(IValidator.class, getClassLoader(), SearchMode.ON_DEMAND);
        } catch (IOException ex) {
            debugger.severe("Failed to load validators/serializers", ex);
            return CompletableFuture.completedFuture(null);
        }

        // Let other plugins register serializers/validators
        QueueSerializerEvent event = new QueueSerializerEvent(this, getDataFolder());
        event.addSerializers(serializers);
        event.addValidators(validators);
        Bukkit.getPluginManager().callEvent(event);
        serializers = event.getSerializers();
        validators = event.getValidators();

        ammoConfigurations = new RootFileReader<>(this, Ammo.class, "ammos")
                .withSerializers(serializers)
                .withValidators(validators)
                .assertFiles()
                .read();
        long ammos = ammoConfigurations.values().stream().filter(Ammo.class::isInstance).count();
        debugger.info("Loaded " + ammos + " ammos");

        projectileConfigurations = new RootFileReader<>(this, Projectile.class, "projectiles")
                .withSerializers(serializers)
                .withValidators(validators)
                .assertFiles()
                .read();
        long projectiles = projectileConfigurations.values().stream().filter(Projectile.class::isInstance).count();
        debugger.info("Loaded " + projectiles + " projectiles");

        weaponConfigurations = new RootFileReader<>(this, WeaponSerializer.class, "weapons")
                .withSerializers(serializers)
                .withValidators(validators)
                .assertFiles()
                .read();
        long weapons = weaponConfigurations.values().stream().filter(WeaponSerializer.class::isInstance).count();
        debugger.info("Loaded " + weapons + " weapons");

        registerWeaponPermissions(true);

        return CompletableFuture.completedFuture(null);
    }

    public @NotNull CompletableFuture<Void> handleDatabase() {
        if (!configuration.getBoolean("Database.Enable", true))
            return CompletableFuture.completedFuture(null);

        debugger.fine("Setting up database");
        String databaseType = configuration.getString("Database.Type", "SQLITE");
        if ("SQLITE".equalsIgnoreCase(databaseType)) {
            String absolutePath = configuration.getString("Database.SQLite.Absolute_Path", "plugins/WeaponMechanics/weaponmechanics.db");
            try {
                database = new SQLite(absolutePath);
            } catch (IOException | SQLException e) {
                debugger.warning("Failed to initialized database!", e);
                return CompletableFuture.failedFuture(new CompletionException("Failed to initialize SQLite database", e));
            }
        } else {
            String hostname = configuration.getString("Database.MySQL.Hostname", "localhost");
            int port = configuration.getInt("Database.MySQL.Port", 3306);
            String databaseName = configuration.getString("Database.MySQL.Database", "weaponmechanics");
            String username = configuration.getString("Database.MySQL.Username", "root");
            String password = configuration.getString("Database.MySQL.Password", "");
            database = new MySQL(hostname, port, databaseName, username, password);
        }
        database.executeUpdate(true, PlayerStat.getCreateTableString(), WeaponStat.getCreateTableString());
        return CompletableFuture.completedFuture(null);
    }

    @NotNull
    @Override
    public CompletableFuture<Void> handleListeners() {
        debugger.fine("Registering listeners");

        PluginManager pm = Bukkit.getPluginManager();
        pm.registerEvents(new TriggerPlayerListeners(weaponHandler), this);
        pm.registerEvents(new TriggerEntityListeners(weaponHandler), this);
        pm.registerEvents(new WeaponListeners(weaponHandler), this);
        pm.registerEvents(new ExplosionInteractionListeners(), this);

        pm.registerEvents(new AmmoModifierListener(resolveBulletResistanceKey(pm)), this);

        // Update weapon items for players who joined after a reload/restart
        pm.registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.LOW)
            public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) {
                Player joined = e.getPlayer();
                // Delay 1 tick so the inventory is fully loaded before we touch it
                foliaScheduler.entity(joined).runDelayed(() -> {
                    if (joined.isOnline()) updateWeaponsForPlayer(joined);
                }, 1L);
            }
        }, WeaponMechanics.this);

        if (resourcePackListener == null) {
            debugger.warning("Failed to register ResourcePackListener... users will not automatically download the resource pack!");
        } else {
            pm.registerEvents(resourcePackListener, this);
        }

        if (pm.isPluginEnabled("MythicMobs")) {
            PluginDescriptionFile desc = Objects.requireNonNull(pm.getPlugin("MythicMobs")).getDescription();
            if (!desc.getVersion().split("\\.")[0].contains("5")) {
                debugger.warning("Could not hook into MythicMobs because it is outdated");
            } else {
                pm.registerEvents(new MythicMobsLoader(), this);
                debugger.info("Hooked in MythicMobs " + desc.getVersion());
            }
        }

        if (pm.isPluginEnabled("PlaceholderAPI")) {
            papiExpansion = new WeaponMechanicsPAPIExpansion(this);
            papiExpansion.register();
            debugger.info("Hooked into PlaceholderAPI");
        }

        return super.handleListeners();
    }

    /**
     * Ищет StalkerCore через PluginManager и получает его NamespacedKey пулестойкости
     * через рефлексию — без какой-либо compile-time зависимости на StalkerCore.
     * Если плагин не найден или метод переименован — возвращает null,
     * и Armor_Penetration просто не работает.
     */
    private @Nullable org.bukkit.NamespacedKey resolveBulletResistanceKey(org.bukkit.plugin.PluginManager pm) {
        org.bukkit.plugin.Plugin stalker = pm.getPlugin("StalkerCore");
        if (stalker == null) {
            debugger.info("StalkerCore not found — Armor_Penetration disabled");
            return null;
        }
        try {
            java.lang.reflect.Method method = stalker.getClass().getMethod("getBulletResistanceKey");
            Object result = method.invoke(stalker);
            if (result instanceof org.bukkit.NamespacedKey key) {
                debugger.info("Hooked into StalkerCore — Armor_Penetration enabled");
                return key;
            }
        } catch (NoSuchMethodException e) {
            debugger.warning("StalkerCore found but getBulletResistanceKey() not found — Armor_Penetration disabled");
        } catch (Exception e) {
            debugger.warning("Failed to get BulletResistanceKey from StalkerCore: " + e.getMessage());
        }
        return null;
    }

    @Override
    public @NotNull CompletableFuture<Void> handlePacketListeners() {
        debugger.fine("Creating packet listeners");

        EventManager em = PacketEvents.getAPI().getEventManager();
        em.registerListener(new OutAbilitiesListener(), PacketListenerPriority.NORMAL);
        em.registerListener(new OutEntityEffectListener(), PacketListenerPriority.NORMAL);
        em.registerListener(new OutRemoveEntityEffectListener(), PacketListenerPriority.NORMAL);
        if (configuration.getBoolean("Fix_Bobbing_Legacy", false)) {
            debugger.info("Enabling legacy bobbing fix for clients older than 1.21.4");
            em.registerListener(new OutSetSlotBobFix(this), PacketListenerPriority.NORMAL);
        }
        return super.handlePacketListeners();
    }

    @Override
    public @NotNull CompletableFuture<Void> handlePermissions() {
        debugger.fine("Registering permissions");
        registerWeaponPermissions(false);
        return super.handlePermissions();
    }

    private void registerWeaponPermissions(boolean reCalculateOnlinePlayers) {
        if (weaponHandler == null) return;

        PluginManager pm = Bukkit.getPluginManager();

        Permission parent = pm.getPermission("weaponmechanics.use.*");
        if (parent == null) {
            parent = new Permission("weaponmechanics.use.*", "Allows using all WeaponMechanics weapons", PermissionDefault.OP);
            pm.addPermission(parent);
        }

        for (String weaponTitle : weaponHandler.getInfoHandler().getSortedWeaponList()) {
            String node = "weaponmechanics.use." + weaponTitle;

            Permission perm = pm.getPermission(node);
            if (perm == null) {
                perm = new Permission(node, "Permission to use " + weaponTitle, PermissionDefault.FALSE);
                pm.addPermission(perm);
            }

            perm.addParent(parent, true);
        }

        if (reCalculateOnlinePlayers) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.recalculatePermissions();
            }
        }
    }

    @Override
    public @NotNull CompletableFuture<Void> handleMetrics() {
        Metrics metrics = getMetrics();
        if (metrics == null) {
            return CompletableFuture.completedFuture(null);
        }

        debugger.fine("Registering bStats");

        // Tracks the number of weapons that are used in the plugin. Since each
        // server uses a relatively random number of weapons, we should track
        // ranges of weapons (As in, <10, >10 & <20, >20 & <30, etc). This way,
        // the pie chart will look tolerable.
        // https://bstats.org/help/custom-charts
        metrics.addCustomChart(new SimplePie("registered_weapons", () -> {
            int weapons = weaponHandler.getInfoHandler().getSortedWeaponList().size();

            if (weapons <= 10) {
                return "0-10";
            } else if (weapons <= 20) {
                return "11-20";
            } else if (weapons <= 30) {
                return "21-30";
            } else if (weapons <= 50) {
                return "31-50";
            } else if (weapons <= 100) {
                return "51-100";
            } else {
                return ">100";
            }
        }));

        metrics.addCustomChart(new SimplePie("core_version", () -> MechanicsCore.getInstance().getDescription().getVersion()));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void init() {
        super.init();

        // Cancel running tasks for wrappers before clearing (defensive redundancy)
        for (EntityWrapper ew : entityWrappers.values()) {
            TaskImplementation<Void> moveTask = ew.getMoveTask();
            if (moveTask != null) moveTask.cancel();
            ew.getMainHandData().cancelTasks();
            ew.getOffHandData().cancelTasks();
        }
        entityWrappers.clear();

        // Initialize core objects (recreate to ensure a clean state)
        weaponHandler = new WeaponHandler();
        resourcePackListener = new ResourcePackListener();
        if (ServerVersions.isFolia()) {
            projectileSpawner = new FoliaProjectileSpawner(this);
        } else {
            projectileSpawner = new SpigotProjectileSpawner(this);
        }
    }


    @Override
    public @NotNull CompletableFuture<TaskImplementation<Void>> reload() {
        for (EntityWrapper entity : entityWrappers.values()) {
            TaskImplementation<Void> moveTask = entity.getMoveTask();
            if (moveTask != null)
                moveTask.cancel();
        }

        return MechanicsCore.getInstance().reload()
                .thenCompose((ignore) -> super.reload())
                .thenCompose((ignore) -> handleDatabase())
                .thenCompose((ignore) -> {
                    if (weaponHandler == null) {
                        debugger.severe("WeaponHandler is null after reload! This should never happen.");
                        return CompletableFuture.failedFuture(new IllegalStateException("WeaponHandler is null after reload!"));
                    }

                    // Make sure each online player has a wrapper
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        PlayerWrapper playerWrapper = getPlayerWrapper(player);
                        weaponHandler.getStatsHandler().load(playerWrapper);
                    }

                    // Update weapon items in all online players' inventories
                    // so changes in config (lore, name, model data, etc.) take effect immediately
                    updateWeaponsInInventories();

                    return CompletableFuture.completedFuture(null);
                });
    }

    /**
     * After /wm reload or server start, iterates over all online players' inventories
     * and replaces each WeaponMechanics weapon item with a freshly generated one
     * (updated lore, name, model data, etc. from the new config), while preserving
     * all per-item NBT state:
     * <ul>
     *   <li>{@link CustomTag#AMMO_LEFT}            – bullets currently in the magazine</li>
     *   <li>{@link CustomTag#AMMO_TYPE_INDEX}      – which ammo type is loaded</li>
     *   <li>{@link CustomTag#SELECTIVE_FIRE}       – burst / auto / single-fire mode</li>
     *   <li>{@link CustomTag#FIREARM_ACTION_STATE} – open/closed bolt state</li>
     *   <li>{@link CustomTag#ATTACHMENTS}          – attached WMPlus attachments</li>
     *   <li>{@link CustomTag#WEAPON_SKIN}          – cosmetic skin override (WMC)</li>
     *   <li>Item damage (durability)               – current weapon wear</li>
     * </ul>
     * Slots that do not contain a WM weapon are left untouched.
     * Players who were offline during reload will be updated via {@link #updateWeaponsForPlayer(Player)}.
     */
    private void updateWeaponsInInventories() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            updateWeaponsForPlayer(player);
        }
        debugger.info("Updated weapon items in online players' inventories after reload");
    }

    /**
     * Updates all WM weapon items in a single player's inventory.
     * Safe to call on join (via listener) to catch players who were offline during reload.
     *
     * @param player the player whose inventory should be updated
     */
    public void updateWeaponsForPlayer(@NotNull Player player) {
        PlayerInventory inv = player.getInventory();
        boolean changed = false;

        // getSize() returns 41 (0-35 main + 36-39 armor + 40 off-hand)
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack old = inv.getItem(i);
            if (old == null) continue;

            String weaponTitle = CustomTag.WEAPON_TITLE.getString(old);
            if (weaponTitle == null) continue;

            // Weapon may have been removed from config — leave item as-is
            if (!getWeaponHandler().getInfoHandler().hasWeapon(weaponTitle)) continue;

            // ----- Save NBT state from the old item -----
            int      ammoLeft      = CustomTag.AMMO_LEFT.hasInteger(old)            ? CustomTag.AMMO_LEFT.getInteger(old)            : -1;
            int      ammoTypeIdx   = CustomTag.AMMO_TYPE_INDEX.hasInteger(old)      ? CustomTag.AMMO_TYPE_INDEX.getInteger(old)      : -1;
            int      selectiveFire = CustomTag.SELECTIVE_FIRE.hasInteger(old)       ? CustomTag.SELECTIVE_FIRE.getInteger(old)       : -1;
            int      firearmState  = CustomTag.FIREARM_ACTION_STATE.hasInteger(old) ? CustomTag.FIREARM_ACTION_STATE.getInteger(old) : -1;
            String[] attachments   = CustomTag.ATTACHMENTS.hasStringArray(old)      ? CustomTag.ATTACHMENTS.getStringArray(old)      : null;
            String   weaponSkin    = CustomTag.WEAPON_SKIN.hasString(old)           ? CustomTag.WEAPON_SKIN.getString(old)           : null;

            // Preserve durability (item wear)
            int oldDamage = -1;
            if (old.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable dmg && dmg.hasDamage()) {
                oldDamage = dmg.getDamage();
            }

            // ----- Generate fresh item (picks up all config changes) -----
            ItemStack updated = WeaponMechanicsAPI.generateWeapon(weaponTitle);
            // generateWeapon() can return null if weaponTitle is misconfigured
            if (updated == null) continue;

            updated.setAmount(old.getAmount());

            // ----- Restore NBT state onto new item -----
            if (ammoLeft      >= 0)  CustomTag.AMMO_LEFT.setInteger(updated, ammoLeft);
            if (ammoTypeIdx   >= 0)  CustomTag.AMMO_TYPE_INDEX.setInteger(updated, ammoTypeIdx);
            if (selectiveFire >= 0)  CustomTag.SELECTIVE_FIRE.setInteger(updated, selectiveFire);
            if (firearmState  >= 0)  CustomTag.FIREARM_ACTION_STATE.setInteger(updated, firearmState);
            if (attachments   != null) CustomTag.ATTACHMENTS.setStringArray(updated, attachments);
            if (weaponSkin    != null) CustomTag.WEAPON_SKIN.setString(updated, weaponSkin);

            // Restore durability
            if (oldDamage >= 0 && updated.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable newDmg) {
                newDmg.setDamage(oldDamage);
                updated.setItemMeta(newDmg);
            }

            inv.setItem(i, updated);
            changed = true;
        }

        if (changed) {
            player.updateInventory();
        }
    }

    @Override
    public void onDisable() {
        // Try to unregister events, just in case this is a reload and not a
        // full plugin disable (in which case this would be done already).
        HandlerList.unregisterAll(this);

        // This won't cancel move tasks on Folia... We have to do it manually
        foliaScheduler.cancelTasks();
        for (EntityWrapper entityWrapper : entityWrappers.values()) {
            TaskImplementation<Void> moveTask = entityWrapper.getMoveTask();
            if (moveTask != null)
                moveTask.cancel();
            entityWrapper.getMainHandData().cancelTasks();
            entityWrapper.getOffHandData().cancelTasks();
        }

        BlockDamageData.regenerateAll();

        // Close database and save data in SYNC
        if (database != null && weaponHandler != null) {
            for (EntityWrapper entityWrapper : entityWrappers.values()) {
                if (!entityWrapper.isPlayer())
                    continue;
                weaponHandler.getStatsHandler().save((PlayerWrapper) entityWrapper, true);
            }
            try {
                database.close();
            } catch (SQLException e) {
                debugger.warning("Couldn't close database properly...", e);
            }
        }

        if (papiExpansion != null) {
            papiExpansion = null;
        }
        database = null;
        weaponHandler = null;
        entityWrappers.clear();
        weaponConfigurations.clear();
        ammoConfigurations.clear();
        projectileConfigurations.clear();
        projectileSpawner = null;
    }

    public @NotNull WeaponHandler getWeaponHandler() {
        if (weaponHandler == null)
            throw new UninitializedPropertyAccessException();
        return weaponHandler;
    }

    public @NotNull Database getDatabase() {
        if (database == null)
            throw new UninitializedPropertyAccessException();
        return database;
    }

    public @NotNull ProjectileSpawner getProjectileSpawner() {
        if (projectileSpawner == null)
            throw new UninitializedPropertyAccessException();
        return projectileSpawner;
    }

    public @NotNull Configuration getAmmoConfigurations() {
        return ammoConfigurations;
    }

    public @NotNull Configuration getProjectileConfigurations() {
        return projectileConfigurations;
    }

    public @NotNull Configuration getWeaponConfigurations() {
        return weaponConfigurations;
    }

    public static @NotNull WeaponMechanics getInstance() {
        return INSTANCE;
    }

    /**
     * This method can't return null because new EntityWrapper is created if not found.
     *
     * @param entity the entity wrapper to get
     * @return the entity wrapper
     */
    public @NotNull EntityWrapper getEntityWrapper(@NotNull LivingEntity entity) {
        if (entity.getType() == EntityType.PLAYER) {
            return getPlayerWrapper((Player) entity);
        }
        return getEntityWrapper(entity, false);
    }

    /**
     * This method will return null if no auto add is set to true and EntityWrapper is not found. If no
     * auto add is false then new EntityWrapper is automatically created if not found and returned by
     * this method.
     *
     * @param entity    the entity
     * @param noAutoAdd true means that EntityWrapper wont be automatically added if not found
     * @return the entity wrapper or null if no auto add is true and EntityWrapper was not found
     */
    public @Nullable EntityWrapper getEntityWrapper(@NotNull LivingEntity entity, boolean noAutoAdd) {
        if (entity.getType() == EntityType.PLAYER) {
            return getPlayerWrapper((Player) entity);
        }
        EntityWrapper wrapper = entityWrappers.get(entity);
        if (wrapper == null) {
            if (noAutoAdd) {
                return null;
            }
            wrapper = new EntityWrapper(entity);
            entityWrappers.put(entity, wrapper);
        }
        return wrapper;
    }

    /**
     * This method can't return null because new PlayerWrapper is created if not found. Use mainly
     * getEntityWrapper() instead of this unless you especially need something from PlayerWrapper.
     *
     * @param player the player wrapper to get
     * @return the player wrapper
     */
    public @NotNull PlayerWrapper getPlayerWrapper(@NotNull Player player) {
        EntityWrapper wrapper = entityWrappers.get(player);
        if (wrapper == null) {
            PlayerWrapper pw = new PlayerWrapper(player);
            entityWrappers.put(player, pw);
            return pw;
        }
        if (!(wrapper instanceof PlayerWrapper pw)) {
            // Exception is better in this case as we need to know where this mistake happened
            throw new IllegalArgumentException("Tried to get PlayerWrapper from player which didn't have PlayerWrapper (only EntityWrapper)...?");
        }
        if (pw.getPlayer() != player) {
            removeEntityWrapper(player);
            pw = new PlayerWrapper(player);
            entityWrappers.put(player, pw);
        }
        return pw;
    }

    /**
     * Removes entity (and player) wrapper and all of its content. Move task is also cancelled.
     *
     * @param entity the entity (or player)
     */
    public void removeEntityWrapper(@NotNull LivingEntity entity) {
        EntityWrapper oldWrapper = entityWrappers.remove(entity);
        if (oldWrapper != null) {
            TaskImplementation<Void> oldMoveTask = oldWrapper.getMoveTask();
            if (oldMoveTask != null) {
                oldMoveTask.cancel();
            }
            oldWrapper.getMainHandData().cancelTasks();
            oldWrapper.getOffHandData().cancelTasks();
        }
    }
}