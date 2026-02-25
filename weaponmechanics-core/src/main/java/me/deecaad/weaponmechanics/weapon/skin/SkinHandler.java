package me.deecaad.weaponmechanics.weapon.skin;

import me.deecaad.weaponmechanics.WeaponMechanics;
import me.deecaad.weaponmechanics.utils.CustomTag;
import me.deecaad.weaponmechanics.weapon.WeaponHandler;
import me.deecaad.weaponmechanics.weapon.trigger.TriggerType;
import me.deecaad.weaponmechanics.weapon.weaponevents.WeaponSkinEvent;
import me.deecaad.weaponmechanics.wrappers.EntityWrapper;
import me.deecaad.weaponmechanics.wrappers.HandData;
import org.bukkit.Bukkit;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public class SkinHandler {

    private WeaponHandler weaponHandler;

    public SkinHandler(WeaponHandler weaponHandler) {
        this.weaponHandler = weaponHandler;
    }

    /**
     * Вызывается после каждого выстрела.
     * Через 250мс обновляет скин — чтобы сбросить Shoot когда игрок стоит и не двигается.
     */
    public void scheduleShootSkinReset(EntityWrapper entityWrapper, String weaponTitle, ItemStack weaponStack, EquipmentSlot slot) {
        SkinSelector skins = WeaponMechanics.getInstance().getWeaponConfigurations().getObject(weaponTitle + ".Skin", SkinSelector.class);
        if (skins == null || !skins.hasAction(null, SkinSelector.SkinAction.SHOOT))
            return;

        // 250мс = 5 тиков — чуть больше чем isRightClicking() таймер (215мс)
        Bukkit.getScheduler().runTaskLater(WeaponMechanics.getInstance(), () -> {
            tryUse(entityWrapper, weaponTitle, weaponStack, slot);
        }, 5L);
    }

    public boolean tryUse(EntityWrapper entityWrapper, String weaponTitle, ItemStack weaponStack, EquipmentSlot slot) {
        return tryUse(null, entityWrapper, weaponTitle, weaponStack, slot, false);
    }

    public boolean tryUse(EntityWrapper entityWrapper, String weaponTitle, ItemStack weaponStack, EquipmentSlot slot, boolean forceDefault) {
        return tryUse(null, entityWrapper, weaponTitle, weaponStack, slot, forceDefault);
    }

    public boolean tryUse(TriggerType triggerType, EntityWrapper entityWrapper, String weaponTitle, ItemStack weaponStack, EquipmentSlot slot) {
        return tryUse(triggerType, entityWrapper, weaponTitle, weaponStack, slot, false);
    }

    public boolean tryUse(TriggerType triggerType, EntityWrapper entityWrapper, String weaponTitle, ItemStack weaponStack, EquipmentSlot slot, boolean forceDefault) {
        HandData hand = slot == EquipmentSlot.HAND ? entityWrapper.getMainHandData() : entityWrapper.getOffHandData();
        SkinSelector skins = WeaponMechanics.getInstance().getWeaponConfigurations().getObject(weaponTitle + ".Skin", SkinSelector.class);
        if (skins == null || !weaponStack.hasItemMeta())
            return false;

        WeaponSkinEvent event = new WeaponSkinEvent(weaponTitle, weaponStack, entityWrapper.getEntity(), slot, skins, triggerType, forceDefault);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled())
            return false;

        SkinSelector.SkinAction action = getSkinAction(skins, event.getSkin(), hand, weaponStack, triggerType, forceDefault);
        String[] attachments = CustomTag.ATTACHMENTS.getStringArray(weaponStack);
        skins.apply(weaponStack, event.getSkin(), action, attachments);

        return false;
    }

    public SkinSelector.SkinAction getSkinAction(SkinSelector skins, String skin, HandData hand, ItemStack weaponStack, TriggerType triggerType) {
        return getSkinAction(skins, skin, hand, weaponStack, triggerType, false);
    }

    public SkinSelector.SkinAction getSkinAction(SkinSelector skins, String skin, HandData hand, ItemStack weaponStack, TriggerType triggerType, boolean forceDefault) {
        // This is used usually when dequipping the weapon
        if (forceDefault)
            return SkinSelector.SkinAction.DEFAULT;

        EntityWrapper entityWrapper = hand.getEntityWrapper();

        if ((!hand.isReloading() || !skins.hasAction(skin, SkinSelector.SkinAction.RELOAD)) && CustomTag.AMMO_LEFT.getInteger(weaponStack) == 0) {
            if (skins.hasAction(skin, SkinSelector.SkinAction.NO_AMMO))
                return SkinSelector.SkinAction.NO_AMMO;
        }

        if (hand.getZoomData().isZooming()) {

            SkinSelector.SkinAction stackAction = new SkinSelector.SkinAction("Scope_" + hand.getZoomData().getZoomStacks());
            if (skins.hasAction(skin, stackAction))
                return stackAction;

            if (skins.hasAction(skin, SkinSelector.SkinAction.SCOPE))
                return SkinSelector.SkinAction.SCOPE;
        }

        if (hand.isReloading() && skins.hasAction(skin, SkinSelector.SkinAction.RELOAD))
            return SkinSelector.SkinAction.RELOAD;

        // Checks are like this due to when PlayerToggleSprintEvent is called player isn't yet actually
        // sprinting
        // since the event is also cancellable. This ignores the cancelling of sprint event,
        // it doesn't do anything if its cancelled anyway :p
        // + disable when dual wielding ++ don't even try when its END_SPRINT
        // Shoot — перебивает Sprint
        if (entityWrapper.isRightClicking() && skins.hasAction(skin, SkinSelector.SkinAction.SHOOT))
            return SkinSelector.SkinAction.SHOOT;

        if (triggerType != TriggerType.END_SPRINT
                && (entityWrapper.isSprinting() || triggerType == TriggerType.START_SPRINT)
                && !entityWrapper.isDualWieldingWeapons()) {

            if (skins.hasAction(skin, SkinSelector.SkinAction.SPRINT))
                return SkinSelector.SkinAction.SPRINT;
        }

        return SkinSelector.SkinAction.DEFAULT;
    }
}