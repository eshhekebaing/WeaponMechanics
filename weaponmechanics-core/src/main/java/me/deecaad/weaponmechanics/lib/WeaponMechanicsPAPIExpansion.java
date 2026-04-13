package me.deecaad.weaponmechanics.lib;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.deecaad.weaponmechanics.WeaponMechanics;
import me.deecaad.weaponmechanics.WeaponMechanicsAPI;
import me.deecaad.weaponmechanics.utils.CustomTag;
import me.deecaad.weaponmechanics.weapon.reload.ammo.AmmoConfig;
import me.deecaad.weaponmechanics.wrappers.PlayerWrapper;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Регистрирует PlaceholderAPI плейсхолдеры для WeaponMechanics.
 * Значения читаются моментально при каждом запросе — без кэша и без таска.
 * Доступные плейсхолдеры:
 *   %weaponmechanics_ammo_left%       - патроны в магазине
 *   %weaponmechanics_ammo_available%  - патроны в запасе
 *   %weaponmechanics_weapon_title%    - название оружия в руке
 *   %weaponmechanics_is_weapon%       - "true" если в руке оружие WM, иначе "false"
 */
public class WeaponMechanicsPAPIExpansion extends PlaceholderExpansion {

    private final WeaponMechanics plugin;

    public WeaponMechanicsPAPIExpansion(WeaponMechanics plugin) {
        this.plugin = plugin;
    }

    @Override public @NotNull String getIdentifier() { return "weaponmechanics"; }
    @Override public @NotNull String getAuthor()     { return "DeeCaaD, CJCrafter"; }
    @Override public @NotNull String getVersion()    { return plugin.getDescription().getVersion(); }

    @Override public boolean persist() { return true; }

    @Override
    public @Nullable String onPlaceholderRequest(Player player, @NotNull String params) {
        if (player == null) return "";

        ItemStack mainHand = player.getInventory().getItemInMainHand();
        String weaponTitle = WeaponMechanicsAPI.getWeaponTitle(mainHand);

        return switch (params) {
            case "is_weapon" -> Boolean.toString(weaponTitle != null);
            case "weapon_title" -> weaponTitle != null ? weaponTitle : "";
            case "ammo_left" -> {
                if (weaponTitle == null) yield "";
                yield String.valueOf(CustomTag.AMMO_LEFT.getInteger(mainHand));
            }
            case "ammo_available" -> {
                if (weaponTitle == null) yield "";
                AmmoConfig ammoConfig = plugin.getWeaponConfigurations()
                        .getObject(weaponTitle + ".Reload.Ammo", AmmoConfig.class);
                if (ammoConfig == null) yield "0";
                PlayerWrapper wrapper = plugin.getPlayerWrapper(player);
                int magSize = plugin.getWeaponConfigurations()
                        .getInt(weaponTitle + ".Reload.Magazine_Size");
                yield String.valueOf(ammoConfig.getMaximumAmmo(mainHand, wrapper, magSize));
            }
            default -> null;
        };
    }
}