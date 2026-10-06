package com.skilltree;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Дерево навыков из {@code trees/<id>.yml} ({@link Definition}) и изученное в нём одним игроком
 * (хранится в {@link SkillStore}).
 */
public final class SkillTree {

    /**
     * {@code gx, gy} — клетка сетки (y вверх); {@code parents} — узлы, которые надо изучить раньше: хотя бы
     * один, а при {@code allParents} — все (пусто — стартовый, изучен сразу); {@code cost} — сколько очков стоит; {@code description} —
     * что даёт (для подсказки, может быть пустым); {@code minLevel} — с какого максимального достигнутого
     * уровня опыта открывается (0 — сразу); {@code permission} — право, без которого узел закрыт (null —
     * не нужно), {@code permissionText} — что тогда написать в подсказке; {@code ranks} — сколько раз
     * узел можно изучить (каждый ранг стоит {@code cost}, атрибуты {@code effects} умножаются на ранг).
     * Иконка: ванильный {@code icon}, либо предмет Nexo {@code nexoItem} (тогда icon — запасной), плюс
     * необязательная модель из ресурспака {@code iconModel} (компонент item_model) поверх. {@code hidden} —
     * пока не изучен ни один родитель, узел показывается «?» без иконки, названия и эффектов.
     */
    public record Node(String id, int gx, int gy, Material icon, String nexoItem, String iconModel, String name,
                       String description, List<String> parents, boolean allParents, int cost, int minLevel, String permission,
                       String permissionText, int ranks, boolean hidden, Effects effects) {

        public boolean isStart() {
            return parents.isEmpty();
        }
    }

    /** Почему узел нельзя изучить прямо сейчас; {@link #NONE} — можно. Проверки идут в этом порядке. */
    public enum Lock { NONE, LEARNED, PERMISSION, PARENTS, LEVEL, POINTS }

    /** Что дерево спрашивает об игроке для требований узлов. */
    public interface Access {
        /** Всё разрешено, уровень не важен — для расчётов, где требования не нужны (эффекты, плейсхолдеры). */
        Access ANY = new Access() {
            @Override
            public int maxLevel() {
                return Integer.MAX_VALUE;
            }

            @Override
            public boolean hasPermission(String permission) {
                return true;
            }
        };

        /** Максимальный уровень опыта, которого игрок достигал. */
        int maxLevel();

        boolean hasPermission(String permission);
    }

    /**
     * Что даёт изученный узел. Атрибуты и права действуют, пока узел изучен (см. {@link SkillEffects});
     * команды выполняются от консоли один раз — в момент изучения.
     */
    public record Effects(List<Modifier> attributes, List<String> permissions, List<String> commands) {
        static final Effects NONE = new Effects(List.of(), List.of(), List.of());
    }

    /** Прибавка к атрибуту: {@code amount} как есть, либо доля от значения ({@code percent}, 0.1 = +10%). */
    public record Modifier(String attribute, double amount, boolean percent) {
    }

    /** Что плагин проверяет на сервере при разборе конфига. */
    public interface Lookup {
        /** Предмет по id; null — нет такого или это не предмет. */
        Material item(String id);

        boolean isAttribute(String id);

        /** Есть ли предмет Nexo с таким id (Nexo не установлен — нет). */
        default boolean isNexoItem(String id) {
            return false;
        }
    }

    /** Настоящие реестры сервера. */
    public static final Lookup SERVER = new Lookup() {
        @Override
        public Material item(String id) {
            Material m = Material.matchMaterial(id);
            return m != null && m.isItem() ? m : null;
        }

        @Override
        public boolean isAttribute(String id) {
            NamespacedKey key = NamespacedKey.fromString(id);
            return key != null && Registry.ATTRIBUTE.get(key) != null;
        }

        @Override
        public boolean isNexoItem(String id) {
            return NexoIcons.exists(id);
        }
    };

    /** Тексты разбора конфига из messages: ошибки (errors.*) и умолчания (tree.*). */
    public interface Text {
        String get(String key, Object... kv);
    }

    /** Ошибки разбора — сразу текстом на языке сервера. */
    private record Errors(Text text, List<String> list) {
        void add(String key, Object... kv) {
            list.add(text.get("errors." + key, kv));
        }
    }

    /** id узла идёт в ключи модификаторов и в сохранение через запятую — только такие символы. */
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+");

    /** Подпись на поле дерева (в клетках сетки, можно дробные). */
    public record Label(double gx, double gy, String text) {
    }

