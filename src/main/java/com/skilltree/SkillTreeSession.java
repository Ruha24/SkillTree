package com.skilltree;

import com.screenui.ScreenUI;
import com.screenui.Messages;
import com.screenui.UiSession;
import io.papermc.paper.datacomponent.DataComponentTypes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;

/**
 * /skilltree: деревья навыков из trees/*.yml — вкладками слева; у текущего дерева пан (перетаскивание ЛКМ)
 * и зум (колесо). Шапка, подсказки, вкладки и кнопка сброса строятся один раз; при смене вкладки
 * перестраиваются только элементы дерева ({@link #treeEls}).
 */
public final class SkillTreeSession extends UiSession {

    private static final double GRID = 0.22;
    private static final double NODE = 0.11;
    private static final double ICON = 0.07;
    private static final double LINE = 0.012;

    private static final Color BACKDROP = Color.fromARGB(245, 16, 16, 20);
    private static final Color PANEL = Color.fromARGB(255, 27, 27, 33);
    private static final Color GOLD = Color.fromARGB(255, 232, 184, 74);
    private static final Color GOLD_HOVER = Color.fromARGB(255, 255, 216, 107);
    /** Изучены не все ранги узла. */
    private static final Color PARTIAL = Color.fromARGB(255, 150, 112, 40);
    private static final Color PARTIAL_HOVER = Color.fromARGB(255, 186, 142, 58);
    private static final Color BADGE = Color.fromARGB(230, 16, 16, 20);
    private static final Color AVAILABLE = Color.fromARGB(255, 74, 74, 88);
    private static final Color AVAILABLE_HOVER = Color.fromARGB(255, 122, 122, 144);
    private static final Color LOCKED = Color.fromARGB(255, 34, 34, 42);
    private static final Color LOCKED_HOVER = Color.fromARGB(255, 51, 51, 61);
    private static final Color LINE_OFF = Color.fromARGB(255, 58, 58, 68);
    private static final Color NONE = Color.fromARGB(0, 0, 0, 0);
    private static final Color DANGER = Color.fromARGB(255, 122, 40, 40);
    /** Выбранная вкладка. */
    private static final Color TAB_ACTIVE = Color.fromARGB(255, 92, 72, 30);

    /** Кнопка сброса — справа сверху, в координатах экрана (не двигается с деревом). */
    private static final double RESET_X = 1.45;
    private static final double RESET_Y = 0.95;
    private static final double RESET_W = 0.5;
    private static final double RESET_H = 0.13;
    /** Кнопка «FOV, под который построен экран» — справа снизу, над нижней строкой; ведёт в калибровку. */
    private static final double FOV_X = 1.45;
    private static final double FOV_Y = -0.84;
    private static final double FOV_W = 0.5;
    private static final double FOV_H = 0.13;
    /** Кнопка «к центру» — слева снизу, напротив кнопки FOV: вернуть дерево в исходное положение. */
    private static final double CENTER_X = -1.45;
    private static final double CENTER_Y = -0.84;
    private static final double CENTER_W = 0.5;
    private static final double CENTER_H = 0.13;
    /** Вкладки — столбцом слева сверху: первая на TAB_Y, следующие ниже на TAB_STEP. */
    private static final double TAB_X = -1.45;
    private static final double TAB_Y = 0.95;
    private static final double TAB_W = 0.5;
    private static final double TAB_H = 0.13;
    private static final double TAB_STEP = 0.16;
    /**
     * Насколько центр экрана может уйти за край дерева при перетаскивании (в единицах экрана) — дальше
     * дерево не тащится и не уезжает из кадра. Граница растёт с зумом: приближенное дерево видно целиком.
     */
    private static final double PAN_MARGIN = 0.25;
    /** Прозрачность панели, под которой оказалось дерево (из 255), и текста на ней. */
    private static final int FADED_ALPHA = 90;
    private static final byte FADED_TEXT = (byte) 150;
    /** Текст по умолчанию — непрозрачный (-1 = 255). */
    private static final byte OPAQUE_TEXT = (byte) -1;

    /**
     * Ореол узла, который можно изучить прямо сейчас: полупрозрачный квадрат за узлом, раз в PULSE_TICKS
     * меняет размер между HALO_MIN и HALO_MAX — плавно, интерполяцией на клиенте.
     */
    private static final Color HALO = Color.fromARGB(110, 232, 184, 74);
    private static final double HALO_MIN = NODE + 0.012;
    private static final double HALO_MAX = NODE + 0.036;
    private static final int PULSE_TICKS = 12;

    /** Вспышка изученного узла: во сколько раз «подпрыгивает» и сколько тиков держится. */
    private static final double POP = 1.3;
    private static final int POP_TICKS = 3;
    private static final Color FLASH = Color.fromARGB(255, 255, 246, 214);

    /**
     * Панель подробностей узла под курсором — справа. Один многострочный text_display: его фон сам
     * подгоняется под текст. Text_display растёт вверх от своей точки, а панель должна стоять верхним краем
     * на DETAILS_TOP — поэтому точку опускаем на высоту текста ({@link #placeDetails}).
     */
    private static final double DETAILS_X = 1.4;
    private static final double DETAILS_TOP = 0.6;
    private static final double DETAILS_SCALE = 0.22;
    /** Высота строки text_display — 10 px шрифта, в единицах экрана при масштабе панели. */
    private static final double DETAILS_LINE = 10 * 0.025 * DETAILS_SCALE;
    /** Ширина строки панели в пикселях шрифта — длинное описание переносится. */
    private static final int DETAILS_WIDTH = 190;
    private static final int OK = 0x7FD08A;
    private static final int BAD = 0xE06A6A;
    private static final int MUTED = 0x9A9AAA;
    private static final int RANK = 0xE8B84A;

