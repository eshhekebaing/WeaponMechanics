package me.deecaad.weaponmechanics.listeners;

import me.deecaad.weaponmechanics.WeaponMechanics;
import me.deecaad.weaponmechanics.weapon.reload.ammo.Ammo;
import me.deecaad.weaponmechanics.weapon.reload.ammo.AmmoConfig;
import me.deecaad.weaponmechanics.weapon.weaponevents.PrepareWeaponShootEvent;
import me.deecaad.weaponmechanics.weapon.weaponevents.WeaponDamageEntityEvent;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Применяет модификаторы патрона: Velocity_Multiplier, Armor_Penetration, Fire_Ticks.
 * <p>
 * Damage_Modifier применяется в DamageHandler ДО вызова события — здесь не трогаем.
 * <p>
 * ПОРЯДОК СОБЫТИЙ:
 *   LOWEST  — WeaponDamageListener (StalkerCore) применяет пулестойкость к baseDamage
 *             и кладёт данные в SHARED_ORIGINAL_BASE_MAP через putOriginalBase()
 *   NORMAL  — этот листенер читает originalBase из карты и применяет пенетрацию
 *   HIGH+   — DamageHandler считает getFinalDamage() с критами/хэдшотами
 * <p>
 * FIX #1 (главный): Убрано обратное вычисление originalBase через деление на armorMultiplier.
 *   Старая логика: originalBase = reducedBase / armorMultiplier
 *   Проблема: calcArmorMultiplier() не учитывал hpMultiplier из формулы StalkerCore.
 *   При 200HP (hpMultiplier=2.0) и resistance=200:
 *     StalkerCore reductionFactor = 200/((100+200)*2.0) = 0.333 -> reducedBase = orig*0.667
 *     calcArmorMultiplier: multiplier = 100/(100+200) = 0.333
 *     "восстановление": orig*0.667 / 0.333 = orig*2.0  <- вдвое больше реального!
 *   Итог: AP-патроны наносили неверный (завышенный) урон.
 *   Решение: StalkerCore сам кладёт originalBase в SHARED_ORIGINAL_BASE_MAP, берём оттуда.
 * <p>
 * FIX #2: Восстановление critChance после setBaseDamage().
 *   setBaseDamage() в WM API сбрасывает wasCritical — без восстановления critChance
 *   критические попадания с AP-патронами не работали совсем.
 * <p>
 * FIX #3: TTL-очистка SHARED_ORIGINAL_BASE_MAP.
 *   Если событие отменялось между LOWEST и NORMAL — записи накапливались вечно (утечка памяти).
 *   Теперь evictStaleEntries() вызывается из StalkerCore каждые 10 сек через scheduler.
 */
public class AmmoModifierListener implements Listener {

    private static final double EPSILON = 1e-6;

    /**
     * FIX #1: Статическая общая карта между этим классом (форк WM) и WeaponDamageListener (StalkerCore).
     * <p>
     * Почему static: StalkerCore не имеет compile-time зависимости на форк WM (только на API),
     * поэтому передать экземпляр через конструктор нельзя. Static-поле доступно из StalkerCore
     * через прямой импорт класса AmmoModifierListener — он виден т.к. форк WM является зависимостью.
     * <p>
     * WeaponDamageListener (StalkerCore) вызывает AmmoModifierListener.putOriginalBase(key, ...)
     * на LOWEST, этот класс читает и удаляет запись на NORMAL.
     * <p>
     * Ключ: "UUID::weaponTitle" — уникален для каждой пары жертва+оружие.
     * Значение: double[] { originalBase, reductionFactor, totalResistance, effectiveHP }
     */
    public static final Map<String, double[]> SHARED_ORIGINAL_BASE_MAP = new ConcurrentHashMap<>();

    /** FIX #3: Метки времени вставки для TTL-очистки. */
    private static final Map<String, Long> INSERT_TIMESTAMPS = new ConcurrentHashMap<>();
    private static final long ENTRY_TTL_MS = 5_000L;

