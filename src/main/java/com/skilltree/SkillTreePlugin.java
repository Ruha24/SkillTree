package com.skilltree;

import com.screenui.ScreenUI;
import com.screenui.Messages;
import com.screenui.UiSession;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Деревья навыков на экране ScreenUI: /skilltree открывает/закрывает экран (вкладки — trees/*.yml). */
public final class SkillTreePlugin extends JavaPlugin {

    /** Деревья по умолчанию — кладутся в trees/, если там ещё ничего нет. */
    private static final List<String> DEFAULT_TREES = List.of("main", "gathering");

    private SkillStore skills;
    private SkillEffects effects;
    private final Messages messages = new Messages(this);
    /** Деревья по порядку вкладок; заменяется целиком при перезагрузке (открытые экраны держат свой список). */
    private volatile List<SkillTree.Definition> trees = List.of();

    @Override
    public void onEnable() {
        migrateFromCameraUI();
        messages.load(ScreenUI.language());
        loadTrees();
        skills = new SkillStore();
        effects = new SkillEffects(this);
        getServer().getPluginManager().registerEvents(new SkillListener(this), this);
        NexoIcons.listen(this);
        SkillTreeCommand command = new SkillTreeCommand(this);
        getCommand("skilltree").setExecutor(command);
        getCommand("skilltree").setTabCompleter(command);
        // Класс расширения ссылается на PlaceholderAPI — трогаем его, только если тот стоит на сервере.
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new SkillPlaceholders(this).register();
        }
        // После /reload права навыков сервер не сохраняет — ставим их заново.
        for (Player p : getServer().getOnlinePlayers()) {
            p.getScheduler().run(this, t -> effects.sync(p), null);
        }
    }

    @Override
    public void onDisable() {
        for (Player p : getServer().getOnlinePlayers()) {
            if (ScreenUI.session(p) instanceof SkillTreeSession) {
                try {
                    ScreenUI.close(p);
                } catch (RuntimeException ignored) {
                    // на Folia — чужой поток региона; движок вернёт игрока при следующем входе
                }
            }
        }
    }

    /**
     * Открыть экран, если сейчас безопасно, или закрыть открытый.
     *
     * @param treeId на какой вкладке открыть; null — на первой
     */
    void toggle(Player player, String treeId) {
        if (ScreenUI.session(player) != null) {
            ScreenUI.close(player);
            return;
        }
        List<SkillTree.Definition> list = trees;
        if (list.isEmpty()) {
            player.sendMessage(messages.get("chat.no-trees", NamedTextColor.RED));
            return;
        }
        ScreenUI.open(player, session(player, treeId));
    }

    /** Новый экран деревьев на вкладке {@code treeId} (null или нет такой — на первой). */
    UiSession session(Player player, String treeId) {
        List<SkillTree.Definition> list = trees;
        int tab = 0;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(treeId)) {
                tab = i;
            }
        }
        return new SkillTreeSession(this, player, list, tab);
    }

    /** Перечитать тексты и trees/*.yml; false — где-то ошибки (там оставлено прежнее дерево). */
    boolean reload() {
        messages.load(ScreenUI.language());
        boolean ok = loadTrees();
        syncAll();
        return ok;
    }

    /** Перечитать только деревья (Nexo загрузил предметы — иконки nexo: теперь проверяются по-настоящему). */
    void reloadTrees() {
        loadTrees();
        syncAll();
    }

    /** Все деревья по порядку вкладок (снимок). */
    List<SkillTree.Definition> trees() {
        return trees;
    }

    /** Загрузилось ли хоть одно дерево — иначе эффекты лучше не трогать. */
    boolean treesLoaded() {
        return !trees.isEmpty();
    }

    /** Дерево по id; null — нет такого. */
    SkillTree.Definition tree(String id) {
        for (SkillTree.Definition def : trees) {
            if (def.id().equals(id)) {
                return def;
            }
        }
        return null;
    }

    /** Изученное игроком в дереве (на потоке игрока); null — нет такого дерева. */
    SkillTree treeOf(Player player, String treeId) {
        SkillTree.Definition def = tree(treeId);
        return def == null ? null
                : new SkillTree(def, skills.load(player, def.id()), extraPoints(player, def));
    }

    /** Очки дерева сверх points из его конфига: выданные командой и за уровни опыта. */
    int extraPoints(Player player, SkillTree.Definition def) {
        return skills.bonus(player, def.id()) + def.levelPoints().of(skills.maxLevel(player));
    }

    /**
     * Игрок достиг уровня {@code level}: если это новый максимум — запомнить и сообщить, сколько очков
     * он принёс в каждом дереве. Вызывается при смене уровня и при входе (уровень мог вырасти, пока плагина
     * не было).
     */
    void levelReached(Player player, int level) {
        int old = skills.maxLevel(player);
        if (level <= old) {
            return;
        }
        skills.setMaxLevel(player, level);
        List<String> gains = new ArrayList<>();
        for (SkillTree.Definition def : trees) {
            int got = def.levelPoints().of(level) - def.levelPoints().of(old);
            if (got > 0) {
                gains.add(messages.format("chat.level-points-entry", "tree", new Messages.Raw(def.tab()), "count", got));
            }
        }
        if (!gains.isEmpty()) {
            player.sendMessage(messages.get("chat.level-points", NamedTextColor.GOLD,
                    "level", level, "gains", new Messages.Raw(String.join(", ", gains))));
        }
        // Даже без новых очков: новый уровень мог открыть узлы с min-level.
        if (ScreenUI.session(player) instanceof SkillTreeSession s) {
            s.pointsChanged();
        }
    }

    /**
     * Прочитать все trees/*.yml. С ошибками в файле — пишем их все в консоль и оставляем прежнюю версию
     * этого дерева (если была). Открытые экраны досматривают то, с чем открылись.
     *
     * @return false — хотя бы в одном файле ошибки
     */
    private boolean loadTrees() {
        File dir = new File(getDataFolder(), "trees");
        File[] files = dir.listFiles((d, name) -> name.endsWith(".yml"));
        if (files == null || files.length == 0) {
            DEFAULT_TREES.forEach(this::saveDefaultTree);
            files = dir.listFiles((d, name) -> name.endsWith(".yml"));
        }
        Map<String, SkillTree.Definition> loaded = new LinkedHashMap<>();
        boolean ok = true;
        for (File file : files == null ? new File[0] : files) {
            String id = file.getName().substring(0, file.getName().length() - ".yml".length());
            try {
                loaded.put(id, SkillTree.Definition.parse(id, YamlConfiguration.loadConfiguration(file), SkillTree.SERVER,
                        messages::plainText));
            } catch (IllegalArgumentException ex) {
                ok = false;
                getLogger().severe(messages.plainText("log.tree-errors", "file", file.getName()) + "\n" + ex.getMessage());
                SkillTree.Definition old = tree(id);
                if (old != null) {
                    loaded.put(id, old);
                }
            }
        }
        List<SkillTree.Definition> sorted = new ArrayList<>(loaded.values());
        sorted.sort(Comparator.comparingInt(SkillTree.Definition::order).thenComparing(SkillTree.Definition::id));
        trees = List.copyOf(sorted);
        return ok;
    }

    /**
     * Стандартное дерево на языке сервера (trees/&lt;язык&gt;/&lt;id&gt;.yml в jar, нет такого языка —
     * английское) — в trees/&lt;id&gt;.yml, если там ещё ничего нет.
     */
    private void saveDefaultTree(String id) {
        File out = new File(getDataFolder(), "trees/" + id + ".yml");
        if (out.exists()) {
            return;
        }
        String path = "trees/" + ScreenUI.language() + "/" + id + ".yml";
        if (getResource(path) == null) {
            path = "trees/en/" + id + ".yml";
        }
        try (InputStream in = getResource(path)) {
            out.getParentFile().mkdirs();
            Files.copy(in, out.toPath());
        } catch (IOException ex) {
            getLogger().warning("Cannot create trees/" + id + ".yml: " + ex.getMessage());
        }
    }

    /**
     * Раньше плагин назывался CameraUI и деревья с текстами лежали в plugins/CameraUI: при первом запуске
     * переносим деревья и копируем тексты (в них и тексты дерева) сюда. Тексты до выбора языка — messages.yml,
     * только русские: он становится messages_ru.yml. Данные игроков — в их PDC под тем же пространством имён
     * cameraui, их переносить не надо.
     */
    private void migrateFromCameraUI() {
        File old = new File(getDataFolder().getParentFile(), "CameraUI");
        File oldTrees = new File(old, "trees");
        File newTrees = new File(getDataFolder(), "trees");
        if (!oldTrees.isDirectory() || newTrees.exists()) {
            return;
        }
        try {
            getDataFolder().mkdirs();
            Files.move(oldTrees.toPath(), newTrees.toPath());
            File[] texts = old.listFiles((d, name) -> name.startsWith("messages_") && name.endsWith(".yml"));
            for (File f : texts == null ? new File[0] : texts) {
                Files.copy(f.toPath(), new File(getDataFolder(), f.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            File legacy = new File(old, "messages.yml");
            File ru = new File(getDataFolder(), "messages_ru.yml");
            if (legacy.isFile() && !ru.exists()) {
                Files.copy(legacy.toPath(), ru.toPath());
            }
            getLogger().info("Trees and texts moved from plugins/CameraUI to plugins/SkillTree.");
        } catch (IOException ex) {
            getLogger().warning("Cannot move trees from plugins/CameraUI: " + ex.getMessage());
        }
    }

    /** Эффекты навыков у всех онлайн — после смены деревьев. */
    private void syncAll() {
        for (Player p : getServer().getOnlinePlayers()) {
            p.getScheduler().run(this, t -> effects.sync(p), null);
        }
    }

    Messages messages() {
        return messages;
    }

    SkillEffects effects() {
        return effects;
    }

    SkillStore skills() {
        return skills;
    }
}