    /** Сколько ждём второго нажатия «сбросить». */
    private static final long CONFIRM_MS = 3000;

    private final SkillTreePlugin plugin;
    /** Деревья (вкладки), с которыми экран открылся; перезагрузка конфига их не меняет. */
    private final List<SkillTree.Definition> defs;
    /** Уровень и права игрока — для требований узлов; читаются вживую. */
    private final SkillTree.Access access;
    private int tab;
    private SkillTree.Definition def;
    private SkillTree tree;

    /** Все элементы текущего дерева — убираются при смене вкладки. */
    private final List<El> treeEls = new ArrayList<>();
    private final Map<SkillTree.Node, El> nodeBoxes = new HashMap<>();
    private final Map<SkillTree.Node, El> nodeIcons = new HashMap<>();
    private final Map<SkillTree.Node, El> halos = new HashMap<>();
    /** Узлы, у которых сейчас виден ореол. */
    private final Set<SkillTree.Node> pulsing = new HashSet<>();
    private int pulseTicks;
    private boolean pulseOut;
    /** «?» на месте иконки скрытых узлов и какие из них сейчас скрыты (иконка убрана). */
    private final Map<SkillTree.Node, TextDisplay> mysteries = new HashMap<>();
    private final Set<SkillTree.Node> concealed = new HashSet<>();
    /** Плашка «ранг / рангов» под углом узлов, у которых больше одного ранга. */
    private final Map<SkillTree.Node, TextDisplay> rankBadges = new HashMap<>();

    /** Линия от узла к одному из родителей: один отрезок или два (уголок). */
    private record Line(SkillTree.Node node, SkillTree.Node parent, List<El> segments) {
    }

    private final List<Line> lines = new ArrayList<>();

    private TextDisplay title;
    private TextDisplay subtitle;
    private TextDisplay tooltip;
    private TextDisplay details;
    private El detailsEl;
    private boolean dragging;
    private SkillTree.Node hovered;
    /** Рамка дерева в его собственных координатах (без пана и зума): узлы с краями и подписи. */
    private double treeMinX;
    private double treeMaxX;
    private double treeMinY;
    private double treeMaxY;
    /** Панели поверх дерева: шапка, нижняя строка, вкладки, кнопки сброса, FOV и «к центру». */
    private final List<Overlay> overlays = new ArrayList<>();
    private Overlay resetOverlay;
    private boolean resetHovered;
    /** До какого момента (мс) второе нажатие на кнопку сброса его подтвердит; 0 — не взведено. */
    private long resetArmedUntil;
    private Overlay fovOverlay;
    private boolean fovHovered;
    private Overlay centerOverlay;
    private boolean centerHovered;
    private final List<Overlay> tabOverlays = new ArrayList<>();
    private final List<TextDisplay> tabTexts = new ArrayList<>();
    /** Вкладка под курсором; -1 — ни одна. */
    private int hoveredTab = -1;

    SkillTreeSession(SkillTreePlugin plugin, Player player, List<SkillTree.Definition> defs, int tab) {
        super(player);
        this.plugin = plugin;
        this.defs = defs;
        this.tab = tab;
        defs.forEach(d -> plugin.skills().initRewarded(player, d.id()));
        // Уровень и права читаются вживую: поднял уровень или получил право с открытым экраном — узел откроется.
        this.access = new SkillTree.Access() {
            @Override
            public int maxLevel() {
                return plugin.skills().maxLevel(player);
            }

            @Override
            public boolean hasPermission(String permission) {
                return player.hasPermission(permission);
            }
        };
        loadTree();
    }

    /** Тот же экран на той же вкладке, заново построенный (после калибровки или смены FOV). */
    @Override
    protected UiSession recreate() {
        return plugin.session(player, def.id());
    }

    /** id дерева открытой вкладки. */
    String treeId() {
        return def.id();
    }

    private void loadTree() {
        def = defs.get(tab);
        tree = new SkillTree(def, plugin.skills().load(player, def.id()), plugin.extraPoints(player, def), access);
    }

    // ── Построение экрана ────────────────────────────────────────────────

