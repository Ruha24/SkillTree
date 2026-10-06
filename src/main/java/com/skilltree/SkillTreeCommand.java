package com.skilltree;

import com.screenui.ScreenUI;
import com.screenui.Messages;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * {@code /skilltree [дерево]} — открыть/закрыть экран (на вкладке дерева); {@code /skilltree fov} — калибровка
 * кликом, {@code /skilltree fov [30-110|сброс]} — указать свой FOV числом (масштаб экрана зависит от FOV клиента,
 * а его сервер не знает). Подкоманды администратора
 * ({@code skilltree.admin}):
 * <ul>
 *   <li>{@code reload} — перечитать config.yml и trees/*.yml;</li>
 *   <li>{@code reset <ник> [дерево]} — сбросить изученное в дереве или во всех (очки возвращаются, разовые
 *       команды узлов — нет);</li>
 *   <li>{@code points <ник> [дерево [add|take|set <n>]]} — посмотреть или изменить бонусные очки дерева.
 *       Удобно вызывать из других плагинов (квесты, награды): команда работает и из консоли.</li>
 *   <li>{@code learn|unlearn <ник> <дерево> <узел>} — выдать или забрать ранг узла.</li>
 * </ul>
 * Изменения — только игрокам в сети: данные лежат в PDC игрока.
 */
final class SkillTreeCommand implements TabExecutor {

    private static final List<String> ADMIN = List.of("reload", "reset", "points", "learn", "unlearn");
    private static final List<String> FOV_HINTS = List.of("70", "90", "110", "reset");
    private static final List<String> POINT_OPS = List.of("add", "take", "set");
    /** Подкоманды вида {@code <подкоманда> <ник> <дерево> …}. */
    private static final List<String> TARGETED = List.of("reset", "points", "learn", "unlearn");

    private final SkillTreePlugin plugin;

