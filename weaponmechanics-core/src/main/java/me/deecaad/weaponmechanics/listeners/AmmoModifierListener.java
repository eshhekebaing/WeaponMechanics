package me.deecaad.weaponmechanics.listeners;

import me.deecaad.weaponmechanics.WeaponMechanics;
import me.deecaad.weaponmechanics.weapon.reload.ammo.Ammo;
import me.deecaad.weaponmechanics.weapon.reload.ammo.AmmoConfig;
import me.deecaad.weaponmechanics.weapon.weaponevents.PrepareWeaponShootEvent;
import me.deecaad.weaponmechanics.weapon.weaponevents.WeaponDamageEntityEvent;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Применяет модификаторы патрона: Velocity_Multiplier, Armor_Penetration, Fire_Ticks.
 *
 * ВАЖНО: Damage_Modifier патрона применяется в DamageHandler ДО вызова события,
 * поэтому здесь его применять НЕ нужно — иначе он применится дважды.
 *
 * ПОРЯДОК СОБЫТИЙ:
 *   LOWEST  — WeaponDamageListener (StalkerCore) применяет пулестойкость к baseDamage
 *   NORMAL  — этот листенер читает уменьшенный baseDamage и восстанавливает
 *             часть урона пропорционально Armor_Penetration патрона
 *   HIGH+   — DamageHandler считает getFinalDamage() с критами/хэдшотами
 *
 * Armor_Penetration логика (мультипликативная, совпадает с StalkerCore):
 *   StalkerCore: baseDamage *= multiplier  (где multiplier = произведение (1-res) по слотам)
 *   Пенетрация:  восстанавливаем часть срезанного урона:
 *     lostToArmor = originalBase - reducedBase = originalBase * (1 - multiplier)
 *     итог        = reducedBase + lostToArmor * penetration
 *                 = originalBase * multiplier + originalBase * (1 - multiplier) * pen
 *                 = originalBase * (multiplier + (1 - multiplier) * pen)
 *
 * Пример: броня 50%, патрон 100% пенетрации → итог = baseDamage * (0.5 + 0.5*1.0) = baseDamage (броня игнорируется)
 * Пример: броня 50%, патрон 50% пенетрации  → итог = baseDamage * (0.5 + 0.5*0.5) = baseDamage * 0.75
 */
public class AmmoModifierListener implements Listener {

    private static final double EPSILON = 1e-6;

    /**
     * NamespacedKey пулестойкости из StalkerCore.
     * Передаётся снаружи при регистрации — берётся через рефлексию из getBulletResistanceKey().
     * Если null — Armor_Penetration работать не будет (StalkerCore не загружен).
     */
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

    /**
     * NORMAL — выполняется ПОСЛЕ WeaponDamageListener (LOWEST) из StalkerCore,
     * который уже применил пулестойкость к baseDamage через setBaseDamage().
     *
     * НЕ применяем Damage_Modifier здесь — он уже применён в DamageHandler
     * до вызова события (см. DamageHandler.java строка ~69).
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onWeaponDamage(WeaponDamageEntityEvent event) {
        Ammo ammo = getCurrentAmmo(event.getWeaponTitle(), event.getWeaponStack());
        if (ammo == null) return;

        // --- Armor_Penetration ---
        double pen = ammo.getArmorPenetration();
        if (pen > EPSILON && bulletResistanceKey != null) {
            // Считаем мультипликатор брони (то же что StalkerCore)
            double armorMultiplier = calcArmorMultiplier(event.getVictim(), event.getWeaponTitle());
            double totalReduction = 1.0 - armorMultiplier;

            if (totalReduction > EPSILON) {
                // baseDamage уже уменьшен StalkerCore: reducedBase = originalBase * armorMultiplier
                // Восстанавливаем часть срезанного урона:
                //   newMultiplier = armorMultiplier + (1 - armorMultiplier) * pen
                double newMultiplier = armorMultiplier + totalReduction * pen;
                double originalBase  = event.getBaseDamage() / armorMultiplier; // восстанавливаем оригинал
                event.setBaseDamage(originalBase * newMultiplier);
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

    /**
     * Считает мультипликативный множитель пулестойкости брони жертвы —
     * ТОЧНО так же как WeaponDamageListener в StalkerCore.
     * Результат: 1.0 = нет брони, 0.5 = 50% снижения урона.
     */
    private double calcArmorMultiplier(LivingEntity victim, String weaponTitle) {
        if (victim.getEquipment() == null) return 1.0;

        double multiplier = 1.0;
        for (ItemStack item : victim.getEquipment().getArmorContents()) {
            if (item == null || !item.hasItemMeta()) continue;

            PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
            String data = pdc.get(bulletResistanceKey, PersistentDataType.STRING);
            if (data == null) continue;

            double res = parseResistance(data, weaponTitle);
            res = Math.max(0.0, Math.min(res, 1.0));

            if (res > EPSILON) {
                multiplier *= (1.0 - res);
            }
        }
        return multiplier;
    }

    /**
     * Парсит строку PDC вида "Base:0.05;AK-47:0.1;SVD:0.0".
     * Конкретное оружие имеет приоритет над Base.
     */
    private double parseResistance(String data, String weaponTitle) {
        double base = 0.0;
        for (String part : data.split(";")) {
            String[] kv = part.split(":", 2);
            if (kv.length != 2) continue;
            try {
                double value = Double.parseDouble(kv[1].trim());
                String key = kv[0].trim();
                if (key.equals(weaponTitle)) return value;
                if (key.equals("Base")) base = value;
            } catch (NumberFormatException ignored) {}
        }
        return base;
    }
}