    /**
     * Очки за уровни опыта — по максимальному уровню, которого игрок достигал (смерть и зачарование
     * их не отнимают). Либо равномерно: {@code amount} очков за каждые {@code every} уровней, либо
     * своими порогами {@code milestones} (уровень → очков); {@code max} — потолок (0 — без потолка).
     */
    public record LevelPoints(int every, int amount, int max, NavigableMap<Integer, Integer> milestones) {
        static final LevelPoints NONE = new LevelPoints(0, 0, 0, new TreeMap<>());

        /** Сколько очков даёт достигнутый уровень. */
        public int of(int level) {
            int pts = 0;
            if (!milestones.isEmpty()) {
                for (int v : milestones.headMap(level, true).values()) {
                    pts += v;
                }
            } else if (every > 0) {
                pts = level / every * amount;
            }
            return max > 0 ? Math.min(pts, max) : pts;
        }

        /** На каком уровне придёт следующее очко после {@code level}; -1 — больше не придёт (потолок, конец порогов). */
        public int nextLevel(int level) {
            if (max > 0 && of(level) >= max) {
                return -1;
            }
            if (!milestones.isEmpty()) {
                for (Map.Entry<Integer, Integer> e : milestones.tailMap(level, false).entrySet()) {
                    if (e.getValue() > 0) {
                        return e.getKey();
                    }
                }
                return -1;
            }
            return every > 0 && amount > 0 ? (level / every + 1) * every : -1;
        }
    }

    /** Сброс навыков игроком за уровни опыта: {@code levels} + {@code perPoint} за каждое потраченное очко. */
    public record Respec(int levels, int perPoint) {
        public int cost(int spent) {
            return levels + perPoint * spent;
        }
    }

    /** Дерево, чьи данные игроков лежат под ключами PDC без суффикса (см. {@link SkillStore}). */
    public static final String MAIN = "main";