    SkillTreeCommand(SkillTreePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("fov") && sender instanceof Player player) {
            fov(player, args);
            return true;
        }
        if (args.length > 0 && sender.hasPermission("skilltree.admin")) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "reload" -> {
                    reload(sender);
                    return true;
                }
                case "reset" -> {
                    reset(sender, args);
                    return true;
                }
                case "points" -> {
                    points(sender, args);
                    return true;
                }
                case "learn" -> {
                    learn(sender, args, true);
                    return true;
                }
                case "unlearn" -> {
                    learn(sender, args, false);
                    return true;
                }
                default -> {
                    // не подкоманда — дальше, как обычный /skilltree
                }
            }
        }
        if (!(sender instanceof Player player)) {
            return usage(sender, msg().raw("usage.admin"));
        }
        if (args.length > 0 && plugin.tree(args[0].toLowerCase(Locale.ROOT)) == null) {
            error(player, "chat.unknown-tree", "tree", args[0], "trees", String.join(", ", treeIds()));
            return true;
        }
        plugin.toggle(player, args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : null);
        return true;
    }

    /** FOV — забота движка: то же, что /screenui fov (калибровка, число или сброс). */
    private void fov(Player player, String[] args) {
        player.performCommand("screenui " + String.join(" ", args));
    }

    private void reload(CommandSender sender) {
        if (plugin.reload()) {
            ok(sender, "admin.reload-ok", "trees", String.join(", ", treeIds()));
        } else {
            error(sender, "admin.reload-errors");
        }
    }

    private void reset(CommandSender sender, String[] args) {
        if (args.length != 2 && args.length != 3) {
            usage(sender, msg().raw("usage.reset"));
            return;
        }
        List<SkillTree.Definition> which;
        if (args.length == 3) {
            SkillTree.Definition def = treeArg(sender, args[2]);
            if (def == null) {
                return;
            }
            which = List.of(def);
        } else {
            which = plugin.trees();
        }
        onTarget(sender, args[1], target -> {
            if (ScreenUI.session(target) instanceof SkillTreeSession) {
                ScreenUI.close(target); // открытый экран показывал бы старое
            }
            which.forEach(def -> plugin.skills().clear(target, def.id()));
            plugin.effects().sync(target);
            String what = which.size() == 1 ? msg().format("admin.reset-one", "tree", new Messages.Raw(which.getFirst().tab()))
                    : msg().format("admin.reset-all");
            ok(sender, "admin.reset-done", "player", target.getName(), "which", new Messages.Raw(what));
        });
    }

    private void points(CommandSender sender, String[] args) {
        String usage = msg().raw("usage.points");
        if (args.length == 2 || args.length == 3) {
            List<SkillTree.Definition> which;
            if (args.length == 3) {
                SkillTree.Definition def = treeArg(sender, args[2]);
                if (def == null) {
                    return;
                }
                which = List.of(def);
            } else {
                which = plugin.trees();
            }
            onTarget(sender, args[1], target -> {
                int maxLevel = plugin.skills().maxLevel(target);
                ok(sender, "admin.points-header", "player", target.getName(), "level", maxLevel);
                for (SkillTree.Definition def : which) {
                    SkillTree tree = plugin.treeOf(target, def.id());
                    ok(sender, "admin.points-line", "tree", new Messages.Raw(def.tab()), "id", def.id(), "spent", tree.spent(),
                            "total", tree.points(), "base", def.points(), "level_points", def.levelPoints().of(maxLevel),
                            "bonus", plugin.skills().bonus(target, def.id()));
                }
            });
            return;
        }
        // Старый вид без дерева (points <ник> add 5) — если дерево одно.
        String[] a = args;
        if (args.length == 4 && POINT_OPS.contains(args[2].toLowerCase(Locale.ROOT))) {
            if (plugin.trees().size() != 1) {
                error(sender, "admin.points-need-tree", "usage", usage);
                return;
            }
            a = new String[] {args[0], args[1], plugin.trees().getFirst().id(), args[2], args[3]};
        }
        if (a.length != 5 || !POINT_OPS.contains(a[3].toLowerCase(Locale.ROOT))) {
            usage(sender, usage);
            return;
        }
        SkillTree.Definition def = treeArg(sender, a[2]);
        if (def == null) {
            return;
        }
        int n;
        try {
            n = Integer.parseInt(a[4]);
        } catch (NumberFormatException ex) {
            usage(sender, usage);
            return;
        }
        if (n < 0) {
            error(sender, "admin.points-negative");
            return;
        }
        String op = a[3].toLowerCase(Locale.ROOT);
        onTarget(sender, a[1], target -> {
            int old = plugin.skills().bonus(target, def.id());
            int bonus = switch (op) {
                case "add" -> old + n;
                case "take" -> Math.max(0, old - n);
                default -> n;
            };
            plugin.skills().setBonus(target, def.id(), bonus);
            if (ScreenUI.session(target) instanceof SkillTreeSession s) {
                s.pointsChanged();
            }
            ok(sender, "admin.points-changed", "player", target.getName(), "tree", new Messages.Raw(def.tab()), "old", old, "new", bonus);
            if (bonus > old && sender != target) {
                int got = bonus - old;
                target.sendMessage(msg().get("chat.points-received", NamedTextColor.GOLD, "count", got,
                        "points", msg().plural("points", got), "tree", new Messages.Raw(def.tab()), "id", def.id()));
            }
        });
    }

    /**
     * {@code learn}: следующий ранг узла в обход родителей, уровня и права — очки тратятся как обычно, команды
     * узла выполняются, как при изучении. {@code unlearn}: забрать ранг; забытый совсем узел уносит потомков,
     * которым он был нужен. Открытый экран игрока пересобирается — иначе он показывал бы старое.
     */
    private void learn(CommandSender sender, String[] args, boolean learn) {
        if (args.length != 4) {
            usage(sender, msg().raw(learn ? "usage.learn" : "usage.unlearn"));
            return;
        }
        SkillTree.Definition def = treeArg(sender, args[2]);
        if (def == null) {
            return;
        }
        SkillTree.Node node = def.node(args[3]);
        if (node == null) {
            error(sender, "admin.unknown-node", "node", args[3], "tree", new Messages.Raw(def.tab()),
                    "nodes", String.join(", ", nodeIds(def)));
            return;
        }
        Messages.Raw name = new Messages.Raw(node.name());
        if (node.isStart()) {
            error(sender, "admin.node-start", "name", name);
            return;
        }
        onTarget(sender, args[1], target -> {
            SkillTree tree = plugin.treeOf(target, def.id());
            List<SkillTree.Node> cascade = List.of();
            if (learn) {
                SkillTree.Lock lock = tree.grant(node);
                if (lock == SkillTree.Lock.LEARNED) {
                    error(sender, "admin.learn-maxed", "player", target.getName(), "name", name);
                    return;
                }
                if (lock == SkillTree.Lock.POINTS) {
                    error(sender, "admin.learn-points", "player", target.getName(), "name", name, "cost", node.cost(),
                            "free", tree.points() - tree.spent(), "tree", def.id());
                    return;
                }
                plugin.skills().save(target, def.id(), tree.learned());
                plugin.effects().learned(target, def, node, tree.rank(node));
            } else {
                if (!tree.isAllocated(node)) {
                    error(sender, "admin.unlearn-none", "player", target.getName(), "name", name);
                    return;
                }
                cascade = tree.revoke(node);
                plugin.skills().save(target, def.id(), tree.learned());
                plugin.effects().sync(target);
            }
            if (ScreenUI.session(target) instanceof SkillTreeSession) {
                ScreenUI.reopen(target);
            }
            ok(sender, learn ? "admin.learn-done" : "admin.unlearn-done", "player", target.getName(), "name", name,
                    "rank", tree.rank(node), "ranks", node.ranks(), "tree", new Messages.Raw(def.tab()));
            if (!cascade.isEmpty()) {
                String names = cascade.stream()
                        .map(n -> msg().format("state.parent", "name", new Messages.Raw(n.name())))
                        .collect(Collectors.joining(", "));
                ok(sender, "admin.unlearn-cascade", "nodes", new Messages.Raw(names));
            }
            if (learn && sender != target) {
                target.sendMessage(msg().get("chat.node-granted", NamedTextColor.GOLD, "name", name,
                        "rank", tree.rank(node), "ranks", node.ranks(), "tree", new Messages.Raw(def.tab())));
            }
        });
    }

    /** id узлов дерева, которые можно выдать или забрать (без стартовых). */
    private static List<String> nodeIds(SkillTree.Definition def) {
        return def.nodes().stream().filter(n -> !n.isStart()).map(SkillTree.Node::id).toList();
    }

    /** Дерево по id из аргумента команды; null — нет такого (сообщение уже отправлено). */
    private SkillTree.Definition treeArg(CommandSender sender, String id) {
        SkillTree.Definition def = plugin.tree(id.toLowerCase(Locale.ROOT));
        if (def == null) {
            error(sender, "chat.unknown-tree", "tree", id, "trees", String.join(", ", treeIds()));
        }
        return def;
    }

    private List<String> treeIds() {
        return plugin.trees().stream().map(SkillTree.Definition::id).toList();
    }

    /** Действие над игроком в сети — на его потоке (Folia: PDC и экран игрока трогаются только там). */
    private void onTarget(CommandSender sender, String name, Consumer<Player> action) {
        Player target = plugin.getServer().getPlayerExact(name);
        if (target == null) {
            error(sender, "admin.not-online", "player", name);
            return;
        }
        target.getScheduler().run(plugin, t -> action.accept(target), null);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        String sub = args[0].toLowerCase(Locale.ROOT);
        boolean admin = sender.hasPermission("skilltree.admin");
        List<String> options = new ArrayList<>();
        switch (args.length) {
            case 1 -> {
                options.add("fov");
                options.addAll(treeIds());
                if (admin) {
                    options.addAll(ADMIN);
                }
            }
            case 2 -> {
                if (sub.equals("fov")) {
                    options.addAll(FOV_HINTS);
                } else if (admin && TARGETED.contains(sub)) {
                    plugin.getServer().getOnlinePlayers().forEach(p -> options.add(p.getName()));
                }
            }
            case 3 -> {
                if (admin && TARGETED.contains(sub)) {
                    options.addAll(treeIds());
                }
            }
            case 4 -> {
                if (admin && sub.equals("points")) {
                    options.addAll(POINT_OPS);
                } else if (admin && (sub.equals("learn") || sub.equals("unlearn"))) {
                    SkillTree.Definition def = plugin.tree(args[2].toLowerCase(Locale.ROOT));
                    if (def != null) {
                        options.addAll(nodeIds(def));
                    }
                }
            }
            default -> {
            }
        }
        return filter(options, args[args.length - 1]);
    }

    private static List<String> filter(List<String> options, String typed) {
        String prefix = typed.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }

    private Messages msg() {
        return plugin.messages();
    }

    private boolean usage(CommandSender sender, String usage) {
        error(sender, "admin.usage", "usage", usage);
        return true;
    }

    /** Сообщение из messages.yml, зелёное по умолчанию. */
    private void ok(CommandSender sender, String key, Object... kv) {
        sender.sendMessage(msg().get(key, NamedTextColor.GREEN, kv));
    }

    /** Сообщение из messages.yml, красное по умолчанию. */
    private void error(CommandSender sender, String key, Object... kv) {
        sender.sendMessage(msg().get(key, NamedTextColor.RED, kv));
    }
}