    @Override
    protected void build() {
        rect(0, 0, 40, 24, -0.05, BACKDROP, false); // с запасом под FOV выше расчётного

        // Шапка и подсказки — поверх дерева, не двигаются; над деревом становятся полупрозрачными.
        // Шапка прижата к верху (0.825..1.075): в исходном положении дерева под ней нет даже верхней подписи.
        Overlay header = new Overlay(0, 0.95, 1.3, 0.25);
        header.rect(0, 0.95, 1.3, 0.25, 0.10, PANEL);
        header.rect(0, 0.895, 0.5, 0.008, 0.11, GOLD);
        title = header.text(0, 0.985, 0.5, Component.empty());
        subtitle = header.text(0, 0.855, 0.22, Component.empty());

        Overlay footer = new Overlay(0, -1.0, 3.3, 0.12);
        footer.rect(0, -1.0, 3.3, 0.12, 0.10, PANEL);
        footer.text(0, -1.0, 0.2, msg().get("screen.footer", NamedTextColor.GRAY));
        tooltip = (TextDisplay) text(0, -0.84, 0.25, 0.11, Component.empty(), false).display;
        detailsEl = text(right(DETAILS_X), DETAILS_TOP, DETAILS_SCALE, 0.12, Component.empty(), false);
        details = (TextDisplay) detailsEl.display;
        details.setAlignment(TextDisplay.TextAlignment.LEFT);
        details.setLineWidth(DETAILS_WIDTH);

        // Вкладки — только если деревьев больше одного.
        if (defs.size() > 1) {
            for (int i = 0; i < defs.size(); i++) {
                int index = i;
                double y = TAB_Y - i * TAB_STEP;
                Overlay o = new Overlay(left(TAB_X), y, TAB_W, TAB_H);
                o.rect(left(TAB_X), y, TAB_W, TAB_H, 0.10,
                        () -> index == tab ? TAB_ACTIVE : index == hoveredTab ? AVAILABLE : PANEL);
                tabTexts.add(o.text(left(TAB_X), y, 0.2, Component.empty()));
                tabOverlays.add(o);
            }
        }

        // Кнопка сброса — общая; у деревьев без respec прячется.
        resetOverlay = new Overlay(right(RESET_X), RESET_Y, RESET_W, RESET_H);
        resetOverlay.rect(right(RESET_X), RESET_Y, RESET_W, RESET_H, 0.10,
                () -> resetArmedUntil != 0 ? DANGER : resetHovered ? AVAILABLE : PANEL);
        resetOverlay.text(right(RESET_X), RESET_Y, 0.2, msg().get("screen.reset-button", NamedTextColor.GRAY));

        // Под какой FOV построен экран (тот же, что в масштабе UiSession) — и кнопка его перенастроить.
        centerOverlay = new Overlay(left(CENTER_X), CENTER_Y, CENTER_W, CENTER_H);
        centerOverlay.rect(left(CENTER_X), CENTER_Y, CENTER_W, CENTER_H, 0.10, () -> centerHovered ? AVAILABLE : PANEL);
        centerOverlay.text(left(CENTER_X), CENTER_Y, 0.2, msg().get("screen.center-button", NamedTextColor.GRAY));

        fovOverlay = new Overlay(right(FOV_X), FOV_Y, FOV_W, FOV_H);
        fovOverlay.rect(right(FOV_X), FOV_Y, FOV_W, FOV_H, 0.10, () -> fovHovered ? AVAILABLE : PANEL);
        fovOverlay.text(right(FOV_X), FOV_Y, 0.2, msg().get("screen.fov-button", NamedTextColor.GRAY,
                "fov", ScreenUI.fov(player)));

        buildTree();
    }

    /** Элементы текущего дерева: линии, узлы, плашки рангов, подписи. Вид — в исходном положении. */
    private void buildTree() {
        zoom = 1;
        panX = 0;
        panY = 0;
        // Связи — под узлами. Каждый узел рисует линию к каждому родителю: прямую, если они на одной линии
        // сетки, иначе уголком — сначала по горизонтали от узла, потом по вертикали к родителю.
        for (SkillTree.Node n : def.nodes()) {
            for (String parentId : n.parents()) {
                SkillTree.Node p = def.node(parentId);
                double x1 = n.gx() * GRID;
                double y1 = n.gy() * GRID;
                double x2 = p.gx() * GRID;
                double y2 = p.gy() * GRID;
                List<El> segments = new ArrayList<>();
                if (y1 != y2 && x1 != x2) {
                    segments.add(segment(x1, y1, x2, y1));
                    segments.add(segment(x2, y1, x2, y2));
                } else {
                    segments.add(segment(x1, y1, x2, y2));
                }
                lines.add(new Line(n, p, segments));
            }
        }
        for (SkillTree.Node n : def.nodes()) {
            // Между линиями (z 0.01) и узлом (z 0.02); пока узел не доступен — прозрачный.
            halos.put(n, mine(rect(n.gx() * GRID, n.gy() * GRID, HALO_MIN, HALO_MIN, 0.015, NONE, true)));
            nodeBoxes.put(n, mine(rect(n.gx() * GRID, n.gy() * GRID, NODE, NODE, 0.02, LOCKED, true)));
            nodeIcons.put(n, mine(item(n.gx() * GRID, n.gy() * GRID, ICON, 0.06, iconStack(n), true)));
            if (n.hidden()) {
                mysteries.put(n, (TextDisplay) mine(text(n.gx() * GRID, n.gy() * GRID, 0.4, 0.07,
                        Component.empty(), true)).display);
            }
            if (n.ranks() > 1) {
                // Под правым нижним углом узла, а не поверх: внутри клетки плашка закрывала иконку.
                TextDisplay badge = (TextDisplay) mine(text(n.gx() * GRID + NODE / 2 - 0.022,
                        n.gy() * GRID - NODE / 2 - 0.017, 0.12, 0.09, Component.empty(), true)).display;
                badge.setBackgroundColor(BADGE);
                rankBadges.put(n, badge);
            }
        }
        for (SkillTree.Label l : def.labels()) {
            mine(text(l.gx() * GRID, l.gy() * GRID, 0.16, 0.02,
                    Messages.parse(l.text(), TextColor.color(0x8A8A99)), true));
        }

        title.text(Messages.parse(def.title(), NamedTextColor.WHITE));
        for (int i = 0; i < tabTexts.size(); i++) {
            tabTexts.get(i).text(Messages.parse(defs.get(i).tab(), i == tab ? NamedTextColor.WHITE : NamedTextColor.GRAY));
        }
        resetOverlay.setVisible(def.respec() != null);
        measureTree();
        updateOverlays();
        refresh();
    }

    /** Предмет-иконка узла: ванильный, из Nexo или с моделью из ресурспака. */
    private static ItemStack iconStack(SkillTree.Node n) {
        ItemStack stack = n.nexoItem() != null ? NexoIcons.item(n.nexoItem()) : null;
        if (stack == null) {
            stack = new ItemStack(n.icon());
        }
        if (n.iconModel() != null) {
            stack.setData(DataComponentTypes.ITEM_MODEL, Key.key(n.iconModel()));
        }
        return stack;
    }

    private boolean revealed(SkillTree.Node n) {
        return tree.isRevealed(n);
    }