    /**
     * Дерево целиком, как оно задано в конфиге {@code trees/<id>.yml}. {@code tab} — подпись вкладки,
     * {@code order} — её место (меньше — выше). {@code respec} — null, если игрок сбрасывать не может.
     */
    public record Definition(String id, String tab, int order, String title, int points, LevelPoints levelPoints,
                             Respec respec, List<Node> nodes, List<Label> labels) {

        public Node node(String id) {
            for (Node n : nodes) {
                if (n.id().equals(id)) {
                    return n;
                }
            }
            return null;
        }

        /**
         * Прочитать дерево из конфига. Ошибки собираются все сразу, чтобы не чинить конфиг по одной.
         *
         * @param treeId id дерева — имя файла без .yml
         * @param lookup проверка предметов и атрибутов, на сервере — {@link #SERVER}
         * @param text   тексты ошибок и умолчаний на языке сервера
         * @throws IllegalArgumentException со списком ошибок, если дерево собрать нельзя
         */
        public static Definition parse(String treeId, ConfigurationSection cfg, Lookup lookup, Text text) {
            Errors errors = new Errors(text, new ArrayList<>());
            if (!ID.matcher(treeId).matches()) {
                errors.add("tree-id", "id", treeId);
            }
            String title = cfg.getString("title", text.get("tree.default-title"));
            String tab = cfg.getString("tab", title);
            int order = cfg.getInt("order", 0);
            int points = cfg.getInt("points", 10);
            if (points < 0) {
                errors.add("negative", "path", "points");
            }

            Map<String, Node> nodes = new LinkedHashMap<>();
            ConfigurationSection section = cfg.getConfigurationSection("nodes");
            if (section == null || section.getKeys(false).isEmpty()) {
                errors.add("no-nodes");
            } else {
                for (String id : section.getKeys(false)) {
                    ConfigurationSection n = section.getConfigurationSection(id);
                    if (!ID.matcher(id).matches()) {
                        errors.add("node-id", "path", "nodes." + id);
                    }
                    if (n == null) {
                        errors.add("node-section", "path", "nodes." + id);
                        continue;
                    }
                    String iconName = n.getString("icon", "");
                    String nexoItem = null;
                    Material icon;
                    if (iconName.startsWith("nexo:")) {
                        // Предмет Nexo; ванильная иконка — запасная, если Nexo потом не окажется.
                        nexoItem = iconName.substring("nexo:".length());
                        if (!lookup.isNexoItem(nexoItem)) {
                            errors.add("nexo-item", "path", "nodes." + id + ".icon", "item", nexoItem);
                        }
                        icon = Material.PAPER;
                    } else {
                        icon = lookup.item(iconName);
                        if (icon == null) {
                            errors.add("item", "path", "nodes." + id + ".icon", "item", iconName);
                            icon = Material.BARRIER;
                        }
                    }
                    String iconModel = n.getString("icon-model");
                    if (iconModel != null && NamespacedKey.fromString(iconModel) == null) {
                        errors.add("icon-model", "path", "nodes." + id + ".icon-model", "value", iconModel);
                    }
                    int cost = n.getInt("cost", 1);
                    if (cost < 0) {
                        errors.add("negative", "path", "nodes." + id + ".cost");
                    }
                    if (!n.isInt("x") || !n.isInt("y")) {
                        errors.add("grid", "path", "nodes." + id);
                    }
                    Effects effects = effects(n.getConfigurationSection("effects"), "nodes." + id + ".effects",
                            lookup, errors);
                    // parent: один id или список — тогда хватит любого изученного из них (parent-mode: all — нужны все).
                    List<String> parents = n.isList("parent") ? List.copyOf(n.getStringList("parent"))
                            : n.getString("parent") == null ? List.of() : List.of(n.getString("parent"));
                    String parentMode = n.getString("parent-mode", "any");
                    if (!parentMode.equals("any") && !parentMode.equals("all")) {
                        errors.add("parent-mode", "path", "nodes." + id + ".parent-mode", "value", parentMode);
                    }
                    int minLevel = n.getInt("min-level", 0);
                    if (minLevel < 0) {
                        errors.add("negative", "path", "nodes." + id + ".min-level");
                    }
                    int ranks = n.getInt("ranks", 1);
                    if (ranks < 1) {
                        errors.add("ranks", "path", "nodes." + id + ".ranks");
                    }
                    nodes.put(id, new Node(id, n.getInt("x"), n.getInt("y"), icon, nexoItem, iconModel,
                            n.getString("name", id),
                            n.getString("description", ""), parents, parentMode.equals("all"), cost, minLevel, n.getString("permission"),
                            n.getString("permission-text", text.get("tree.no-permission")), Math.max(1, ranks),
                            n.getBoolean("hidden", false), effects));
                }
            }

            Map<String, String> cells = new HashMap<>();
            for (Node n : nodes.values()) {
                String other = cells.putIfAbsent(n.gx() + "," + n.gy(), n.id());
                if (other != null) {
                    errors.add("cell-taken", "path", "nodes." + n.id(), "x", n.gx(), "y", n.gy(), "other", other);
                }
                for (String parent : n.parents()) {
                    if (!nodes.containsKey(parent)) {
                        errors.add("unknown-parent", "path", "nodes." + n.id() + ".parent", "parent", parent);
                    }
                }
            }
            if (errors.list().isEmpty()) {
                // От стартовых узлов должен быть путь к каждому, иначе его не изучить никогда (родители по кругу).
                Set<String> reachable = new HashSet<>();
                nodes.values().stream().filter(Node::isStart).forEach(n -> reachable.add(n.id()));
                boolean grew = true;
                while (grew) {
                    grew = false;
                    for (Node n : nodes.values()) {
                        boolean met = n.allParents() ? n.parents().stream().allMatch(reachable::contains)
                                : n.parents().stream().anyMatch(reachable::contains);
                        if (!reachable.contains(n.id()) && met) {
                            reachable.add(n.id());
                            grew = true;
                        }
                    }
                }
                for (Node n : nodes.values()) {
                    if (!reachable.contains(n.id()) && nodes.values().stream().anyMatch(Node::isStart)) {
                        errors.add("unreachable", "path", "nodes." + n.id());
                    }
                }
            }
            if (!nodes.isEmpty() && nodes.values().stream().noneMatch(Node::isStart)) {
                errors.add("no-start");
            }

            List<Label> labels = new ArrayList<>();
            for (Map<?, ?> m : cfg.getMapList("labels")) {
                if (m.get("x") instanceof Number x && m.get("y") instanceof Number y && m.get("text") != null) {
                    labels.add(new Label(x.doubleValue(), y.doubleValue(), String.valueOf(m.get("text"))));
                } else {
                    errors.add("label", "value", m);
                }
            }

            LevelPoints levelPoints = parseLevelPoints(cfg.getConfigurationSection("level-points"), errors);
            Respec respec = null;
            ConfigurationSection r = cfg.getConfigurationSection("respec");
            if (r != null && r.getBoolean("enabled", true)) {
                respec = new Respec(r.getInt("levels", 0), r.getInt("per-point", 0));
                if (respec.levels() < 0 || respec.perPoint() < 0) {
                    errors.add("respec-negative");
                }
            }

            if (!errors.list().isEmpty()) {
                throw new IllegalArgumentException(String.join("\n", errors.list()));
            }
            return new Definition(treeId, tab, order, title, points, levelPoints, respec, List.copyOf(nodes.values()),
                    List.copyOf(labels));
        }
    }

