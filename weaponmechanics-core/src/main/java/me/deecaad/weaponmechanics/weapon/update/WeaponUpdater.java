package me.deecaad.weaponmechanics.weapon.update;

import me.deecaad.core.file.Configuration;
import me.deecaad.weaponmechanics.WeaponMechanics;
import me.deecaad.weaponmechanics.utils.CustomTag;
import me.deecaad.weaponmechanics.weapon.info.InfoHandler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Отвечает за обновление оружия у игроков после /wm reload или рестарта сервера.
 *
 * <p>Стратегия обновления:
 * <ol>
 *   <li>Берём свежую эталонную копию оружия из конфига (display name, lore, CustomModelData и т.д.)
 *   <li>Находим у игрока предметы с тем же WEAPON_TITLE тегом (NBT)
 *   <li>Переносим изменённые поля (meta), <b>сохраняя</b> персональные данные игрока:
 *       AMMO_LEFT, SELECTIVE_FIRE, FIREARM_ACTION_STATE, AMMO_TYPE_INDEX, ATTACHMENTS, WEAPON_SKIN
 *   <li>Применяем результат обратно в инвентарь
 * </ol>
 *
 * <h2>Известные edge-cases и защита от них</h2>
 * <ul>
 *   <li><b>Игрок офлайн</b> — пропускаем, обновление при следующем входе через {@link WeaponUpdateJoinListener}.
 *   <li><b>Оружие удалено из конфига</b> — предмет не трогаем (weaponTitle больше не регистрирован).
 *   <li><b>Тип предмета изменился</b> — тип тоже обновляем (например, IRON_SWORD → GOLDEN_SWORD).
 *   <li><b>Лаги при большом числе игроков</b> — итерируем через foliaScheduler.runDelayed с батчами.
 *   <li><b>Null ItemMeta</b> — проверяем перед каждой операцией.
 *   <li><b>ConcurrentModification инвентаря</b> — работаем в главном потоке сервера.
 *   <li><b>Endgame стак оружия (amount > 1)</b> — обновляем только meta, количество сохраняем.
 * </ul>
 */
public class WeaponUpdater {

    private static final Logger LOGGER = Logger.getLogger("WeaponMechanics");

    /**
     * Размер батча игроков за один тик.
     * При 100 игроках и BATCH_SIZE=10 получаем 10 тиков (0.5 сек) на полный обход.
     */
    private static final int BATCH_SIZE = 10;

    private WeaponUpdater() {}

    /**
     * Запускает асинхронное поочерёдное обновление всех онлайн-игроков.
     * Вызывается из {@code WeaponMechanics.reload()} после завершения загрузки конфига.
     */
    public static void updateAllOnlinePlayers() {
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (players.isEmpty()) return;

        scheduleNextBatch(players, 0);
    }

    private static void scheduleNextBatch(List<Player> players, int offset) {
        if (offset >= players.size()) return;

        // Запускаем на следующем тике, чтобы не блокировать основной поток reload'а
        WeaponMechanics.getInstance().getFoliaScheduler().global().runDelayed(() -> {
            int end = Math.min(offset + BATCH_SIZE, players.size());

            for (int i = offset; i < end; i++) {
                Player player = players.get(i);
                if (player.isOnline()) {
                    try {
                        updatePlayerWeapons(player);
                    } catch (Exception e) {
                        LOGGER.warning("[WeaponUpdater] Ошибка при обновлении оружия у " + player.getName() + ": " + e.getMessage());
                    }
                }
            }

            // Планируем следующий батч через 1 тик
            if (end < players.size()) {
                scheduleNextBatch(players, end);
            }
        }, 1L);
    }

    /**
     * Обновляет все предметы оружия в инвентаре конкретного игрока.
     * Вызывается также при входе игрока на сервер, если у него есть старые версии оружия.
     *
     * @param player игрок, чей инвентарь нужно обновить
     */
    public static void updatePlayerWeapons(Player player) {
        WeaponMechanics plugin = WeaponMechanics.getInstance();
        InfoHandler infoHandler = plugin.getWeaponHandler().getInfoHandler();
        Configuration weaponConfig = plugin.getWeaponConfigurations();

        ItemStack[] contents = player.getInventory().getContents();
        boolean changed = false;

        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item == null) continue;

            // Получаем weaponTitle из NBT тега — не из meta, чтобы не сломать произвольные предметы
            String weaponTitle = CustomTag.WEAPON_TITLE.getString(item);
            if (weaponTitle == null) continue;

            // Если оружие было удалено из конфига — не трогаем
            if (!infoHandler.hasWeapon(weaponTitle)) continue;

            // Получаем эталонный ItemStack из конфига (уже содержит обновлённый name/lore/model)
            ItemStack template = weaponConfig.getObject(weaponTitle + ".Info.Weapon_Item", ItemStack.class);
            if (template == null) continue;

            // Клонируем шаблон, чтобы не мутировать кешированный объект
            ItemStack updated = template.clone();
            updated.setAmount(item.getAmount());

            // ── Переносим персональные NBT данные игрока ──────────────────────────

            // Патроны в магазине
            if (CustomTag.AMMO_LEFT.hasInteger(item)) {
                CustomTag.AMMO_LEFT.setInteger(updated, CustomTag.AMMO_LEFT.getInteger(item));
            }

            // Режим огня (одиночный / очередью / авто)
            if (CustomTag.SELECTIVE_FIRE.hasInteger(item)) {
                CustomTag.SELECTIVE_FIRE.setInteger(updated, CustomTag.SELECTIVE_FIRE.getInteger(item));
            }

            // Состояние затвора/перезарядки
            if (CustomTag.FIREARM_ACTION_STATE.hasInteger(item)) {
                CustomTag.FIREARM_ACTION_STATE.setInteger(updated, CustomTag.FIREARM_ACTION_STATE.getInteger(item));
            }

            // Индекс типа патронов
            if (CustomTag.AMMO_TYPE_INDEX.hasInteger(item)) {
                CustomTag.AMMO_TYPE_INDEX.setInteger(updated, CustomTag.AMMO_TYPE_INDEX.getInteger(item));
            }

            // Навесное оборудование (WMC/WMPlus attachments)
            if (CustomTag.ATTACHMENTS.hasStringArray(item)) {
                CustomTag.ATTACHMENTS.setStringArray(updated, CustomTag.ATTACHMENTS.getStringArray(item));
            }

            // Кастомный скин оружия (WMC)
            if (CustomTag.WEAPON_SKIN.hasString(item)) {
                CustomTag.WEAPON_SKIN.setString(updated, CustomTag.WEAPON_SKIN.getString(item));
            }

            // ── Переносим durability (если изношенное оружие) ────────────────────
            ItemMeta oldMeta = item.getItemMeta();
            ItemMeta newMeta = updated.getItemMeta();

            if (oldMeta instanceof org.bukkit.inventory.meta.Damageable oldDmg
                    && newMeta instanceof org.bukkit.inventory.meta.Damageable newDmg) {
                newDmg.setDamage(oldDmg.getDamage());
                updated.setItemMeta(newMeta);
            }

            // Сохраняем результат в инвентарь
            contents[slot] = updated;
            changed = true;
        }

        if (changed) {
            player.getInventory().setContents(contents);
            // Обновляем инвентарь для клиента
            player.updateInventory();
        }
    }
}