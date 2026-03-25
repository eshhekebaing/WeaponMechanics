package me.deecaad.weaponmechanics.lib;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.deecaad.weaponmechanics.WeaponMechanics;
import me.deecaad.weaponmechanics.WeaponMechanicsAPI;
import me.deecaad.weaponmechanics.utils.CustomTag;
import me.deecaad.weaponmechanics.weapon.reload.ammo.AmmoConfig;
import me.deecaad.weaponmechanics.wrappers.PlayerWrapper;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Регистрирует PlaceholderAPI плейсхолдеры для WeaponMechanics.
 *
 * Оптимизация: данные каждого игрока кэшируются и обновляются каждый тик (50мс)
 * синхронным Bukkit-таском. Это гарантирует, что при скорострельном оружии
 * счётчик патронов меняется плавно (каждый тик), а не "прыгает" через несколько
 * значений из-за внутреннего кэша PAPI.
 *
 * Доступные плейсхолдеры:
 *   %weaponmechanics_ammo_left%       - патроны в магазине
 *   %weaponmechanics_ammo_available%  - патроны в запасе
 *   %weaponmechanics_weapon_title%    - название оружия в руке
 *   %weaponmechanics_is_weapon%       - "true" если в руке оружие WM, иначе "false"
 */
public class WeaponMechanicsPAPIExpansion extends PlaceholderExpansion {

    // ── Структура кэша одного игрока ─────────────────────────────────────
    private static class PlayerCache {
        String  weaponTitle;    // null если не оружие WM
        int     ammoLeft;
        int     ammoAvailable;

        // Заполняется каждый тик — только примитивы, никаких аллокаций
        void update(String title, int left, int available) {
            this.weaponTitle    = title;
            this.ammoLeft       = left;
            this.ammoAvailable  = available;
        }
    }

    // ── Поля класса ───────────────────────────────────────────────────────
    private final WeaponMechanics plugin;

    /** Кэш: UUID игрока → последние известные данные (обновляется каждый тик) */
    private final Map<UUID, PlayerCache> cache = new ConcurrentHashMap<>();

    /** Синхронный repeating-таск, обновляющий кэш каждый тик (period = 1) */
    private BukkitTask updateTask;

    // ── Конструктор ───────────────────────────────────────────────────────
    public WeaponMechanicsPAPIExpansion(WeaponMechanics plugin) {
        this.plugin = plugin;
    }

    // ── PlaceholderExpansion meta ─────────────────────────────────────────
    @Override public @NotNull String getIdentifier() { return "weaponmechanics"; }
    @Override public @NotNull String getAuthor()     { return "DeeCaaD, CJCrafter"; }
    @Override public @NotNull String getVersion()    { return plugin.getDescription().getVersion(); }

    /** Не выгружать при /papi reload */
    @Override public boolean persist() { return true; }

    // ── Жизненный цикл таска ─────────────────────────────────────────────

    /**
     * Вызвать после регистрации expansion, чтобы запустить фоновый таск.
     * Пример: new WeaponMechanicsPAPIExpansion(this).register(); expansion.start();
     */
    public void start() {
        if (updateTask != null) return; // уже запущен

        // period = 1 тик (50 мс) — обновляем каждый тик для точности
        updateTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickUpdate, 1L, 1L);
    }

    /**
     * Вызвать при выгрузке плагина / expansion.
     */
    public void stop() {
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
        cache.clear();
    }

    // ── Логика обновления кэша (каждый тик, main thread) ─────────────────

    private void tickUpdate() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();

            ItemStack mainHand   = player.getInventory().getItemInMainHand();
            String    weaponTitle = WeaponMechanicsAPI.getWeaponTitle(mainHand);

            PlayerCache pc = cache.computeIfAbsent(uuid, k -> new PlayerCache());

            if (weaponTitle == null) {
                // Не оружие — сброс, не тратим время на дальнейшие вычисления
                pc.update(null, 0, 0);
                continue;
            }

            // Патроны в магазине
            int ammoLeft = CustomTag.AMMO_LEFT.getInteger(mainHand);

            // Патроны в запасе
            int ammoAvailable = 0;
            AmmoConfig ammoConfig = plugin.getWeaponConfigurations()
                    .getObject(weaponTitle + ".Reload.Ammo", AmmoConfig.class);
            if (ammoConfig != null) {
                PlayerWrapper wrapper = plugin.getPlayerWrapper(player);
                int magSize = plugin.getWeaponConfigurations()
                        .getInt(weaponTitle + ".Reload.Magazine_Size");
                ammoAvailable = ammoConfig.getMaximumAmmo(mainHand, wrapper, magSize);
            }

            pc.update(weaponTitle, ammoLeft, ammoAvailable);
        }

        // Удаляем кэш вышедших игроков, чтобы не держать мёртвые записи
        cache.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
    }

    // ── onPlaceholderRequest — просто читаем из кэша (без вычислений) ─────

    @Override
    public @Nullable String onPlaceholderRequest(Player player, @NotNull String params) {
        if (player == null) return "";

        PlayerCache pc = cache.get(player.getUniqueId());
        if (pc == null) return ""; // игрок ещё не попал в кэш (первый тик)

        return switch (params) {
            case "is_weapon" -> pc.weaponTitle != null ? "true" : "false";
            case "weapon_title" -> pc.weaponTitle != null ? pc.weaponTitle : "";
            case "ammo_left" -> pc.weaponTitle != null ? String.valueOf(pc.ammoLeft) : "";
            case "ammo_available" -> pc.weaponTitle != null ? String.valueOf(pc.ammoAvailable) : "";
            default -> null;
        };
    }
}