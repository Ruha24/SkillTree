package com.skilltree;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;

/**
 * Эффекты изученных узлов на игроке. {@link #sync} приводит их к тому, что сейчас в trees/*.yml, — после
 * правки конфига у игроков ровно то, что там написано. Команды выполняются один раз — при первом изучении
 * узла ({@link #learned}); после сброса и повторного изучения награду не выдают снова.
 *
 * <p>Атрибуты — обычные (сохраняемые) модификаторы с ключом cameraui:skill/[&lt;дерево&gt;/]&lt;узел&gt;/&lt;атрибут&gt;
 * (у дерева main — без части с деревом):
 * временные при входе ставились бы уже после загрузки здоровья, и бонус к max_health срезался бы при каждом
 * перезаходе. По той же причине sync меняет только отличающиеся модификаторы, а не снимает всё подряд.
 * Права — вложение прав плагина: его сервер не сохраняет, sync ставит заново при входе.</p>
 */
final class SkillEffects {

    private static final String PREFIX = "skill/";

    private final SkillTreePlugin plugin;
    private final String namespace;
    private final Map<UUID, PermissionAttachment> permissions = new ConcurrentHashMap<>();

    SkillEffects(SkillTreePlugin plugin) {
        this.plugin = plugin;
        // cameraui — модификаторы у игроков остались с тех пор, как плагин назывался CameraUI.
        this.namespace = SkillStore.NAMESPACE;
    }

    /** Привести атрибуты и права игрока к изученному во всех деревьях. Вызывать на потоке игрока. */
    void sync(Player player) {
        if (!plugin.treesLoaded()) {
            return; // деревья не загрузились — не трогаем то, что у игрока уже есть
        }
        Map<Attribute, Map<NamespacedKey, AttributeModifier>> wanted = new HashMap<>();
        Set<String> perms = new LinkedHashSet<>();
        for (SkillTree.Definition def : plugin.trees()) {
            SkillTree tree = new SkillTree(def, plugin.skills().load(player, def.id()), 0);
            for (SkillTree.Node n : tree.allocatedNodes()) {
                for (SkillTree.Modifier m : n.effects().attributes()) {
                    Attribute attribute = Registry.ATTRIBUTE.get(NamespacedKey.fromString(m.attribute()));
                    if (attribute == null) {
                        continue;
                    }
                    // cameraui:skill[/дерево]/узел/атрибут — у main без дерева.
                    String scope = def.id().equals(SkillTree.MAIN) ? "" : def.id() + "/";
                    NamespacedKey key = SkillStore.key(
                            PREFIX + scope + n.id() + "/" + m.attribute().replace(':', '.'));
                    // Атрибуты в конфиге — за один ранг.
                    wanted.computeIfAbsent(attribute, a -> new HashMap<>()).put(key, new AttributeModifier(key,
                            m.amount() * tree.rank(n),
                            m.percent() ? AttributeModifier.Operation.MULTIPLY_SCALAR_1 : AttributeModifier.Operation.ADD_NUMBER));
                }
                perms.addAll(n.effects().permissions());
            }
        }

        for (Attribute attribute : Registry.ATTRIBUTE) {
            AttributeInstance inst = player.getAttribute(attribute);
            if (inst == null) {
                continue;
            }
            Map<NamespacedKey, AttributeModifier> want = new HashMap<>(wanted.getOrDefault(attribute, Map.of()));
            for (AttributeModifier have : List.copyOf(inst.getModifiers())) {
                NamespacedKey key = have.getKey();
                if (!key.getNamespace().equals(namespace) || !key.getKey().startsWith(PREFIX)) {
                    continue; // чужой модификатор
                }
                AttributeModifier w = want.get(key);
                if (w != null && w.getAmount() == have.getAmount() && w.getOperation() == have.getOperation()) {
                    want.remove(key); // уже стоит как надо
                } else {
                    inst.removeModifier(have);
                }
            }
            want.values().forEach(inst::addModifier);
        }

        PermissionAttachment old = permissions.remove(player.getUniqueId());
        if (old != null) {
            player.removeAttachment(old);
        }
        if (!perms.isEmpty()) {
            PermissionAttachment attachment = player.addAttachment(plugin);
            perms.forEach(p -> attachment.setPermission(p, true));
            permissions.put(player.getUniqueId(), attachment);
        }
    }

    /**
     * Игрок только что изучил ранг {@code rank} узла: пересчёт атрибутов/прав и, если этот ранг получен
     * впервые, команды узла от консоли.
     */
    void learned(Player player, SkillTree.Definition def, SkillTree.Node node, int rank) {
        sync(player);
        String rewardKey = SkillStore.rewardKey(node.id(), rank);
        if (node.effects().commands().isEmpty() || plugin.skills().rewarded(player, def.id()).contains(rewardKey)) {
            return;
        }
        plugin.skills().addRewarded(player, def.id(), rewardKey);
        for (String raw : node.effects().commands()) {
            String cmd = raw.replace("{player}", player.getName())
                    .replace("{uuid}", player.getUniqueId().toString())
                    .replace("{node}", node.id())
                    .replace("{tree}", def.id())
                    .replace("{rank}", String.valueOf(rank));
            String line = cmd.startsWith("/") ? cmd.substring(1) : cmd;
            // Консольные команды на Folia — только с глобального потока.
            plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
                try {
                    plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), line);
                } catch (RuntimeException ex) {
                    plugin.getLogger().log(Level.WARNING,
                            plugin.messages().plainText("log.command-failed", "node", node.id(), "command", line), ex);
                }
            });
        }
    }

    /** Игрок вышел — вложение прав сервер убирает сам, забываем ссылку. */
    void forget(Player player) {
        permissions.remove(player.getUniqueId());
    }
}