    /**
     * Статический метод — вызывается из WeaponDamageListener (StalkerCore) на LOWEST.
     *
     * @param mapKey         "UUID::weaponTitle"
     * @param originalBase   baseDamage ДО применения пулестойкости StalkerCore
     * @param reductionFactor доля срезанного урона (0..1), = totalResistance / effectiveHP
     * @param totalResistance суммарная пулестойкость (для лога/отладки)
     * @param effectiveHP    приведённое HP (для лога/отладки)
     */
    public static void putOriginalBase(String mapKey, double originalBase,
                                       double reductionFactor, double totalResistance, double effectiveHP) {
        SHARED_ORIGINAL_BASE_MAP.put(mapKey, new double[]{ originalBase, reductionFactor, totalResistance, effectiveHP });
        INSERT_TIMESTAMPS.put(mapKey, System.currentTimeMillis());
    }

    /**
     * FIX #3: TTL-очистка. Вызывается из StalkerCore scheduler каждые 10 сек.
     * Удаляет записи старше 5 сек — они точно уже не будут прочитаны NORMAL-обработчиком.
     */
    public static void evictStaleEntries() {
        long now = System.currentTimeMillis();
        INSERT_TIMESTAMPS.entrySet().removeIf(e -> {
            if (now - e.getValue() > ENTRY_TTL_MS) {
                SHARED_ORIGINAL_BASE_MAP.remove(e.getKey());
                return true;
            }
            return false;
        });
    }

    // bulletResistanceKey оставлен чтобы не менять сигнатуру конструктора
    // и не трогать WeaponMechanics.java / resolveBulletResistanceKey().
    // Больше не используется внутри для вычислений.
    private final NamespacedKey bulletResistanceKey;

    public AmmoModifierListener(NamespacedKey bulletResistanceKey) {
        this.bulletResistanceKey = bulletResistanceKey;
    }

    // ──────────────────────────────────────────────────────────────
    // Shoot — скорость пули
    // ──────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPrepareShoot(PrepareWeaponShootEvent event) {
        Ammo ammo = getCurrentAmmo(event.getWeaponTitle(), event.getWeaponStack());
        if (ammo == null) return;

        double mult = ammo.getVelocityMultiplier();
        if (mult != 1.0) {
            event.setProjectileSpeed(event.getProjectileSpeed() * mult);
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Damage — пенетрация брони и поджог
    // ──────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onWeaponDamage(WeaponDamageEntityEvent event) {
        Ammo ammo = getCurrentAmmo(event.getWeaponTitle(), event.getWeaponStack());
        if (ammo == null) return;

        // --- Armor_Penetration ---
        double pen = ammo.getArmorPenetration();
        if (pen > EPSILON) {
            String mapKey = event.getVictim().getUniqueId() + "::" + event.getWeaponTitle();

            // FIX #1: берём originalBase из карты StalkerCore — точный оригинал без деления.
            // Если записи нет — у жертвы не было брони с пулестойкостью, пенетрация не нужна.
            double[] data = SHARED_ORIGINAL_BASE_MAP.remove(mapKey);
            INSERT_TIMESTAMPS.remove(mapKey);

            if (data != null) {
                double originalBase    = data[0];
                double reductionFactor = data[1];
                double armorMultiplier = 1.0 - reductionFactor;

                // Восстанавливаем часть срезанного урона пропорционально пенетрации:
                //   newMultiplier = armorMultiplier + (1 - armorMultiplier) * pen
                // pen=1.0 → броня полностью игнорируется
                // pen=0.5 → половина срезанного урона возвращается
                // pen=0.0 → поведение без пенетрации
                double newMultiplier = armorMultiplier + reductionFactor * pen;

                // FIX #2: сохраняем critChance — setBaseDamage() его сбрасывает в WM API
                double savedCritChance = event.getCritChance();
                event.setBaseDamage(originalBase * newMultiplier);
                event.setCritChance(savedCritChance);
            }
        }

        // --- Fire_Ticks ---
        int ammoFire = ammo.getFireTicks();
        if (ammoFire > 0) {
            event.setFireTicks(event.getFireTicks() + ammoFire);
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────

    private Ammo getCurrentAmmo(String weaponTitle, ItemStack weaponStack) {
        if (weaponStack == null) return null;

        AmmoConfig ammoConfig = WeaponMechanics.getInstance()
                .getWeaponConfigurations()
                .getObject(weaponTitle + ".Reload.Ammo", AmmoConfig.class);

        if (ammoConfig == null) return null;

        return ammoConfig.getCurrentAmmo(weaponStack);
    }
}