    private static LevelPoints parseLevelPoints(ConfigurationSection c, Errors errors) {
        if (c == null || !c.getBoolean("enabled", true)) {
            return LevelPoints.NONE;
        }
        int every = c.getInt("every", 0);
        int amount = c.getInt("amount", 1);
        int max = c.getInt("max", 0);
        if (every < 0 || amount < 0 || max < 0) {
            errors.add("level-points-negative");
        }
        NavigableMap<Integer, Integer> milestones = new TreeMap<>();
        ConfigurationSection m = c.getConfigurationSection("milestones");
        if (m != null) {
            for (String key : m.getKeys(false)) {
                try {
                    int level = Integer.parseInt(key.trim());
                    if (level <= 0 || !m.isInt(key) || m.getInt(key) < 0) {
                        throw new NumberFormatException();
                    }
                    milestones.put(level, m.getInt(key));
                } catch (NumberFormatException ex) {
                    errors.add("milestone", "value", key + ": " + m.get(key));
                }
            }
        }
        if (every > 0 && !milestones.isEmpty()) {
            errors.add("level-points-both");
        }
        return new LevelPoints(every, amount, max, milestones);
    }

    private static Effects effects(ConfigurationSection e, String path, Lookup lookup, Errors errors) {
        if (e == null) {
            return Effects.NONE;
        }
        List<Modifier> mods = new ArrayList<>();
        ConfigurationSection attrs = e.getConfigurationSection("attributes");
        if (attrs != null) {
            for (String attr : attrs.getKeys(false)) {
                if (!lookup.isAttribute(attr)) {
                    errors.add("attribute", "path", path + ".attributes", "attr", attr);
                    continue;
                }
                Object raw = attrs.get(attr);
                String text = String.valueOf(raw).trim();
                boolean percent = text.endsWith("%");
                try {
                    double v = Double.parseDouble(percent ? text.substring(0, text.length() - 1).trim() : text);
                    mods.add(new Modifier(attr, percent ? v / 100 : v, percent));
                } catch (NumberFormatException ex) {
                    errors.add("attribute-value", "path", path + ".attributes." + attr, "value", raw);
                }
            }
        }
        return new Effects(List.copyOf(mods), List.copyOf(e.getStringList("permissions")),
                List.copyOf(e.getStringList("commands")));
    }

    private final Definition def;
    private final Access access;
    /**
     * Изученное: id → ранг (больше 0). Включая id, которых в текущем конфиге нет (узел убрали или
     * переименовали) — их не теряем.
     */
    private final Map<String, Integer> allocated = new LinkedHashMap<>();

    /** Очки сверх {@code points} из конфига. */
    private int bonus;

    public SkillTree(Definition def) {
        this(def, Map.of(), 0, Access.ANY);
    }

    /** Без требований к игроку (уровень, права) — для эффектов и плейсхолдеров. */
    public SkillTree(Definition def, Map<String, Integer> learned, int bonus) {
        this(def, learned, bonus, Access.ANY);
    }

    /**
     * @param learned сохранённые изученные узлы: id → ранг
     * @param bonus   очки сверх {@code points} из конфига
     * @param access  уровень и права игрока для требований узлов
     */
    public SkillTree(Definition def, Map<String, Integer> learned, int bonus, Access access) {
        this.def = def;
        this.bonus = bonus;
        this.access = access;
        learned.forEach((id, rank) -> {
            if (rank > 0) {
                allocated.put(id, rank);
            }
        });
        addStartNodes();
    }

    /** Стартовые узлы изучены сразу и полностью. */
    private void addStartNodes() {
        for (Node n : def.nodes()) {
            if (n.isStart()) {
                allocated.put(n.id(), n.ranks());
            }
        }
    }

    /** Изученные узлы текущего дерева (стартовые тоже). */
    public List<Node> allocatedNodes() {
        return def.nodes().stream().filter(this::isAllocated).toList();
    }

    public Definition definition() {
        return def;
    }