    /** Скрытый узел: убрать иконку и показать «?»; открылся — вернуть иконку. Только при смене (это пакеты). */
    private void conceal(SkillTree.Node n, boolean hide) {
        if (hide ? concealed.add(n) : concealed.remove(n)) {
            ((ItemDisplay) nodeIcons.get(n).display).setItemStack(hide ? new ItemStack(Material.AIR) : iconStack(n));
            mysteries.get(n).text(hide ? Component.text("?", TextColor.color(MUTED)) : Component.empty());
        }
    }

    /** Ореол доступного узла — менять цвет, только если изменилось (это пакет). Размер качает {@link #onTick}. */
    private void halo(SkillTree.Node n, boolean on) {
        if (on ? pulsing.add(n) : pulsing.remove(n)) {
            ((TextDisplay) halos.get(n).display).setBackgroundColor(on ? HALO : NONE);
        }
    }

    /** Элемент принадлежит дереву — уберётся при смене вкладки. */
    private El mine(El e) {
        treeEls.add(e);
        return e;
    }

    /** Переключиться на вкладку: убрать текущее дерево и построить другое, камера и сиденье остаются. */
    private void switchTab(int index) {
        if (index == tab) {
            return;
        }
        treeEls.forEach(this::remove);
        treeEls.clear();
        nodeBoxes.clear();
        nodeIcons.clear();
        halos.clear();
        pulsing.clear();
        mysteries.clear();
        concealed.clear();
        rankBadges.clear();
        lines.clear();
        hovered = null;
        dragging = false;
        resetArmedUntil = 0;
        tab = index;
        loadTree();
        buildTree();
        tabOverlays.forEach(Overlay::paint);
        player.playSound(player, Sound.ITEM_BOOK_PAGE_TURN, 0.6f, 1.0f);
        updateHover();
    }

    /**
     * Панель поверх дерева. Когда под ней узел или подпись, она становится полупрозрачной вместе с текстом —
     * иначе шапка закрывала, например, верхнюю ветку перетащенного дерева.
     */
    private final class Overlay {
        private final double x;
        private final double y;
        private final double w;
        private final double h;
        /** Фоны и их текущие цвета (цвет кнопок меняется от наведения — поэтому поставщик). */
        private final Map<El, Supplier<Color>> rects = new LinkedHashMap<>();
        /** Тексты и их содержимое — чтобы вернуть после {@link #setVisible}(false). */
        private final Map<TextDisplay, Component> texts = new LinkedHashMap<>();
        private boolean faded;
        private boolean visible = true;

        Overlay(double x, double y, double w, double h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            overlays.add(this);
        }

        void rect(double rx, double ry, double rw, double rh, double z, Color color) {
            rect(rx, ry, rw, rh, z, () -> color);
        }

        void rect(double rx, double ry, double rw, double rh, double z, Supplier<Color> color) {
            El e = SkillTreeSession.this.rect(rx, ry, rw, rh, z, color.get(), false);
            rects.put(e, color);
        }

        TextDisplay text(double tx, double ty, double scale, Component content) {
            TextDisplay t = (TextDisplay) SkillTreeSession.this.text(tx, ty, scale, 0.11, content, false).display;
            texts.put(t, content);
            return t;
        }

        boolean contains(double px, double py) {
            return visible && Math.abs(px - x) <= w / 2 && Math.abs(py - y) <= h / 2;
        }

        /** Спрятать целиком (кнопка сброса у дерева без respec). */
        void setVisible(boolean show) {
            if (show == visible) {
                return;
            }
            visible = show;
            texts.forEach((t, content) -> t.text(show ? content : Component.empty()));
            paint();
        }

        /** Пересчитать, есть ли под панелью дерево, и перекрасить, если изменилось. */
        void update() {
            boolean under = treeUnder(x, y, w, h);
            if (under != faded) {
                faded = under;
                texts.keySet().forEach(t -> t.setTextOpacity(faded ? FADED_TEXT : OPAQUE_TEXT));
                paint();
            }
        }

        void paint() {
            rects.forEach((e, color) -> {
                Color c = visible ? color.get() : NONE;
                ((TextDisplay) e.display).setBackgroundColor(faded ? c.setAlpha(Math.min(c.getAlpha(), FADED_ALPHA)) : c);
            });
        }
    }

    /** Есть ли под прямоугольником экрана (центр, ширина, высота) узел или подпись дерева. */
    private boolean treeUnder(double cx, double cy, double w, double h) {
        double half = NODE / 2 * zoom;
        for (SkillTree.Node n : def.nodes()) {
            if (overlap(n.gx() * GRID * zoom + panX, n.gy() * GRID * zoom + panY, half, half, cx, cy, w, h)) {
                return true;
            }
        }
        for (SkillTree.Label l : def.labels()) {
            if (overlap(l.gx() * GRID * zoom + panX, l.gy() * GRID * zoom + panY,
                    labelHalfWidth(l.text()) * zoom, 0.02 * zoom, cx, cy, w, h)) {
                return true;
            }
        }
        return false;
    }

    private static boolean overlap(double x, double y, double hw, double hh, double cx, double cy, double w, double h) {
        return Math.abs(x - cx) < hw + w / 2 && Math.abs(y - cy) < hh + h / 2;
    }

    /** Полуширина подписи ветки (символ ≈ 6 px шрифта по 0.025 × масштаб 0.16) — с запасом. */
    private static double labelHalfWidth(String text) {
        return Messages.plain(text).length() * 6 * 0.025 * 0.16 / 2;
    }

