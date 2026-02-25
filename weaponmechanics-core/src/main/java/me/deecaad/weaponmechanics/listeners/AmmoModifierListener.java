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
 * Применяет модификаторы патрона: Damage_Modifier, Velocity_Multiplier,
 * Armor_Penetration (против PDC-пулестойкости из StalkerCore), Fire_Ticks.
 * Порядок приоритетов:
 *   NORMAL — WeaponDamageListener (StalkerCore) считает сопротивление и пишет finalDamage
 *   HIGH   — этот listener читает уже изменённый finalDamage и возвращает часть урона,
 *            срезанную сопротивлением, пропорционально Armor_Penetration патрона.
 */
public class AmmoModifierListener implements Listener {

    /**
     * NamespacedKey пулестойкости из StalkerCore.
     * Передаётся снаружи при регистрации — берётся напрямую из plugin.getBulletResistanceKey().
     * Если null — Armor_Penetration работать не будет (StalkerCore не загружен).
     */
    private final NamespacedKey bulletResistanceKey;

    public AmmoModifierListener(NamespacedKey bulletResistanceKey) {
        this.bulletResistanceKey = bulletResistanceKey;
    }

    // -------------------------------------------------------------------------
    // Shoot — скорость
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPrepareShoot(PrepareWeaponShootEvent event) {
        Ammo ammo = getCurrentAmmo(event.getWeaponTitle(), event.getWeaponStack());
        if (ammo == null) return;

        double mult = ammo.getVelocityMultiplier();
        if (mult != 1.0) {
            event.setProjectileSpeed(event.getProjectileSpeed() * mult);
        }
    }

    // -------------------------------------------------------------------------
    // Damage — урон, пенетрация, поджог
    // -------------------------------------------------------------------------

    /**
     * Приоритет HIGH — выполняется ПОСЛЕ WeaponDamageListener (NORMAL) из StalkerCore,
     * который уже применил пулестойкость и записал finalDamage.
     * Логика Armor_Penetration:
     *   StalkerCore делает: finalDamage = baseDamage * (1 - totalResistance)
     *   Мы знаем baseDamage и totalResistance, восстанавливаем срезанный урон:
     *     lostToArmor = baseDamage - afterArmor
     *     итог        = afterArmor + lostToArmor * penetration
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onWeaponDamage(WeaponDamageEntityEvent event) {
        Ammo ammo = getCurrentAmmo(event.getWeaponTitle(), event.getWeaponStack());
        if (ammo == null) return;

        // --- Damage_Modifier ---
        double dmgMod = ammo.getDamageModifier();
        if (dmgMod != 1.0) {
            event.setBaseDamage(event.getBaseDamage() * dmgMod);
        }

        // --- Armor_Penetration ---
        double pen = ammo.getArmorPenetration();
        if (pen > 0.0 && bulletResistanceKey != null) {
            double totalResistance = getTotalResistance(event.getVictim(), event.getWeaponTitle());

            if (totalResistance > 0.0) {
                double baseDamage  = event.getBaseDamage();
                double afterArmor  = baseDamage * Math.max(0.0, 1.0 - totalResistance);
                double lostToArmor = baseDamage - afterArmor;
                double penetrated  = lostToArmor * pen;
                event.setFinalDamage(afterArmor + penetrated);
            }
        }

        // --- Fire_Ticks ---
        int ammoFire = ammo.getFireTicks();
        if (ammoFire > 0) {
            event.setFireTicks(event.getFireTicks() + ammoFire);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Ammo getCurrentAmmo(String weaponTitle, ItemStack weaponStack) {
        if (weaponStack == null) return null;

        AmmoConfig ammoConfig = WeaponMechanics.getInstance()
                .getWeaponConfigurations()
                .getObject(weaponTitle + ".Reload.Ammo", AmmoConfig.class);

        if (ammoConfig == null) return null;

        return ammoConfig.getCurrentAmmo(weaponStack);
    }

    /**
     * Читает суммарное сопротивление из PDC брони жертвы —
     * то же самое что делает WeaponDamageListener в StalkerCore.
     */
    private double getTotalResistance(LivingEntity victim, String weaponTitle) {
        if (victim.getEquipment() == null) return 0.0;

        double total = 0.0;
        for (ItemStack item : victim.getEquipment().getArmorContents()) {
            if (item == null || !item.hasItemMeta()) continue;

            PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
            String data = pdc.get(bulletResistanceKey, PersistentDataType.STRING);

            if (data != null) {
                total += parseResistance(data, weaponTitle);
            }
        }
        return total;
    }

    /**
     * Парсит строку PDC вида "Base:0.05;AK-47:0.1;SVD:0.0".
     * Конкретное оружие перекрывает Base.
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