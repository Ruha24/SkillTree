package com.skilltree;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLevelChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/** Эффекты навыков на игроке и очки за уровни опыта. Экран и его ввод — забота движка ScreenUI. */
final class SkillListener implements Listener {

    private final SkillTreePlugin plugin;

    SkillListener(SkillTreePlugin plugin) {
        this.plugin = plugin;
    }

    /** Права навыков сервер не сохраняет — ставим заново; уровень мог вырасти, пока плагина не было. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.effects().sync(event.getPlayer());
        plugin.levelReached(event.getPlayer(), event.getPlayer().getLevel());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.effects().forget(event.getPlayer());
    }

    /** Очки навыков за уровни опыта. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onLevel(PlayerLevelChangeEvent event) {
        plugin.levelReached(event.getPlayer(), event.getNewLevel());
    }

    /** После смерти модификаторы атрибутов могут не перейти на нового игрока — проверяем. */
    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        player.getScheduler().run(plugin, t -> plugin.effects().sync(player), null);
    }
}
