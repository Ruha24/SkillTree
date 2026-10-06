package com.skilltree;

import com.nexomc.nexo.api.NexoItems;
import com.nexomc.nexo.api.events.NexoItemsLoadedEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

/**
 * Иконки узлов из предметов Nexo ({@code icon: nexo:<id>}). Nexo — необязательная зависимость: класс
 * трогает его API, только если плагин включён, иначе {@link NexoItems} даже не загружается.
 */
final class NexoIcons {

    private NexoIcons() {
    }

    static boolean available() {
        return Bukkit.getPluginManager().isPluginEnabled("Nexo");
    }

    static boolean exists(String id) {
        return available() && Api.exists(id);
    }

    /**
     * Nexo загружает предметы сам, бывает и позже нас: когда загрузит (и после /nexo reload) — перечитать
     * деревья, чтобы иконки nexo: проверились по настоящему списку.
     */
    static void listen(SkillTreePlugin plugin) {
        if (available()) {
            Bukkit.getPluginManager().registerEvents(new Api.Loaded(plugin), plugin);
        }
    }

    /** Предмет Nexo по id; null — Nexo нет или такого предмета нет. */
    static ItemStack item(String id) {
        return available() ? Api.item(id) : null;
    }

    /** Отдельный класс: JVM загрузит NexoItems, только когда сюда реально зайдут (а это — лишь при Nexo). */
    private static final class Api {
        static boolean exists(String id) {
            // Предметы ещё не загружены — не ругаемся: проверим, когда придёт NexoItemsLoadedEvent.
            return NexoItems.itemNames().isEmpty() || NexoItems.exists(id);
        }

        static ItemStack item(String id) {
            var builder = NexoItems.itemFromId(id);
            return builder == null ? null : builder.build();
        }

        record Loaded(SkillTreePlugin plugin) implements Listener {
            @EventHandler
            public void onLoaded(NexoItemsLoadedEvent event) {
                plugin.reloadTrees();
            }
        }
    }
}
