package me.deecaad.weaponmechanics.weapon.placeholders;

import me.deecaad.core.placeholder.PlaceholderData;
import me.deecaad.core.placeholder.PlaceholderHandler;
import me.deecaad.weaponmechanics.WeaponMechanics;
import me.deecaad.weaponmechanics.utils.CustomTag;
import me.deecaad.weaponmechanics.weapon.reload.ammo.AmmoConfig;
import me.deecaad.weaponmechanics.wrappers.PlayerWrapper;
import org.bukkit.NamespacedKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Placeholder <ammo_left_display>
 *
 * Если у оружия заданы патроны (Ammo):
 *   Показывает "30 | 300"  — патроны в магазине | патроны в инвентаре
 *   Если в инвентаре 0:    "30 | 0"
 *
 * Если патроны НЕ заданы:
 *   Показывает просто "30"
 */
public class PAmmoLeftDisplay extends PlaceholderHandler {

    public PAmmoLeftDisplay() {
    }

    @Override
    public @NotNull NamespacedKey getKey() {
        return new NamespacedKey(WeaponMechanics.getInstance(), "ammo_left_display");
    }

    @Override
    public @Nullable String onRequest(@NotNull PlaceholderData data) {
        if (data.item() == null || data.itemTitle() == null)
            return null;

        // Сколько патронов в магазине прямо сейчас
        int ammoLeft = CustomTag.AMMO_LEFT.getInteger(data.item());

        // Проверяем заданы ли патроны (Ammo) для этого оружия
        AmmoConfig ammoConfig = WeaponMechanics.getInstance()
                .getWeaponConfigurations()
                .getObject(data.itemTitle() + ".Reload.Ammo", AmmoConfig.class);

        // Если патроны не заданы — просто показываем количество в магазине
        if (ammoConfig == null || data.player() == null) {
            return String.valueOf(ammoLeft);
        }

        // Если патроны заданы — считаем сколько их в инвентаре
        PlayerWrapper playerWrapper = WeaponMechanics.getInstance().getPlayerWrapper(data.player());
        int magazineSize = WeaponMechanics.getInstance()
                .getWeaponConfigurations()
                .getInt(data.itemTitle() + ".Reload.Magazine_Size");

        int ammoInInventory = ammoConfig.getMaximumAmmo(data.item(), playerWrapper, magazineSize);

        return ammoLeft + " | " + ammoInInventory;
    }
}
