package me.deecaad.weaponmechanics.weapon.reload.ammo;

import me.deecaad.core.file.SerializeData;
import me.deecaad.core.file.Serializer;
import me.deecaad.core.file.SerializerException;
import me.deecaad.core.file.serializers.ItemSerializer;
import me.deecaad.weaponmechanics.utils.CustomTag;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;

public class Ammo implements Serializer<Ammo> {

    private String ammoTitle;
    private String symbol;
    private IAmmoType type;

    // Custom ammo modifiers
    private double damageModifier;
    private double velocityMultiplier;
    private double armorPenetration;
    private int fireTicks;

    /**
     * Default constructor for serializer.
     */
    public Ammo() {
    }

    public Ammo(@NotNull String ammoTitle, @Nullable String symbol, @NotNull IAmmoType type,
                double damageModifier, double velocityMultiplier, double armorPenetration, int fireTicks) {
        this.ammoTitle = ammoTitle;
        this.symbol = symbol;
        this.type = type;
        this.damageModifier = damageModifier;
        this.velocityMultiplier = velocityMultiplier;
        this.armorPenetration = armorPenetration;
        this.fireTicks = fireTicks;
    }

    public @NotNull String getAmmoTitle() {
        return ammoTitle;
    }

    public @Nullable String getSymbol() {
        return symbol;
    }

    public @NotNull String getDisplay() {
        return symbol == null ? ammoTitle : symbol;
    }

    public @NotNull IAmmoType getType() {
        return type;
    }

    /**
     * Multiplier applied to base damage when this ammo is used (default 1.0).
     */
    public double getDamageModifier() {
        return damageModifier;
    }

    /**
     * Multiplier applied to projectile speed when this ammo is used (default 1.0).
     */
    public double getVelocityMultiplier() {
        return velocityMultiplier;
    }

    /**
     * Fraction of armor defense bypassed (0.0 = no bypass, 1.0 = full bypass).
     */
    public double getArmorPenetration() {
        return armorPenetration;
    }

    /**
     * Additional fire ticks applied on hit (stacks with weapon's own Fire_Ticks).
     */
    public int getFireTicks() {
        return fireTicks;
    }

    @NotNull @Override
    public Ammo serialize(@NotNull SerializeData data) throws SerializerException {
        String[] split = data.getKey().split("\\.");
        String ammoTitle = split[split.length - 1];
        String symbol = data.of("Symbol").get(String.class).orElse(null);

        if (data.has("Ammo_Types")) {
            throw data.exception("Ammo_Types", "Ammo_Types is outdated since WeaponMechanics 3.0.0",
                    "In order to use Ammo, you should update your configs. Check the wiki for more info:",
                    "https://cjcrafter.gitbook.io/weaponmechanics/weapon-modules/reload/ammo");
        }

        // Ammo can use 1 of Experience, Money, or Items.
        int count = 0;
        if (data.has("Experience_As_Ammo_Cost"))
            count++;
        if (data.has("Money_As_Ammo_Cost"))
            count++;
        if (data.has("Item_Ammo"))
            count++;

        if (count < 1) {
            throw data.exception(null, "Tried to create an Ammo without any cost. Try adding 'Item_Ammo' as a cost");
        }
        if (count > 1) {
            throw data.exception(null, "Tried to create an Ammo with multiple costs. Try using just 'Item_Ammo'");
        }

        IAmmoType ammoType = null;

        OptionalInt experienceCost = data.of("Experience_As_Ammo_Cost").assertRange(1, null).getInt();
        if (experienceCost.isPresent()) {
            ammoType = new ExperienceAmmo(experienceCost.getAsInt());
        }

        OptionalInt moneyCost = data.of("Money_As_Ammo_Cost").assertRange(1, null).getInt();
        if (moneyCost.isPresent()) {
            ammoType = new MoneyAmmo(moneyCost.getAsInt());
        }

        if (data.has("Item_Ammo")) {
            ItemStack bulletItem = null;
            ItemStack magazineItem = null;

            // Items with NBT tags added have to be serialized in a special order,
            // otherwise the crafted item will be missing the NBT tag.
            if (data.has("Item_Ammo.Bullet_Item")) {
                Map<String, Object> tags = Map.of(CustomTag.AMMO_TITLE.getKey(), ammoTitle);
                bulletItem = new ItemSerializer().serializeWithTags(data.move("Item_Ammo.Bullet_Item"), tags);
            }

            // Items with NBT tags added have to be serialized in a special order,
            // otherwise the crafted item will be missing the NBT tag.
            if (data.has("Item_Ammo.Magazine_Item")) {
                Map<String, Object> tags = Map.of(CustomTag.AMMO_TITLE.getKey(), ammoTitle, CustomTag.AMMO_MAGAZINE.getKey(), 1);
                magazineItem = new ItemSerializer().serializeWithTags(data.move("Item_Ammo.Magazine_Item"), tags);
            }

            if (magazineItem == null && bulletItem == null) {
                throw data.exception(null, "Missing both 'Bullet_Item' and 'Magazine_Item' for your ammo... Use at least 1 of them!");
            }

            AmmoConverter ammoConverter = (AmmoConverter) data.of("Item_Ammo.Ammo_Converter_Check").serialize(AmmoConverter.class).orElse(null);
            ammoType = new ItemAmmo(ammoTitle, bulletItem, magazineItem, ammoConverter);
        }

        if (ammoType == null) {
            throw data.exception(null, "Something went wrong... Check your Ammo config to make sure it is correct!");
        }

        double damageModifier = data.of("Damage_Modifier").assertRange(0.0, null).getDouble().orElse(1.0);
        double velocityMultiplier = data.of("Velocity_Multiplier").assertRange(0.01, null).getDouble().orElse(1.0);
        double armorPenetration = data.of("Armor_Penetration").assertRange(0.0, 1.0).getDouble().orElse(0.0);
        int fireTicks = data.of("Fire_Ticks").assertRange(0, null).getInt().orElse(0);

        return new Ammo(ammoTitle, symbol, ammoType, damageModifier, velocityMultiplier, armorPenetration, fireTicks);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        Ammo ammo = (Ammo) o;
        return Objects.equals(ammoTitle, ammo.ammoTitle);
    }

    @Override
    public int hashCode() {
        return ammoTitle.hashCode();
    }
}