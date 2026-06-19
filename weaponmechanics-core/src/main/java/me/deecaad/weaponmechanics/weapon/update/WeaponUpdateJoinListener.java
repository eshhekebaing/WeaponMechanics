package me.deecaad.weaponmechanics.weapon.update;

import me.deecaad.weaponmechanics.WeaponMechanics;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Обновляет оружие у игрока при входе на сервер.
 *
 * <p>Зачем нужен: если /wm reload выполнили пока игрок был офлайн,
 * его оружие в инвентаре не было обновлено. Этот листенер закрывает этот gap.
 *
 * <p>Обновление ставится через 1-тиковую задержку, потому что
 * в момент {@link PlayerJoinEvent} инвентарь игрока ещё может быть
 * не до конца инициализирован на уровне NMS.
 */
public class WeaponUpdateJoinListener implements Listener {

    @EventHandler(priority = EventPriority.LOW)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Задержка 1 тик — ждём полной инициализации инвентаря
        WeaponMechanics.getInstance().getFoliaScheduler().entity(player).runDelayed(
                () -> {
                    if (!player.isOnline()) return;
                    try {
                        WeaponUpdater.updatePlayerWeapons(player);
                    } catch (Exception e) {
                        WeaponMechanics.getInstance().getDebugger()
                                .warning("[WeaponUpdater] Ошибка при входе " + player.getName() + ": " + e.getMessage());
                    }
                },
                1L
        );
    }
}