    /** Сколько рангов узла изучено (0 — не изучен). Больше, чем ranks в конфиге, не бывает. */
    public int rank(Node node) {
        return Math.min(allocated.getOrDefault(node.id(), 0), node.ranks());
    }

    public boolean isAllocated(Node node) {
        return rank(node) > 0;
    }

    /** Виден ли узел: не скрытый, либо уже изучен, либо изучен хоть один его родитель. */
    public boolean isRevealed(Node node) {
        return !node.hidden() || isAllocated(node) || node.parents().stream().anyMatch(id -> allocated.getOrDefault(id, 0) > 0);
    }

    /** Изучены нужные родители: хотя бы один или, при {@code parent-mode: all}, все. */
    public boolean parentsMet(Node node) {
        return node.allParents() ? node.parents().stream().allMatch(id -> allocated.getOrDefault(id, 0) > 0)
                : node.parents().stream().anyMatch(id -> allocated.getOrDefault(id, 0) > 0);
    }

    /** Изучены все ранги. */
    public boolean isMaxed(Node node) {
        return rank(node) >= node.ranks();
    }

    /** Почему следующий ранг узла сейчас не изучить (или {@link Lock#NONE}). */
    public Lock lock(Node node) {
        if (isMaxed(node)) {
            return Lock.LEARNED;
        }
        if (node.permission() != null && !access.hasPermission(node.permission())) {
            return Lock.PERMISSION;
        }
        if (!parentsMet(node)) {
            return Lock.PARENTS;
        }
        if (access.maxLevel() < node.minLevel()) {
            return Lock.LEVEL;
        }
        if (spent() + node.cost() > points()) {
            return Lock.POINTS;
        }
        return Lock.NONE;
    }

    public boolean canAllocate(Node node) {
        return lock(node) == Lock.NONE;
    }

    /** Изучить следующий ранг узла. */
    public boolean allocate(Node node) {
        if (!canAllocate(node)) {
            return false;
        }
        allocated.put(node.id(), rank(node) + 1);
        return true;
    }

    /**
     * Выдать следующий ранг узла администратором: родители, уровень и право не проверяются, очки тратятся
     * как обычно. {@link Lock#NONE} — выдан, иначе {@link Lock#LEARNED} или {@link Lock#POINTS}.
     */
    public Lock grant(Node node) {
        if (isMaxed(node)) {
            return Lock.LEARNED;
        }
        if (spent() + node.cost() > points()) {
            return Lock.POINTS;
        }
        allocated.put(node.id(), rank(node) + 1);
        return Lock.NONE;
    }

    /**
     * Забрать ранг узла. Если узел забыт совсем, забываются и его потомки, у которых больше не выполнено
     * условие по родителям (по цепочке). Стартовые и не изученные узлы не трогаются.
     *
     * @return потомки, забытые вместе с узлом (без него самого)
     */
    public List<Node> revoke(Node node) {
        int rank = rank(node);
        if (node.isStart() || rank == 0) {
            return List.of();
        }
        if (rank > 1) {
            allocated.put(node.id(), rank - 1);
            return List.of();
        }
        allocated.remove(node.id());
        Set<String> gone = new HashSet<>(Set.of(node.id()));
        List<Node> cascade = new ArrayList<>();
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Node n : def.nodes()) {
                if (!n.isStart() && isAllocated(n) && n.parents().stream().anyMatch(gone::contains) && !parentsMet(n)) {
                    allocated.remove(n.id());
                    gone.add(n.id());
                    cascade.add(n);
                    grew = true;
                }
            }
        }
        return cascade;
    }

    /** Что сохранять игроку: всё изученное (id → ранг), кроме стартовых узлов (их дают даром и так). */
    public Map<String, Integer> learned() {
        Map<String, Integer> out = new LinkedHashMap<>(allocated);
        for (Node n : def.nodes()) {
            if (n.isStart()) {
                out.remove(n.id());
            }
        }
        return out;
    }

    /** Забыть всё изученное (и id узлов, которых в конфиге уже нет) — остаются только стартовые узлы. */
    public void reset() {
        allocated.clear();
        addStartNodes();
    }

    /** Всего очков у игрока: из конфига и выданные. */
    public int points() {
        return def.points() + bonus;
    }

    public void setBonus(int bonus) {
        this.bonus = bonus;
    }

    /** Потрачено очков: цена × ранг (стартовые узлы бесплатные). */
    public int spent() {
        int sum = 0;
        for (Node n : def.nodes()) {
            if (!n.isStart()) {
                sum += n.cost() * rank(n);
            }
        }
        return sum;
    }
}
