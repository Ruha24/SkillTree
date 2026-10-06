package com.skilltree;

import java.util.Locale;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Плейсхолдеры PlaceholderAPI {@code %skilltree_…%} — для табло, чата, меню других плагинов. Только для
 * игроков в сети (данные лежат в их PDC); для остальных — пустая строка. Регистрируется, только если
 * PlaceholderAPI стоит на сервере: без него этот класс не загружается.
 *
 * <p>Деревьев несколько: {@code %skilltree_<дерево>_points_free%} — про конкретное, без дерева
 * ({@code %skilltree_points_free%}) — про первое по порядку вкладок.</p>
 *
 * <ul>
 *   <li>{@code points_total}, {@code points_spent}, {@code points_free} — очков всего, потрачено, свободно;</li>
 *   <li>{@code points_base}, {@code points_level}, {@code points_bonus} — откуда очки: из конфига, за уровни, выданные;</li>
 *   <li>{@code max_level} — максимальный достигнутый уровень; {@code next_point_level} — на каком уровне
 *       следующее очко (пусто, если больше не будет);</li>
 *   <li>{@code learned} — изучено узлов, {@code nodes} — всего узлов (без стартовых);</li>
 *   <li>{@code learned_<id>} — изучен ли узел (хотя бы ранг 1): true/false; {@code rank_<id>} — изученный ранг;</li>
 *   <li>{@code respec_cost} — цена сброса в уровнях (пусто, если сброс выключен).</li>
 * </ul>
 */
final class SkillPlaceholders extends PlaceholderExpansion {

    private final SkillTreePlugin plugin;

    SkillPlaceholders(SkillTreePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "skilltree";
    }

    @Override
    public @NotNull String getAuthor() {
        return String.join(", ", plugin.getPluginMeta().getAuthors());
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    /** Не выгружаться при /papi reload — расширение встроено в плагин. */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer offline, @NotNull String params) {
        if (offline == null || !(offline.getPlayer() instanceof Player player)) {
            return "";
        }
        String p = params.toLowerCase(Locale.ROOT);
        int maxLevel = plugin.skills().maxLevel(player);
        if (p.equals("max_level")) {
            return String.valueOf(maxLevel);
        }
        // «<дерево>_…» — про это дерево, иначе про первое.
        SkillTree.Definition def = null;
        for (SkillTree.Definition d : plugin.trees()) {
            if (p.startsWith(d.id() + "_")) {
                def = d;
                p = p.substring(d.id().length() + 1);
                break;
            }
        }
        if (def == null) {
            if (plugin.trees().isEmpty()) {
                return "";
            }
            def = plugin.trees().getFirst();
        }
        SkillTree tree = plugin.treeOf(player, def.id());
        if (p.startsWith("rank_")) {
            SkillTree.Node node = def.node(p.substring("rank_".length()));
            return node == null ? "" : String.valueOf(tree.rank(node));
        }
        if (p.startsWith("learned_")) {
            SkillTree.Node node = def.node(p.substring("learned_".length()));
            return node == null ? "" : String.valueOf(tree.isAllocated(node));
        }
        return switch (p) {
            case "points_total" -> String.valueOf(tree.points());
            case "points_spent" -> String.valueOf(tree.spent());
            case "points_free" -> String.valueOf(Math.max(0, tree.points() - tree.spent()));
            case "points_base" -> String.valueOf(def.points());
            case "points_level" -> String.valueOf(def.levelPoints().of(maxLevel));
            case "points_bonus" -> String.valueOf(plugin.skills().bonus(player, def.id()));
            case "next_point_level" -> {
                int next = def.levelPoints().nextLevel(maxLevel);
                yield next < 0 ? "" : String.valueOf(next);
            }
            case "learned" -> String.valueOf(tree.learned().keySet().stream().filter(id -> tree.definition().node(id) != null).count());
            case "nodes" -> String.valueOf(def.nodes().stream().filter(n -> !n.isStart()).count());
            case "respec_cost" -> def.respec() == null ? "" : String.valueOf(def.respec().cost(tree.spent()));
            default -> null; // неизвестный плейсхолдер — PlaceholderAPI оставит его как есть
        };
    }
}