    /** Рамка дерева — для ограничения перетаскивания. */
    private void measureTree() {
        treeMinX = Double.MAX_VALUE;
        treeMaxX = -Double.MAX_VALUE;
        treeMinY = Double.MAX_VALUE;
        treeMaxY = -Double.MAX_VALUE;
        for (SkillTree.Node n : def.nodes()) {
            grow(n.gx() * GRID, n.gy() * GRID, NODE / 2, NODE / 2);
        }
        for (SkillTree.Label l : def.labels()) {
            grow(l.gx() * GRID, l.gy() * GRID, labelHalfWidth(l.text()), 0.02);
        }
    }

    private void grow(double x, double y, double hw, double hh) {
        treeMinX = Math.min(treeMinX, x - hw);
        treeMaxX = Math.max(treeMaxX, x + hw);
        treeMinY = Math.min(treeMinY, y - hh);
        treeMaxY = Math.max(treeMaxY, y + hh);
    }

    /**
     * Не дать утащить дерево из кадра: центр экрана (0, 0) остаётся внутри рамки дерева с запасом
     * {@link #PAN_MARGIN}. Рамка на экране — treeMin * zoom + pan, отсюда границы pan.
     */
    private void clampPan() {
        panX = Math.clamp(panX, -(treeMaxX * zoom + PAN_MARGIN), -(treeMinX * zoom - PAN_MARGIN));
        panY = Math.clamp(panY, -(treeMaxY * zoom + PAN_MARGIN), -(treeMinY * zoom - PAN_MARGIN));
    }

    /** Дерево сдвинулось или изменило размер — перекрасить панели над ним. */
    private void updateOverlays() {
        overlays.forEach(Overlay::update);
    }

    /** Горизонтальный или вертикальный отрезок линии связи (концы — в центрах клеток, линия заходит на толщину за угол). */
    private El segment(double x1, double y1, double x2, double y2) {
        boolean horizontal = y1 == y2;
        return mine(rect((x1 + x2) / 2, (y1 + y2) / 2,
                horizontal ? Math.abs(x1 - x2) + LINE : LINE, horizontal ? LINE : Math.abs(y1 - y2) + LINE,
                0.01, LINE_OFF, true));
    }

    // ── Ввод ─────────────────────────────────────────────────────────────

    @Override
    protected void onCursorMove(double dx, double dy) {
        if (dragging) {
            panX += dx;
            panY += dy;
            clampPan();
            layoutWorld(2);
            updateOverlays();
        }
    }

