package com.skilltree;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

/**
 * Изученные навыки и бонусные очки игрока — в его PDC (файл игрока в мире): отдельная база не нужна,
 * данные переезжают вместе с игроком и на Folia пишутся на его же потоке. Навыки — «id:ранг» через запятую
 * (просто «id» — ранг 1).
 *
 * <p>У каждого дерева свои навыки, бонусные очки и выданные награды: ключ «skills/&lt;дерево&gt;». Дерево
 * {@link SkillTree#MAIN} хранится под ключами без суффикса. Максимальный уровень — общий.</p>
 *
 * <p>Ключи — в пространстве имён cameraui:…: так они лежат с тех пор, как плагин назывался CameraUI.</p>
 */
final class SkillStore {

    /** Пространство имён данных игроков (см. описание класса). */
    static final String NAMESPACE = "cameraui";

    private final NamespacedKey levelKey;

    SkillStore() {
        this.levelKey = key("max_level");
    }

    /** Часть ключа, отличающая дерево: у main — пусто, у остальных — «/id». */
    static String scope(String tree) {
        return tree.equals(SkillTree.MAIN) ? "" : "/" + tree;
    }

    private NamespacedKey key(String base, String tree) {
        return key(base + scope(tree));
    }

    /** Ключ данных игрока: cameraui:&lt;name&gt; — пространство имён плагина под старым именем CameraUI. */
    static NamespacedKey key(String name) {
        return NamespacedKey.fromString(NAMESPACE + ":" + name);
    }

    /** Очки дерева сверх {@code points} из его конфига (выданные командой). */
    int bonus(Player player, String tree) {
        return player.getPersistentDataContainer().getOrDefault(key("points", tree), PersistentDataType.INTEGER, 0);
    }

    void setBonus(Player player, String tree, int bonus) {
        if (bonus == 0) {
            player.getPersistentDataContainer().remove(key("points", tree));
        } else {
            player.getPersistentDataContainer().set(key("points", tree), PersistentDataType.INTEGER, bonus);
        }
    }

    /** Максимальный уровень опыта, которого игрок достигал (для очков за уровни) — общий для всех деревьев. */
    int maxLevel(Player player) {
        return player.getPersistentDataContainer().getOrDefault(levelKey, PersistentDataType.INTEGER, 0);
    }

    void setMaxLevel(Player player, int level) {
        player.getPersistentDataContainer().set(levelKey, PersistentDataType.INTEGER, level);
    }

    /**
     * Ранги узлов дерева, за которые разовые команды уже выполнялись. Сброс навыков их не трогает —
     * повторное изучение не выдаёт награду ещё раз.
     */
    Set<String> rewarded(Player player, String tree) {
        return decode(player.getPersistentDataContainer().get(key("rewarded", tree), PersistentDataType.STRING));
    }

    void addRewarded(Player player, String tree, String id) {
        Set<String> ids = rewarded(player, tree);
        if (ids.add(id)) {
            player.getPersistentDataContainer().set(key("rewarded", tree), PersistentDataType.STRING, encode(ids));
        }
    }

    /**
     * Игрокам, изучившим узлы до появления этого списка, команды уже выполнялись — заносим их изученное.
     * Вызывать до того, как игрок сможет что-то изучить (при открытии экрана).
     */
    void initRewarded(Player player, String tree) {
        NamespacedKey k = key("rewarded", tree);
        if (!player.getPersistentDataContainer().has(k, PersistentDataType.STRING)) {
            player.getPersistentDataContainer().set(k, PersistentDataType.STRING, encode(rewardKeys(load(player, tree))));
        }
    }

    /** Изученное в дереве: id → ранг. */
    Map<String, Integer> load(Player player, String tree) {
        return decodeRanks(player.getPersistentDataContainer().get(key("skills", tree), PersistentDataType.STRING));
    }

    void save(Player player, String tree, Map<String, Integer> learned) {
        if (learned.isEmpty()) {
            clear(player, tree);
        } else {
            player.getPersistentDataContainer().set(key("skills", tree), PersistentDataType.STRING, encodeRanks(learned));
        }
    }

    void clear(Player player, String tree) {
        player.getPersistentDataContainer().remove(key("skills", tree));
    }

    /** Ключ награды за ранг: «id» за первый, «id#2», «id#3» — за следующие. */
    static String rewardKey(String id, int rank) {
        return rank <= 1 ? id : id + "#" + rank;
    }

    /** Все ключи наград за уже изученные ранги. */
    static Set<String> rewardKeys(Map<String, Integer> learned) {
        Set<String> keys = new LinkedHashSet<>();
        learned.forEach((id, rank) -> {
            for (int r = 1; r <= rank; r++) {
                keys.add(rewardKey(id, r));
            }
        });
        return keys;
    }

    static String encodeRanks(Map<String, Integer> ranks) {
        return ranks.entrySet().stream()
                .map(e -> e.getValue() == 1 ? e.getKey() : e.getKey() + ":" + e.getValue())
                .collect(Collectors.joining(","));
    }

    static Map<String, Integer> decodeRanks(String raw) {
        Map<String, Integer> ranks = new LinkedHashMap<>();
        for (String part : decode(raw)) {
            int colon = part.indexOf(':');
            if (colon < 0) {
                ranks.put(part, 1);
                continue;
            }
            try {
                int rank = Integer.parseInt(part.substring(colon + 1).trim());
                if (rank > 0) {
                    ranks.put(part.substring(0, colon).trim(), rank);
                }
            } catch (NumberFormatException ignored) {
                // испорченная запись — пропускаем
            }
        }
        return ranks;
    }

    static String encode(Set<String> ids) {
        return String.join(",", ids);
    }

    static Set<String> decode(String raw) {
        Set<String> ids = new LinkedHashSet<>();
        if (raw != null) {
            Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).forEach(ids::add);
        }
        return ids;
    }
}