    @Override
    protected void click() {
        if (resetHovered) {
            resetClick();
            return;
        }
        if (hoveredTab >= 0) {
            switchTab(hoveredTab);
            return;
        }
        if (fovHovered) {
            player.playSound(player, Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
            ScreenUI.calibrate(player); // закроет дерево и вернёт на эту вкладку после выбора ({@link #recreate})
            return;
        }
        if (centerHovered) {
            recenter();
            return;
        }
        if (hovered != null) {
            if (tree.allocate(hovered)) {
                plugin.skills().save(player, def.id(), tree.learned());
                plugin.effects().learned(player, def, hovered, tree.rank(hovered));
                player.playSound(player, Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.6f);
                refresh();
                celebrate(hovered);
            } else {
                player.playSound(player, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.6f);
            }
            return;
        }
        dragging = !dragging;
        player.playSound(player, Sound.UI_BUTTON_CLICK, 0.4f, dragging ? 1.4f : 0.9f);
        showTooltip();
    }

    /**
     * Сброс дерева открытой вкладки за уровни опыта — вторым нажатием в течение {@link #CONFIRM_MS},
     * чтобы не сбросить случайно. Очки за уровни считаются по максимальному уровню, поэтому потраченные
     * уровни их не отнимают.
     */
    private void resetClick() {
        int cost = def.respec().cost(tree.spent());
        if (tree.learned().isEmpty() || player.getLevel() < cost) {
            player.playSound(player, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.6f);
            return;
        }
        long now = System.currentTimeMillis();
        if (now > resetArmedUntil) {
            resetArmedUntil = now + CONFIRM_MS;
            player.playSound(player, Sound.UI_BUTTON_CLICK, 0.5f, 0.7f);
            resetOverlay.paint();
            showTooltip();
            return;
        }
        resetArmedUntil = 0;
        int refunded = tree.spent();
        player.setLevel(player.getLevel() - cost);
        tree.reset();
        plugin.skills().save(player, def.id(), tree.learned());
        plugin.effects().sync(player);
        player.playSound(player, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.6f, 1.2f);
        player.sendMessage(msg().get("reset.done", NamedTextColor.GOLD, "tree", new Messages.Raw(def.tab()), "refund", refunded,
                "points", pts(refunded), "cost", cost));
        resetOverlay.paint();
        refresh();
    }

    @Override
    protected void onTick() {
        if (resetArmedUntil != 0 && System.currentTimeMillis() > resetArmedUntil) {
            resetArmedUntil = 0; // не подтвердили — кнопка остывает
            resetOverlay.paint();
            showTooltip();
        }
        if (++pulseTicks >= PULSE_TICKS) {
            pulseTicks = 0;
            pulseOut = !pulseOut;
            for (SkillTree.Node n : pulsing) {
                El halo = halos.get(n);
                halo.size = pulseOut ? HALO_MAX : HALO_MIN;
                halo.height = halo.size;
                place(halo, PULSE_TICKS);
            }
        }
    }

    /**
     * Отклик на изучение: узел вспыхивает и «подпрыгивает» (размер с интерполяцией — плавно рисует клиент),
     * из него летят искры — только этому игроку. На последнем ранге искр больше.
     */
    private void celebrate(SkillTree.Node n) {
        El box = nodeBoxes.get(n);
        El icon = nodeIcons.get(n);
        double boxW = box.size;
        double boxH = box.height;
        double iconSize = icon.size;
        box.size = boxW * POP;
        box.height = boxH * POP;
        icon.size = iconSize * POP;
        place(box, POP_TICKS);
        place(icon, POP_TICKS);
        ((TextDisplay) box.display).setBackgroundColor(FLASH);
        player.getScheduler().runDelayed(plugin, t -> {
            // Экран закрыли или переключили вкладку — этих элементов уже нет.
            if (isClosed() || nodeBoxes.get(n) != box) {
                return;
            }
            box.size = boxW;
            box.height = boxH;
            icon.size = iconSize;
            place(box, POP_TICKS + 2);
            place(icon, POP_TICKS + 2);
            paint(n);
        }, null, POP_TICKS);

        Location at = screenToWorld(n.gx() * GRID * zoom + panX, n.gy() * GRID * zoom + panY, 0.12);
        boolean maxed = tree.isMaxed(n);
        player.spawnParticle(Particle.END_ROD, at, maxed ? 16 : 8, 0.02, 0.02, 0.0, 0.02);
        if (maxed) {
            player.spawnParticle(Particle.WAX_ON, at, 10, 0.05, 0.05, 0.0, 0.1);
        }
    }

    /** Вернуть дерево в исходное положение (как при открытии вкладки) — плавно. */
    private void recenter() {
        player.playSound(player, Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
        dragging = false;
        zoom = 1;
        panX = 0;
        panY = 0;
        layoutWorld(6);
        updateOverlays();
        showTooltip();
    }

    /** Зум вокруг курсора. */
    @Override
    protected void scroll(int step) {
        double next = Math.clamp(zoom * Math.pow(1.15, step), 0.5, 2.5);
        double k = next / zoom;
        panX = cursorX - (cursorX - panX) * k;
        panY = cursorY - (cursorY - panY) * k;
        zoom = next;
        clampPan(); // отдаление могло вынести дерево за границу
        layoutWorld(3);
        updateOverlays();
        updateHover();
    }

    // ── Отрисовка состояния ──────────────────────────────────────────────

    @Override
    protected void updateHover() {
        // Кнопки лежат поверх дерева: под ними узел не наводится.
        boolean overReset = resetOverlay.contains(cursorX, cursorY);
        if (overReset != resetHovered) {
            resetHovered = overReset;
            resetArmedUntil = 0; // ушли с кнопки — подтверждение сбрасывается
            if (overReset) {
                player.playSound(player, Sound.BLOCK_NOTE_BLOCK_HAT, 0.3f, 1.8f);
            }
            resetOverlay.paint();
            showTooltip();
        }
        boolean overFov = fovOverlay.contains(cursorX, cursorY);
        if (overFov != fovHovered) {
            fovHovered = overFov;
            if (overFov) {
                player.playSound(player, Sound.BLOCK_NOTE_BLOCK_HAT, 0.3f, 1.8f);
            }
            fovOverlay.paint();
        }
        boolean overCenter = centerOverlay.contains(cursorX, cursorY);
        if (overCenter != centerHovered) {
            centerHovered = overCenter;
            if (overCenter) {
                player.playSound(player, Sound.BLOCK_NOTE_BLOCK_HAT, 0.3f, 1.8f);
            }
            centerOverlay.paint();
        }
        int overTab = -1;
        for (int i = 0; i < tabOverlays.size(); i++) {
            if (tabOverlays.get(i).contains(cursorX, cursorY)) {
                overTab = i;
            }
        }
        if (overTab != hoveredTab) {
            int old = hoveredTab;
            hoveredTab = overTab;
            if (old >= 0) {
                tabOverlays.get(old).paint();
            }
            if (overTab >= 0) {
                tabOverlays.get(overTab).paint();
                if (overTab != tab) {
                    player.playSound(player, Sound.BLOCK_NOTE_BLOCK_HAT, 0.3f, 1.8f);
                }
            }
        }
        SkillTree.Node found = null;
        if (!overReset && !overFov && !overCenter && overTab < 0) {
            double half = NODE / 2 * zoom;
            for (SkillTree.Node n : def.nodes()) {
                double sx = n.gx() * GRID * zoom + panX;
                double sy = n.gy() * GRID * zoom + panY;
                if (Math.abs(cursorX - sx) <= half && Math.abs(cursorY - sy) <= half) {
                    found = n;
                    break;
                }
            }
        }
        if (found != hovered) {
            SkillTree.Node old = hovered;
            hovered = found;
            if (old != null) {
                paint(old);
            }
            if (found != null) {
                paint(found);
                player.playSound(player, Sound.BLOCK_NOTE_BLOCK_HAT, 0.3f, 1.8f);
            }
            showTooltip();
        }
    }

    private void refresh() {
        def.nodes().forEach(this::paint);
        // Золотая — линия, по которой узел изучен: изучены оба конца.
        for (Line line : lines) {
            Color color = tree.isAllocated(line.node()) && tree.isAllocated(line.parent()) ? GOLD : LINE_OFF;
            line.segments().forEach(e -> ((TextDisplay) e.display).setBackgroundColor(color));
        }
        subtitle.text(msg().get("screen.spent", TextColor.color(MUTED), "spent", tree.spent(), "total", tree.points(),
                "points", pts(tree.points()), "POINTS", pts(tree.points()).toUpperCase()));
        showTooltip();
    }

    private void paint(SkillTree.Node n) {
        boolean hover = n == hovered;
        Color color;
        if (tree.isMaxed(n)) {
            color = hover ? GOLD_HOVER : GOLD;
        } else if (tree.isAllocated(n)) {
            color = hover ? PARTIAL_HOVER : PARTIAL;
        } else if (tree.canAllocate(n)) {
            color = hover ? AVAILABLE_HOVER : AVAILABLE;
        } else {
            color = hover ? LOCKED_HOVER : LOCKED;
        }
        ((TextDisplay) nodeBoxes.get(n).display).setBackgroundColor(color);
        boolean shown = revealed(n);
        conceal(n, !shown);
        halo(n, shown && tree.canAllocate(n));
        TextDisplay badge = rankBadges.get(n);
        if (badge != null) {
            badge.text(shown ? Component.text(tree.rank(n) + "/" + n.ranks(),
                    tree.isMaxed(n) ? TextColor.color(0xE8B84A) : NamedTextColor.WHITE) : Component.empty());
            badge.setBackgroundColor(shown ? BADGE : NONE);
        }
    }

    private void showTooltip() {
        Component text;
        if (resetHovered) {
            text = resetTooltip();
        } else if (hovered != null) {
            SkillTree.Node n = hovered;
            String state = !revealed(n) ? msg().format("state.hidden") : switch (tree.lock(n)) {
                case LEARNED -> msg().format("state.learned");
                case NONE -> msg().format(tree.isAllocated(n) ? "state.next-rank" : "state.learn",
                        "cost", n.cost(), "points", pts(n.cost()));
                case PERMISSION -> n.permissionText();
                case PARENTS -> msg().format("state.parents", "parents", new Messages.Raw(parentList(n)));
                case LEVEL -> msg().format("state.level", "level", n.minLevel(), "max", plugin.skills().maxLevel(player));
                case POINTS -> msg().format("state.points", "cost", n.cost(),
                        "free", Math.max(0, tree.points() - tree.spent()));
            };
            text = revealed(n) ? Messages.parse(n.name(), NamedTextColor.WHITE)
                    : msg().get("screen.hidden-name", NamedTextColor.WHITE);
            if (hovered.ranks() > 1) {
                text = text.append(Component.text(" " + tree.rank(hovered) + "/" + hovered.ranks(),
                        TextColor.color(0xE8B84A)));
            }
            text = text.append(Messages.parse("  ·  " + state, NamedTextColor.GRAY));
        } else if (dragging) {
            text = msg().get("screen.dragging", NamedTextColor.YELLOW);
        } else {
            text = Component.empty();
        }
        tooltip.text(text);
        // Своя подложка: без неё сквозь подсказку просвечивали подписи и узлы дерева под ней.
        // Фон text_display сам подгоняется под длину строки.
        tooltip.setBackgroundColor(text.equals(Component.empty()) ? NONE : PANEL);
        showDetails();
    }

    /** Панель подробностей узла под курсором: описание, цена, требования, эффекты по рангам. */
    private void showDetails() {
        SkillTree.Node n = hovered;
        if (n == null) {
            details.text(Component.empty());
            details.setBackgroundColor(NONE);
            return;
        }
        if (!revealed(n)) {
            placeDetails(List.of(msg().get("screen.hidden-name", NamedTextColor.WHITE),
                    msg().get("details.hidden", TextColor.color(MUTED), "parents", new Messages.Raw(parentList(n)))));
            return;
        }
        int rank = tree.rank(n);
        boolean maxed = tree.isMaxed(n);
        List<Component> lines = new ArrayList<>();
        Component head = Messages.parse(n.name(), NamedTextColor.WHITE);
        if (n.ranks() > 1) {
            head = head.append(Component.text("  " + rank + "/" + n.ranks(), TextColor.color(RANK)));
        }
        lines.add(head);
        if (!n.description().isEmpty()) {
            lines.add(Messages.parse(n.description()
                    + (n.ranks() > 1 ? msg().raw("details.per-rank") : ""), TextColor.color(OK)));
        }
        lines.add(Component.empty());

        if (maxed) {
            String key = n.isStart() ? "details.start" : n.ranks() > 1 ? "details.maxed" : "details.learned";
            lines.add(msg().get(key, TextColor.color(RANK)));
        } else {
            lines.add(msg().get("details.cost", TextColor.color(MUTED), "cost", n.cost(), "points", pts(n.cost()),
                    "per_rank", new Messages.Raw(n.ranks() > 1 ? msg().raw("details.per-rank") : "")));
            if (!n.parents().isEmpty()) {
                boolean has = tree.parentsMet(n);
                lines.add(requirement(has, "details.req-parents", "parents", new Messages.Raw(parentList(n))));
            }
            if (n.minLevel() > 0) {
                int level = plugin.skills().maxLevel(player);
                lines.add(requirement(level >= n.minLevel(), "details.req-level", "level", n.minLevel(), "max", level));
            }
            if (n.permission() != null) {
                boolean has = player.hasPermission(n.permission());
                lines.add(has ? requirement(true, "details.req-permission-ok")
                        : requirement(false, "details.req-permission", "text", new Messages.Raw(n.permissionText())));
            }
            int free = Math.max(0, tree.points() - tree.spent());
            lines.add(requirement(free >= n.cost(), "details.req-points", "free", free));
        }

        List<Component> effects = effectLines(n, rank, maxed);
        if (!effects.isEmpty()) {
            lines.add(Component.empty());
            String heading = rank > 0 && !maxed ? "details.effects-next"
                    : rank == 0 && n.ranks() > 1 ? "details.effects-first" : "details.effects";
            lines.add(msg().get(heading, TextColor.color(MUTED)));
            lines.addAll(effects);
        }
        placeDetails(lines);
    }

    /**
     * Показать строки в панели, верхним краем на {@link #DETAILS_TOP}: text_display растёт вверх от своей
     * точки, поэтому опускаем её на высоту текста — с учётом строк, которые клиент перенесёт сам.
     */
    private void placeDetails(List<Component> lines) {
        int count = 0;
        for (Component line : lines) {
            count += wrappedLines(PlainTextComponentSerializer.plainText().serialize(line), DETAILS_WIDTH);
        }
        // Точка элемента — середина нижней строки (UiSession сдвигает текст на полстроки).
        detailsEl.y = DETAILS_TOP - (count - 0.5) * DETAILS_LINE;
        place(detailsEl, 0);
        details.text(Component.join(JoinConfiguration.newlines(), lines));
        details.setBackgroundColor(PANEL);
    }

    /**
     * Сколько строк займёт текст при переносе по словам на ширину {@code max} px шрифта — как переносит
     * клиент. Ширины символов — стандартного шрифта (у ресурспака могут чуть отличаться: тогда панель
     * сдвинется на строку, но не съедет).
     */
    static int wrappedLines(String text, int max) {
        int lines = 1;
        int width = 0;
        for (String word : text.split(" ", -1)) {
            int w = 0;
            for (int i = 0; i < word.length(); i++) {
                w += charWidth(word.charAt(i));
            }
            if (width == 0) {
                width = w;
            } else if (width + charWidth(' ') + w > max) {
                lines++;
                width = w;
            } else {
                width += charWidth(' ') + w;
            }
            while (width > max) { // слово длиннее строки — клиент рубит его посередине
                lines++;
                width -= max;
            }
        }
        return lines;
    }

    /** Ширина символа стандартного шрифта в px с промежутком (как у клиента). */
    private static int charWidth(char c) {
        return switch (c) {
            case ' ', 'I', 't', '(', ')', '[', ']', '{', '}', '"', '*', '«', '»' -> 4;
            case '!', ',', '.', ':', ';', 'i', '|', '\'', '·' -> 2;
            case 'l', '`' -> 3;
            case '<', '>', 'f', 'k' -> 5;
            case '@', '~' -> 7;
            default -> 6;
        };
    }

    /** Строка требования из messages.yml: зелёная — выполнено, красная — нет. */
    private Component requirement(boolean met, String key, Object... kv) {
        return msg().get(key, TextColor.color(met ? OK : BAD), kv);
    }

    /**
     * «A» или «B» — родители узла (state.parent через state.parents-separator; при parent-mode: all — через
     * state.parents-separator-all, «A» и «B»), уже с разметкой.
     */
    private String parentList(SkillTree.Node n) {
        return n.parents().stream().map(id -> msg().format("state.parent", "name", new Messages.Raw(def.node(id).name())))
                .collect(Collectors.joining(msg().raw(n.allParents() ? "state.parents-separator-all"
                        : "state.parents-separator")));
    }

    /** Эффекты узла числами: у изучаемого — «сейчас → со следующим рангом», иначе — что даёт (весь узел). */
    private List<Component> effectLines(SkillTree.Node n, int rank, boolean maxed) {
        List<Component> out = new ArrayList<>();
        int shown = maxed || rank == 0 ? Math.max(rank, 1) : rank;
        Function<Double, String> hearts = h -> msg().plural("hearts", h);
        for (SkillTree.Modifier m : n.effects().attributes()) {
            String name = msg().attribute(m.attribute());
            String value = formatModifier(m, shown, hearts);
            out.add(rank > 0 && !maxed
                    ? msg().get("details.attribute-next", NamedTextColor.WHITE, "name", name, "value", value,
                            "next", formatModifier(m, rank + 1, hearts))
                    : msg().get("details.attribute", NamedTextColor.WHITE, "name", name, "value", value));
        }
        if (!n.effects().permissions().isEmpty()) {
            out.add(msg().get("details.permissions", NamedTextColor.WHITE));
        }
        if (!n.effects().commands().isEmpty()) {
            out.add(msg().get(n.ranks() > 1 ? "details.commands-rank" : "details.commands", NamedTextColor.WHITE));
        }
        return out;
    }

    /**
     * «+10%», «-25%», «+2», «+1 сердце» (здоровье — в сердцах: 2 единицы = сердце).
     *
     * @param hearts слово «сердце» в нужной форме для числа
     */
    static String formatModifier(SkillTree.Modifier m, int rank, Function<Double, String> hearts) {
        double v = m.amount() * rank;
        if (m.percent()) {
            return signed(v * 100) + "%";
        }
        if (m.attribute().replace("minecraft:", "").equals("max_health")) {
            double h = v / 2;
            return signed(h) + " " + hearts.apply(Math.abs(h));
        }
        return signed(v);
    }

    private static String signed(double v) {
        String num = v == Math.rint(v) ? String.valueOf((long) Math.abs(v))
                : String.valueOf(Math.round(Math.abs(v) * 100) / 100.0).replace('.', ',');
        return (v < 0 ? "-" : "+") + num; // обычный дефис: «−» может не быть в шрифте ресурспака
    }

    private Component resetTooltip() {
        if (tree.learned().isEmpty()) {
            return msg().get("reset.empty", NamedTextColor.GRAY, "tree", new Messages.Raw(def.tab()));
        }
        int spent = tree.spent();
        int cost = def.respec().cost(spent);
        if (resetArmedUntil != 0) {
            return msg().get("reset.armed", NamedTextColor.YELLOW, "tree", new Messages.Raw(def.tab()), "cost", cost);
        }
        boolean enough = player.getLevel() >= cost;
        return msg().get(enough ? "reset.info" : "reset.info-poor", NamedTextColor.GRAY, "tree", new Messages.Raw(def.tab()),
                "refund", spent, "points", pts(spent), "cost", cost, "level", player.getLevel());
    }

    /** Игроку выдали или забрали очки (командой или за уровень), пока экран открыт. */
    void pointsChanged() {
        tree.setBonus(plugin.extraPoints(player, def));
        refresh();
    }

    /** Слово «очко / очка / очков» для числа (plural.points в messages.yml). */
    private String pts(int n) {
        return msg().plural("points", n);
    }

    private Messages msg() {
        return plugin.messages();
    }
}
