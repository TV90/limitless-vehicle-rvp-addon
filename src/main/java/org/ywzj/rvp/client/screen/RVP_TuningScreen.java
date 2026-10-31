package org.ywzj.rvp.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.ywzj.rvp.client.RVP_Keys;
import org.ywzj.rvp.client.tuning.RVP_TuningClient;
import org.ywzj.rvp.client.tuning.RVP_TuningModule;
import org.ywzj.rvp.client.tuning.RVP_TuningOption;
import org.ywzj.rvp.client.tuning.RVP_TuningRegistry;
import org.ywzj.rvp.client.tuning.RVP_TuningViewState;
import org.ywzj.rvp.client.tuning.RVP_TuningViewState.Bounds;
import org.ywzj.rvp.client.tuning.RVP_TuningTheme;
import org.ywzj.rvp.client.tuning.RVP_TuningTheme.CompactButton;
import org.ywzj.rvp.client.tuning.RVP_TuningTheme.CompactEditBox;
import org.ywzj.rvp.client.tuning.RVP_TuningTheme.CompactNumberEditBox;
import net.minecraft.util.FormattedCharSequence;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 数据驱动的本地调参面板；只处理通用控件，不引用光照或其它具体业务类。 */
public final class RVP_TuningScreen extends Screen {
    /** 参考图2251×1260中窗口的左边、顶部、宽度、屏内高度；以比例适应分辨率。 */
    private static final double[] REFERENCE_BOUNDS = {812.0 / 2251, 788.0 / 1260, 1084.0 / 2251, 472.0 / 1260};
    /** 参考图采用单行参数，行距按Minecraft缩放后的GUI像素计算。 */
    private static final int ROW_HEIGHT = 15;
    /** 从 Mods 配置页进入时返回原界面；游戏内打开时为 null。 */
    private final Screen parent;
    /** 业务扩展共用的只读目录和实际值绑定。 */
    private final RVP_TuningRegistry registry;
    /** 会话级窗口和浏览记忆，不与参数覆盖快照混用。 */
    private final RVP_TuningViewState viewState;
    /** 打开面板前的完整覆盖快照，供“撤销本次”恢复，不受窗口缩放影响。 */
    private final Map<String, Double> openingValues;
    /** 按完整分类/分组键保存折叠状态。 */
    private final Set<String> collapsed = new HashSet<>();
    /** 当前左侧选中的分类键。 */
    private String selectedModule;
    /** 搜索文本独立保存，窗口大小变化后不丢失。 */
    private String query = "";
    /** 最近操作的完整反馈；在横幅、配置菜单说明和独立日志中显示。 */
    private String message;
    /** 当前反馈是否为错误，用于改变文字颜色。 */
    private boolean messageError;
    /** 暂时隐藏面板但保持当前对象和调参状态，方便观察短暂的预览。 */
    private boolean observing;
    /** 面板左边界，优先保留左侧世界画面。 */
    private int panelX;
    /** 面板宽度，按 GUI 缩放后的屏幕尺寸计算。 */
    private int panelWidth;
    /** 浮窗顶部；初始位于画面高度约62.5%的位置。 */
    private int panelY;
    /** 浮窗高度，小窗口时保留可操作的最小高度。 */
    private int panelHeight;
    /** 面板底边界。 */
    private int panelBottom;
    /** 左侧分类列表；使用滚动容器，后续增加分类不会溢出。 */
    private TuningList navigation;
    /** 右侧参数和操作列表；容器统一裁剪绘制与鼠标命中。 */
    private TuningList parameters;
    /** 跨分类搜索输入框。 */
    private EditBox search;
    /** 搜索框右侧的本地配置按钮；观察模式下用于返回。 */
    private Button observeButton;
    /** 当前帧标签或反馈栏需要展示的完整说明。 */
    private String hoveredDescription;
    /** 弹出菜单控件，不占用主参数区的行数。 */
    private final List<CompactButton> menuButtons = new ArrayList<>();
    /** 弹出菜单是否展开；展开时阻止误点背后的参数。 */
    private boolean menuOpen;
    /** 弹出菜单左边界。 */
    private int menuX;
    /** 弹出菜单顶部，优先显示在下方浮窗的上方。 */
    private int menuY;
    /** 弹出菜单宽度。 */
    private int menuWidth;
    /** 菜单当前的键盘焦点行。 */
    private int menuFocus;
    /** 当前是否拖动标题栏。 */
    private boolean draggingPanel;
    /** 拖动起点相对浮窗左边的偏移。 */
    private int dragOffsetX;
    /** 拖动起点相对浮窗顶部的偏移。 */
    private int dragOffsetY;
    /** 最近一次布局使用的GUI宽度，重建时用于按原尺度保存位置。 */
    private int layoutWidth;
    /** 最近一次布局使用的GUI高度。 */
    private int layoutHeight;
    /** 当前拖动的边框位标记，0表示没有调整窗框大小。 */
    private int resizingEdges;
    /** 缩放按下时的窗口矩形，逐帧从该矩形计算，避免累积误差。 */
    private Bounds resizeStartBounds;
    /** 缩放按下时的鼠标GUI横坐标。 */
    private double resizeStartX;
    /** 缩放按下时的鼠标GUI纵坐标。 */
    private double resizeStartY;
    /** 正在拖动的滑条，越出列表或窗口仍把后续拖动和松开交给同一控件。 */
    private OptionSlider activeSlider;
    /** 反馈横幅的截止时间；不参与任何特效的游戏内时间计算。 */
    private long noticeUntil;

    /** 菜单只描述通用操作，不引用具体特效。 */
    private record MenuAction(
            /** 菜单文字。 */ String title,
            /** 本地操作。 */ Runnable action) { }

    public RVP_TuningScreen(Screen parent) {
        super(Component.literal("配置"));
        this.parent = parent;
        // 调用已初始化目录及业务快照，撤销时精确保留原先没有覆盖的参数。
        registry = RVP_TuningClient.registry();
        viewState = RVP_TuningClient.viewState();
        openingValues = registry.snapshot();
        selectedModule = registry.modules().isEmpty() ? "" : registry.modules().get(0).id();
        // 仅恢复浏览状态；本次打开前的业务快照仍独立采集，撤销不会影响上一次已完成的调整。
        viewState.snapshot().ifPresent(saved -> {
            if (registry.modules().stream().anyMatch(module -> module.id().equals(saved.selectedModule()))) {
                selectedModule = saved.selectedModule();
            }
            query = saved.query();
            collapsed.addAll(saved.collapsed());
        });
        message = RVP_TuningClient.startupMessage();
        // 调用独立日志，记录实际进入界面时使用的模块和覆盖，便于区分启动未完成与布局错误。
        RVP_TuningClient.log().info("SCREEN_CREATED modules=" + registry.modules().size() + " options="
                + registry.options().size() + " overrides=" + openingValues);
    }

    @Override
    protected void init() {
        try {
            initializePanel();
        } catch (RuntimeException | LinkageError failure) {
            RVP_TuningClient.log().error("LAYOUT_FAILED gui=" + width + "x" + height, failure);
            throw failure;
        }
    }

    /** 初始化与渲染分开记录，某个控件创建失败也能在独立日志中定位。 */
    private void initializePanel() {
        // GUI缩放或分辨率变化会重建同一Screen，先按旧布局尺寸记住当前位置，再换算到新尺寸。
        rememberView("rebuild");
        panelWidth = Math.min(width - 8, Math.max(320, (int) Math.round(width * REFERENCE_BOUNDS[2])));
        panelHeight = Math.min(height - 8, Math.max(150, (int) Math.round(height * REFERENCE_BOUNDS[3])));
        panelX = Math.max(4, Math.min(width - panelWidth - 4, (int) Math.round(width * REFERENCE_BOUNDS[0])));
        panelY = Math.max(4, Math.min(height - panelHeight, (int) Math.round(height * REFERENCE_BOUNDS[1])));
        Bounds restored = viewState.windowFor(width, height, new Bounds(panelX, panelY, panelWidth, panelHeight));
        panelX = restored.left();
        panelY = restored.top();
        panelWidth = restored.width();
        panelHeight = restored.height();
        panelBottom = restored.bottom();
        layoutWidth = width;
        layoutHeight = height;
        draggingPanel = false;
        resizingEdges = 0;
        activeSlider = null;
        menuOpen = false;
        menuButtons.clear();
        observeButton = addRenderableWidget(new CompactButton(font, "本地配置", () -> {
            if (observing) { observing = false; }
            else if (menuOpen) { closeMenu(); }
            else { openLocalMenu(); }
        }));
        search = addRenderableWidget(new CompactEditBox(font, "搜索全部分类的键名或标签"));
        search.setMaxLength(160);
        search.setHint(Component.literal("搜索键名或标签"));
        search.setValue(query);
        navigation = addRenderableWidget(new TuningList(0, 100, 0, 100, ROW_HEIGHT));
        parameters = addRenderableWidget(new TuningList(0, 100, 0, 100, ROW_HEIGHT));
        // 调用统一布局，拖动标题栏时也只移动控件，不重建正在编辑的输入框。
        positionWidgets();
        search.setResponder(value -> {
            query = value;
            // 调用通用搜索重建显示，不修改任何实际参数或原来的折叠状态。
            rebuildRows(false);
            RVP_TuningClient.log().info("SEARCH query=" + value + " displayedRows=" + parameters.children().size());
        });
        rebuildRows(false);
        viewState.snapshot().ifPresent(saved -> {
            navigation.setScrollAmount(saved.navigationScroll());
            parameters.setScrollAmount(saved.parameterScroll());
            RVP_TuningClient.log().info("VIEW_STATE_RESTORE module=" + selectedModule + " query=" + query
                    + " collapsed=" + collapsed + " navigationScroll=" + navigation.getScrollAmount()
                    + " parameterScroll=" + parameters.getScrollAmount() + " bounds=" + panelBounds());
        });
        // 记录真实像素与GUI缩放后的坐标，直接通过日志核对参考图中的位置比例。
        RVP_TuningClient.log().info("LAYOUT gui=" + width + "x" + height + " pixels="
                + minecraft.getWindow().getWidth() + "x" + minecraft.getWindow().getHeight()
                + " guiScale=" + minecraft.getWindow().getGuiScale() + " panel=" + panelX + "," + panelY
                + "," + panelWidth + "," + panelHeight + " rowHeight=" + ROW_HEIGHT
                + " parameterArea=" + parameters.getLeft() + "," + parameters.getTop() + ","
                + parameters.getWidth() + "," + (parameters.getBottom() - parameters.getTop()));
    }

    /** 窗口按参考图分为约20%导航和80%参数区；右上操作与搜索在同一行。 */
    private void positionWidgets() {
        int sidebarWidth = (int) Math.round(panelWidth * 0.205);
        observeButton.setX(panelX + panelWidth - sidebarWidth - 5);
        observeButton.setY(panelY + 22);
        observeButton.setWidth(sidebarWidth);
        observeButton.setHeight(12);
        search.setX(panelX + 8);
        search.setY(panelY + 24);
        search.setWidth(panelWidth - sidebarWidth - 19);
        search.setHeight(9);
        int top = panelY + 36;
        int bottom = Math.min(height - 2, panelBottom - 3);
        navigation.position(panelX + 4, sidebarWidth, top, bottom);
        parameters.position(panelX + sidebarWidth + 9, panelWidth - sidebarWidth - 15, top, bottom);
        // 窗口变大或变小时按新的可滚范围裁剪，不能保留已超出末尾的滚动量。
        navigation.setScrollAmount(navigation.getScrollAmount());
        parameters.setScrollAmount(parameters.getScrollAmount());
    }

    private Bounds panelBounds() { return new Bounds(panelX, panelY, panelWidth, panelHeight); }

    /** 移动与缩放共用位置更新，不重新创建输入框或参数行。 */
    private void applyBounds(Bounds bounds) {
        panelX = bounds.left();
        panelY = bounds.top();
        panelWidth = bounds.width();
        panelHeight = bounds.height();
        panelBottom = bounds.bottom();
        positionWidgets();
    }

    /** 离开或重建前记录完整浏览位置；内存快照不改写参数预设文件。 */
    private void rememberView(String reason) {
        if (navigation == null || parameters == null || layoutWidth <= 0 || layoutHeight <= 0) { return; }
        viewState.remember(new RVP_TuningViewState.Snapshot(panelBounds(), layoutWidth, layoutHeight,
                selectedModule, query, collapsed, navigation.getScrollAmount(), parameters.getScrollAmount()));
        RVP_TuningClient.log().info("VIEW_STATE_SAVE reason=" + reason + " module=" + selectedModule + " query=" + query
                + " collapsed=" + collapsed + " navigationScroll=" + navigation.getScrollAmount()
                + " parameterScroll=" + parameters.getScrollAmount() + " bounds=" + panelBounds());
    }

    @Override
    public void removed() {
        // 标准Screen离开回调覆盖F8、Esc、菜单关闭以及被其它界面替换的所有路径。
        rememberView("removed");
        activeSlider = null;
        draggingPanel = false;
        resizingEdges = 0;
        closeMenu();
        super.removed();
    }

    /** 保存和观察等常规操作不再占据参考图中没有的底部大按钮区。 */
    private void openLocalMenu() {
        RVP_TuningClient.log().info("MENU_OPEN scope=local module=" + selectedModule);
        openMenu(observeButton.getX(), observeButton.getY(), List.of(
                new MenuAction("保存本地", this::save),
                new MenuAction("撤销本次", () -> {
                    // 调用完整快照恢复，不改已经保存到磁盘的预设。
                    registry.restore(openingValues);
                    clearInputErrors();
                    feedback("已撤销本次调整；本地预设文件未改动。", false);
                }),
                new MenuAction("恢复当前分类", this::resetModule),
                new MenuAction("观察画面", () -> observing = true),
                new MenuAction("关闭面板", this::onClose)));
    }

    private void openGroupMenu(RVP_TuningModule.Group group, int x, int y) {
        RVP_TuningClient.log().info("MENU_OPEN scope=group group=" + group.id());
        List<MenuAction> actions = new ArrayList<>();
        for (RVP_TuningModule.Action action : group.actions()) {
            actions.add(new MenuAction(action.title(), () -> {
                // 调用扩展注册的本地操作，界面不判断预览的是哪一种特效。
                feedback(action.execute().get(), false);
                observing = action.showWorldAfter();
            }));
        }
        actions.add(new MenuAction("恢复本组默认", () -> {
            group.options().forEach(RVP_TuningOption::reset);
            clearInputErrors();
            feedback("已恢复“" + group.title() + "”的默认设置。", false);
        }));
        openMenu(x, y, actions);
    }

    private void openMenu(int anchorX, int anchorY, List<MenuAction> actions) {
        closeMenu();
        for (CompactButton button : menuButtons) { removeWidget(button); }
        menuButtons.clear();
        menuWidth = Math.min(width - 8, 158);
        int menuHeight = actions.size() * 16 + 6;
        menuX = Math.max(4, Math.min(width - menuWidth - 4, anchorX));
        menuY = Math.max(4, Math.min(height - menuHeight - 4, anchorY - menuHeight - 3));
        for (int index = 0; index < actions.size(); index++) {
            MenuAction action = actions.get(index);
            CompactButton button = new CompactButton(font, action.title(), () -> {
                closeMenu();
                try {
                    RVP_TuningClient.log().info("ACTION name=" + action.title());
                    action.action().run();
                    RVP_TuningClient.log().info("ACTION_DONE name=" + action.title() + " overrides=" + registry.snapshot());
                } catch (RuntimeException failure) {
                    RVP_TuningClient.log().error("ACTION_FAILED name=" + action.title(), failure);
                    feedback(failure.getMessage(), true);
                }
            });
            button.setX(menuX + 3);
            button.setY(menuY + 3 + index * 16);
            button.setWidth(menuWidth - 6);
            // 菜单参与焦点路由，绘制则由浮动层负责，防止被内容列表覆盖。
            addWidget(button);
            menuButtons.add(button);
        }
        menuOpen = true;
        menuFocus = 0;
        setFocused(menuButtons.get(0));
    }

    private void closeMenu() {
        menuOpen = false;
        for (CompactButton button : menuButtons) { button.visible = false; button.setFocused(false); }
        if (menuButtons.contains(getFocused())) { setFocused(null); }
    }

    /** 只重建内容列表，搜索框与当前文本焦点不被整屏 init 打断。 */
    private void rebuildRows(boolean keepScroll) {
        double previous = keepScroll ? parameters.getScrollAmount() : 0;
        navigation.clear();
        for (RVP_TuningModule module : registry.modules()) { navigation.add(new NavigationEntry(module)); }
        parameters.clear();
        String lastModule = "";
        // 调用目录的全局搜索；界面完全不按模块实现类或参数键判断内容。
        for (RVP_TuningRegistry.Section section : registry.search(selectedModule, query)) {
            if (!lastModule.equals(section.module().id())) {
                parameters.add(new CaptionEntry(section.module()));
                lastModule = section.module().id();
            }
            String key = section.module().id() + "/" + section.group().id();
            boolean expanded = !query.isBlank() || !collapsed.contains(key);
            parameters.add(new GroupEntry(section, key, expanded));
            if (!expanded) { continue; }
            for (RVP_TuningOption option : section.options()) {
                parameters.add(new OptionEntry(section.module().id() + "/" + section.group().id() + "/" + option.id(), option));
            }
        }
        parameters.setScrollAmount(previous);
    }

    private void save() {
        for (Entry entry : parameters.children()) {
            if (entry instanceof OptionEntry option && option.inputError != null) {
                feedback("请先修正红色数值，或右键该行恢复默认。", true);
                return;
            }
        }
        try {
            // 调用共用本地预设保存，成功后重启客户端也会保留，不写武器/载具 JSON。
            RVP_TuningClient.save();
            feedback("已保存到 config/ywzj_rvp-tuning-client.json；下次启动自动载入。", false);
        } catch (IOException failure) { feedback("保存失败：" + failure.getMessage(), true); }
    }

    private void resetModule() {
        for (RVP_TuningModule module : registry.modules()) {
            if (!module.id().equals(selectedModule)) { continue; }
            // 调用当前选中分类的单项清理；搜索命中的其它分类不会被连带重置。
            for (RVP_TuningModule.Group group : module.groups()) { group.options().forEach(RVP_TuningOption::reset); }
            clearInputErrors();
            feedback("已恢复“" + module.title() + "”的默认设置；保存本地后才改动预设文件。", false);
        }
    }

    private void clearInputErrors() {
        for (Entry entry : parameters.children()) {
            if (entry instanceof OptionEntry option) { option.syncInput(); }
        }
    }

    private void feedback(String text, boolean error) {
        message = text == null ? "操作失败，请查看日志。" : text;
        messageError = error;
        noticeUntil = System.currentTimeMillis() + 5000;
        // 提示文字同样落入独立文件；异常堆栈由具体失败入口另外记录，不在每帧重复写入。
        if (error) { RVP_TuningClient.log().warn("FEEDBACK " + message); }
        else { RVP_TuningClient.log().info("FEEDBACK " + message); }
    }

    @Override
    public void tick() {
        search.tick();
        for (Entry entry : parameters.children()) {
            if (entry instanceof OptionEntry option && option.input != null) { option.input.tick(); }
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        try {
            renderPanel(graphics, mouseX, mouseY, partialTick);
        } catch (RuntimeException | LinkageError failure) {
            RVP_TuningClient.log().error("RENDER_FAILED gui=" + width + "x" + height + " panel="
                    + panelX + "," + panelY + "," + panelWidth + "," + panelHeight
                    + " observing=" + observing + " menu=" + menuOpen, failure);
            throw failure;
        }
    }

    /** 每帧只绘制；正常绘制不写日志，只有包装入口捕获到异常时才输出堆栈。 */
    private void renderPanel(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        hoveredDescription = null;
        if (minecraft.level == null) { renderBackground(graphics); }
        if (observing) {
            RVP_TuningTheme.panel(graphics, panelX, panelY, panelWidth, 38);
            drawCentered(graphics, "— 观察画面", panelX, panelY + 8, panelWidth, RVP_TuningTheme.TEXT);
            observeButton.setMessage(Component.literal("返回调参"));
            observeButton.render(graphics, mouseX, mouseY, partialTick);
            return;
        }
        observeButton.setMessage(Component.literal("本地配置"));
        RVP_TuningTheme.panel(graphics, panelX, panelY, panelWidth, panelHeight);
        drawCentered(graphics, "— 配置", panelX, panelY + 8, panelWidth, RVP_TuningTheme.TEXT);
        graphics.renderOutline(parameters.getLeft() - 2, parameters.getTop() - 1,
                parameters.getWidth() + 3, parameters.getBottom() - parameters.getTop() + 2, 0xFF24202B);
        graphics.vLine(parameters.getLeft() - 3, parameters.getTop() - 2, parameters.getBottom(), 0xFF9C93A7);
        super.render(graphics, mouseX, mouseY, partialTick);
        // 右下角保留窄斜线手柄；四条外边框也可缩放，便于从初始贴底位置向上扩展。
        graphics.hLine(panelX + panelWidth - 10, panelX + panelWidth - 5, panelBottom - 5, 0xFFB1A7BB);
        graphics.hLine(panelX + panelWidth - 7, panelX + panelWidth - 5, panelBottom - 8, 0xFFB1A7BB);
        if (parameters.children().isEmpty()) {
            drawCentered(graphics, "没有匹配的参数", parameters.getLeft(), parameters.getTop() + 8,
                    parameters.getWidth(), RVP_TuningTheme.TEXT);
        }
        if (observeButton.isMouseOver(mouseX, mouseY)) {
            // 调用业务快照比较，命令产生的调整也显示尚未保存状态。
            hoveredDescription = "本机画面配置：保存、撤销、恢复分类、观察画面与关闭。\n"
                    + (RVP_TuningClient.hasUnsavedChanges() ? "当前有未保存的调整。" : "当前数值与本地预设一致。")
                    + "\n分组标题：左键折叠，右键打开预览与重置。\n" + message;
        } else if (mouseX >= panelX && mouseX < panelX + panelWidth && mouseY >= panelY && mouseY < panelY + 20) {
            hoveredDescription = "拖动标题栏移动窗口；拖动四条边框或右下角调整大小。\n关闭后再次打开会记住窗口和浏览位置。";
        }
        if (panelBounds().resizeEdges(mouseX, mouseY) != 0 && !menuOpen) {
            hoveredDescription = "拖动边框调整窗口大小；可拖动上边框向上扩展。";
        }
        if (noticeUntil > System.currentTimeMillis() && !menuOpen) {
            int noticeY = Math.max(2, panelY - 15);
            RVP_TuningTheme.roundedFill(graphics, panelX, noticeY, panelWidth, 13, 0xEA111014);
            graphics.drawString(font, font.plainSubstrByWidth(message, panelWidth - 8), panelX + 4, noticeY + 2,
                    messageError ? 0xFFFF8C8C : 0xFFE3DDD0, false);
        }
        if (menuOpen) {
            hoveredDescription = null;
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 100);
            RVP_TuningTheme.panel(graphics, menuX, menuY, menuWidth, menuButtons.size() * 16 + 6);
            for (CompactButton button : menuButtons) { button.render(graphics, mouseX, mouseY, partialTick); }
            graphics.pose().popPose();
        }
        if (hoveredDescription != null) { drawDescription(graphics, hoveredDescription, mouseX, mouseY); }
    }

    /** 参考图采用宽黑底、浅色细边的说明框，优先放在鼠标左侧。 */
    private void drawDescription(GuiGraphics graphics, String text, int mouseX, int mouseY) {
        int maxWidth = Math.max(100, Math.min(width - 16, (int) (width * 0.64)));
        List<FormattedCharSequence> lines = font.split(Component.literal(text), maxWidth - 10);
        int textWidth = 0;
        for (FormattedCharSequence line : lines) { textWidth = Math.max(textWidth, font.width(line)); }
        int boxWidth = Math.min(width - 8, textWidth + 10);
        int boxHeight = Math.min(height - 8, lines.size() * 10 + 8);
        int left = Math.max(4, Math.min(width - boxWidth - 4, mouseX - boxWidth - 10));
        int top = Math.max(4, Math.min(height - boxHeight - 4, mouseY - boxHeight / 2));
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 200);
        RVP_TuningTheme.roundedFill(graphics, left, top, boxWidth, boxHeight, 0xFFF1F0E7);
        RVP_TuningTheme.roundedFill(graphics, left + 1, top + 1, boxWidth - 2, boxHeight - 2, 0xED080909);
        for (int index = 0; index < lines.size() && index * 10 + 12 <= boxHeight; index++) {
            graphics.drawString(font, lines.get(index), left + 5, top + 4 + index * 10, 0xFFF3F1E9, false);
        }
        graphics.pose().popPose();
    }

    private void drawCentered(GuiGraphics graphics, String text, int left, int top, int availableWidth, int color) {
        String label = font.plainSubstrByWidth(text, Math.max(0, availableWidth - 4));
        graphics.drawString(font, label, left + (availableWidth - font.width(label)) / 2, top, color, false);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (RVP_Keys.OPEN_TUNING.matches(keyCode, scanCode) || observing && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (observing) { observing = false; } else { onClose(); }
            return true;
        }
        if (observing) { return true; }
        if (menuOpen) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) { closeMenu(); }
            else if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER || keyCode == GLFW.GLFW_KEY_SPACE) {
                menuButtons.get(menuFocus).onPress();
            } else if (keyCode == GLFW.GLFW_KEY_DOWN || keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_TAB) {
                int direction = keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_TAB && hasShiftDown() ? -1 : 1;
                menuFocus = Math.floorMod(menuFocus + direction, menuButtons.size());
                setFocused(menuButtons.get(menuFocus));
            }
            return true;
        }
        if (hasControlDown() && keyCode == GLFW.GLFW_KEY_F) {
            setFocused(search);
            search.setFocused(true);
            search.moveCursorToEnd();
            search.setHighlightPos(0);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override public boolean charTyped(char codePoint, int modifiers) { return observing || menuOpen || super.charTyped(codePoint, modifiers); }
    @Override public boolean mouseClicked(double x, double y, int button) {
        if (observing) { return observeButton.mouseClicked(x, y, button); }
        if (menuOpen) {
            for (CompactButton item : menuButtons) { if (item.mouseClicked(x, y, button)) { return true; } }
            closeMenu();
            return true;
        }
        if (button == 0) {
            resizingEdges = panelBounds().resizeEdges(x, y);
            if (resizingEdges != 0) {
                resizeStartBounds = panelBounds();
                resizeStartX = x;
                resizeStartY = y;
                setFocused(null);
                return true;
            }
        }
        if (button == 0 && x >= panelX + 3 && x < panelX + panelWidth - 3 && y >= panelY + 3 && y < panelY + 20) {
            draggingPanel = true;
            dragOffsetX = (int) x - panelX;
            dragOffsetY = (int) y - panelY;
            setFocused(null);
            return true;
        }
        return super.mouseClicked(x, y, button);
    }
    @Override public boolean mouseReleased(double x, double y, int button) {
        if (button == 0 && resizingEdges != 0) {
            resizingEdges = 0;
            rememberView("resize");
            RVP_TuningClient.log().info("PANEL_RESIZED bounds=" + panelBounds());
            return true;
        }
        if (draggingPanel) {
            draggingPanel = false;
            rememberView("move");
            RVP_TuningClient.log().info("PANEL_MOVED bounds=" + panelX + "," + panelY + "," + panelWidth + "," + panelHeight);
            return true;
        }
        if (button == 0 && activeSlider != null) {
            // 松开发生在列表外时也结束同一次拖动，不能让控件残留按下状态。
            OptionSlider released = activeSlider;
            activeSlider = null;
            released.onRelease(x, y);
            setDragging(false);
            parameters.setDragging(false);
            if (parameters.getFocused() != null) { parameters.getFocused().setDragging(false); }
            return true;
        }
        return observing || menuOpen || super.mouseReleased(x, y, button);
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (button == 0 && resizingEdges != 0) {
            // 每次都以鼠标按下时的矩形为基准，边框被最小尺寸或屏幕边界约束时不会累加漂移。
            applyBounds(resizeStartBounds.resized(resizingEdges, (int) Math.round(x - resizeStartX),
                    (int) Math.round(y - resizeStartY), width, height));
            return true;
        }
        if (draggingPanel && button == 0) {
            applyBounds(new Bounds((int) x - dragOffsetX, (int) y - dragOffsetY, panelWidth, panelHeight).fit(width, height));
            return true;
        }
        if (button == 0 && activeSlider != null) {
            // 使用相同控件持续接收GUI坐标，越过滑条两端时由原版滑条夹紧到0或1。
            return activeSlider.mouseDragged(x, y, button, dx, dy);
        }
        return observing || menuOpen || super.mouseDragged(x, y, button, dx, dy);
    }
    @Override public boolean mouseScrolled(double x, double y, double delta) { return observing || menuOpen || super.mouseScrolled(x, y, delta); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() {
        RVP_TuningClient.log().info("SCREEN_CLOSED unsaved=" + RVP_TuningClient.hasUnsavedChanges()
                + " smoothCamera=" + minecraft.options.smoothCamera + " overrides=" + registry.snapshot());
        minecraft.setScreen(parent);
    }

    /** 两栏复用同一种有裁剪和焦点路由的原版滚动容器。 */
    private final class TuningList extends ContainerObjectSelectionList<RVP_TuningScreen.Entry> {
        private TuningList(int left, int listWidth, int top, int bottom, int rowHeight) {
            super(RVP_TuningScreen.this.minecraft, listWidth, RVP_TuningScreen.this.height, top, bottom, rowHeight);
            setLeftPos(left);
            setRenderBackground(false);
            setRenderTopAndBottom(false);
            setRenderSelection(false);
        }
        private void position(int left, int listWidth, int top, int bottom) {
            updateSize(listWidth, RVP_TuningScreen.this.height, top, bottom);
            setLeftPos(left);
        }
        @Override public int getRowWidth() { return getWidth() - (getMaxScroll() > 0 ? 9 : 4); }
        @Override public int getRowLeft() { return getLeft() + 2; }
        @Override protected int getScrollbarPosition() { return getRight() - 5; }
        private void clear() { clearEntries(); }
        private void add(RVP_TuningScreen.Entry entry) { addEntry(entry); }
        @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            super.render(graphics, mouseX, mouseY, partialTick);
            if (getMaxScroll() > 0) {
                int viewHeight = getBottom() - getTop();
                int thumbHeight = Math.max(10, Math.min(viewHeight - 2, viewHeight * viewHeight / Math.max(1, getMaxPosition())));
                int thumbTop = getTop() + (int) Math.round(getScrollAmount() * (viewHeight - thumbHeight) / getMaxScroll());
                // 绘制同一暗槽窄滚动条，沿用原版的滚轮、拖动和焦点处理。
                RVP_TuningTheme.scrollbar(graphics, getScrollbarPosition(), getTop(), getBottom(), thumbTop, thumbHeight);
            }
        }
    }

    /** 各种行只提供通用的子控件与说明，鼠标命中交给容器统一处理。 */
    private abstract class Entry extends ContainerObjectSelectionList.Entry<Entry> {
        /** 行内可交互控件，既用于鼠标/键盘焦点，也用于旁白。 */
        protected final List<AbstractWidget> widgets = new ArrayList<>();
        @Override public List<? extends GuiEventListener> children() { return widgets; }
        @Override public List<? extends NarratableEntry> narratables() { return widgets; }

        protected void drawWidgets(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            for (AbstractWidget widget : widgets) { widget.render(graphics, mouseX, mouseY, partialTick); }
        }
        protected void describe(String text, int mouseX, int mouseY, int left, int top, int rowWidth, int rowHeight) {
            if (parameters.isMouseOver(mouseX, mouseY) && mouseX >= left && mouseX < left + rowWidth
                    && mouseY >= top && mouseY < top + ROW_HEIGHT) { hoveredDescription = text; }
        }
    }

    private final class NavigationEntry extends Entry {
        /** 导航行绑定的模块描述，不依赖具体模块实现。 */
        private final RVP_TuningModule module;
        /** 切换分类时重建右栏，但不改动参数值。 */
        private final Button button;
        private NavigationEntry(RVP_TuningModule module) {
            this.module = module;
            button = new CompactButton(font, module.title(), () -> {
                selectedModule = module.id();
                RVP_TuningClient.log().info("SELECT_MODULE id=" + selectedModule);
                query = "";
                search.setValue("");
                // 延后重建，避免容器正在分发点击时把当前导航行移除。
                minecraft.tell(() -> rebuildRows(false));
            });
            widgets.add(button);
        }
        @Override public void render(GuiGraphics graphics, int index, int top, int left, int rowWidth, int rowHeight,
                                     int mouseX, int mouseY, boolean hovered, float partialTick) {
            button.setX(left);
            button.setY(top);
            button.setWidth(rowWidth);
            button.setFGColor(module.id().equals(selectedModule) ? RVP_TuningTheme.SELECTED : RVP_TuningTheme.TEXT);
            drawWidgets(graphics, mouseX, mouseY, partialTick);
            if (button.isMouseOver(mouseX, mouseY)) { hoveredDescription = module.description(); }
        }
    }

    /** 参考图在右栏顶部单独显示分类名和数量，再展示可折叠分组。 */
    private final class CaptionEntry extends Entry {
        /** 当前分类标题与总参数数。 */
        private final String caption;
        private CaptionEntry(RVP_TuningModule module) {
            int count = module.groups().stream().mapToInt(group -> group.options().size()).sum();
            caption = module.title() + " (" + count + ")";
        }
        @Override public void render(GuiGraphics graphics, int index, int top, int left, int rowWidth, int rowHeight,
                                     int mouseX, int mouseY, boolean hovered, float partialTick) {
            graphics.drawString(font, font.plainSubstrByWidth(caption, rowWidth - 3), left + 1, top + 2, RVP_TuningTheme.HEADING, false);
        }
    }

    private final class GroupEntry extends Entry {
        /** 当前搜索结果与原始分组定义。 */
        private final RVP_TuningRegistry.Section section;
        /** 折叠按钮，不改变业务参数。 */
        private final Button heading;
        private GroupEntry(RVP_TuningRegistry.Section section, String key, boolean expanded) {
            this.section = section;
            String title = (expanded ? "▾ " : "▸ ") + section.group().title() + " (" + section.options().size() + ")";
            heading = new CompactButton(font, title, () -> {
                if (!collapsed.add(key)) { collapsed.remove(key); }
                RVP_TuningClient.log().info("GROUP_TOGGLE key=" + key + " collapsed=" + collapsed.contains(key));
                minecraft.tell(() -> rebuildRows(true));
            });
            heading.setFGColor(RVP_TuningTheme.HEADING);
            widgets.add(heading);
        }
        @Override public boolean mouseClicked(double x, double y, int button) {
            if (button == 1 && heading.isMouseOver(x, y)) {
                openGroupMenu(section.group(), (int) x, heading.getY());
                return true;
            }
            return super.mouseClicked(x, y, button);
        }
        @Override public void render(GuiGraphics graphics, int index, int top, int left, int rowWidth, int rowHeight,
                                     int mouseX, int mouseY, boolean hovered, float partialTick) {
            heading.setX(left);
            heading.setY(top);
            heading.setWidth(rowWidth);
            describe(section.module().title() + " / " + section.group().title() + "\n" + section.group().description()
                            + "\n左键折叠；右键打开本组预览与恢复默认。",
                    mouseX, mouseY, left, top, rowWidth, rowHeight);
            drawWidgets(graphics, mouseX, mouseY, partialTick);
        }
    }

    private final class OptionEntry extends Entry {
        /** 日志中的完整稳定键，避免不同分组的brightness等同名参数混淆。 */
        private final String parameterKey;
        /** 模块提供的业务绑定；命令修改后每帧从这里读取。 */
        private final RVP_TuningOption option;
        /** 数值输入框；开关行不创建。 */
        private final CompactNumberEditBox input;
        /** 数值滑条；开关行不创建。 */
        private final OptionSlider slider;
        /** 开关或中文选择按钮；数值行不创建。 */
        private final Button toggle;
        /** 输入不完整、越界或被业务层拒绝的原因；原有效值保持不变。 */
        private String inputError;
        /** 最近绘制的行左边界，供整行右键重置使用。 */
        private int rowLeft;
        /** 最近绘制的行上边界。 */
        private int rowTop;
        /** 最近绘制的行宽度。 */
        private int renderedWidth;

        private OptionEntry(String parameterKey, RVP_TuningOption option) {
            this.parameterKey = parameterKey;
            this.option = option;
            if (option.control() == RVP_TuningOption.Control.NUMBER) {
                slider = new OptionSlider(this);
                toggle = null;
                input = new CompactNumberEditBox(font, option.title());
                input.setMaxLength(32);
                syncInput();
                input.setResponder(text -> {
                    try {
                        double previous = option.value();
                        // 调用通用校验和业务写入；只有合法的有限数值才能改变实际效果。
                        option.set(Double.parseDouble(text.strip()));
                        inputError = null;
                        input.setTextColor(RVP_TuningTheme.TEXT);
                        if (previous != option.value()) {
                            RVP_TuningClient.log().info("PARAM source=text key=" + parameterKey + " previous=" + previous + " value=" + option.value());
                        }
                    } catch (IllegalArgumentException failure) {
                        String previousError = inputError;
                        inputError = "数值未应用：" + (failure instanceof NumberFormatException
                                ? "请输入完整数字，可用小数或科学计数法。" : failure.getMessage());
                        input.setTextColor(0xFF7777);
                        // 同一次尚未输完的数字只记录一次相同原因，不随每个字符重复刷警告。
                        if (!inputError.equals(previousError)) {
                            RVP_TuningClient.log().warn("INPUT_REJECT key=" + parameterKey + " input=" + text + " reason=" + inputError);
                        }
                    }
                });
                widgets.add(slider);
                widgets.add(input);
            } else {
                input = null;
                slider = null;
                toggle = new CompactButton(font, "", () -> {
                    // 调用通用按钮取值及业务写入入口；曲线循环选择整数项，开关仍严格使用0或1。
                    option.set(option.nextButtonValue());
                    RVP_TuningClient.log().info("PARAM source=button key=" + parameterKey + " value=" + option.value());
                });
                widgets.add(toggle);
            }
        }

        @Override public boolean mouseClicked(double x, double y, int button) {
            if (button == 1 && x >= rowLeft && x < rowLeft + renderedWidth && y >= rowTop && y < rowTop + ROW_HEIGHT) {
                // 右键本行恢复默认，避免常驻重置按钮挤占参考图的三列布局。
                option.reset();
                syncInput();
                RVP_TuningClient.log().info("PARAM_RESET key=" + parameterKey + " default=" + option.value());
                feedback("已恢复“" + option.title() + "”的默认值。", false);
                return true;
            }
            return super.mouseClicked(x, y, button);
        }

        private void syncInput() {
            inputError = null;
            if (input == null) { return; }
            // 调用数值框的只读回填，保持整数开头可见，并隔离格式化与光标事件产生的反向写入。
            input.syncValue(RVP_TuningOption.format(option.value()));
            input.setTextColor(RVP_TuningTheme.TEXT);
        }

        @Override public void render(GuiGraphics graphics, int index, int top, int left, int rowWidth, int rowHeight,
                                     int mouseX, int mouseY, boolean hovered, float partialTick) {
            rowLeft = left;
            rowTop = top;
            renderedWidth = rowWidth;
            int labelWidth = (int) (rowWidth * 0.41);
            int valueWidth = Math.max(43, (int) (rowWidth * 0.17));
            int controlLeft = left + labelWidth;
            // 调用业务覆盖检查；已调整项只用淡暖色文字标记，不增加第二行状态标签。
            int color = option.overrideValue().isPresent() ? 0xFFE0D3B1 : RVP_TuningTheme.TEXT;
            graphics.drawString(font, font.plainSubstrByWidth(option.title(), labelWidth - 5), left + 1, top + 3, color, false);
            if (input != null) {
                input.setX(left + rowWidth - valueWidth + 3);
                input.setY(top + 4);
                input.setWidth(valueWidth - 6);
                input.setHeight(9);
                slider.setX(controlLeft);
                slider.setY(top + 1);
                slider.setWidth(rowWidth - labelWidth - valueWidth - 4);
                slider.readValue();
                if (!input.isFocused() && inputError == null
                        && !input.getValue().equals(RVP_TuningOption.format(option.value()))) { syncInput(); }
            } else {
                toggle.setX(controlLeft);
                toggle.setY(top + 1);
                toggle.setWidth(rowWidth - labelWidth);
                toggle.setHeight(12);
                // 调用参数自己的中文值说明，通用界面不认识具体光晕曲线。
                toggle.setMessage(Component.literal(option.buttonText()));
            }
            String description = option.title() + "（" + option.id() + "）：" + option.description()
                    + "\nDefault: " + RVP_TuningOption.format(option.defaultValue()) + "\nRange: "
                    + RVP_TuningOption.format(option.min()) + " ～ " + RVP_TuningOption.format(option.max()) + " " + option.unit()
                    + "；右键本行恢复默认。"
                    + (inputError == null ? "" : "\n" + inputError);
            describe(description, mouseX, mouseY, left, top, rowWidth, rowHeight);
            drawWidgets(graphics, mouseX, mouseY, partialTick);
        }
    }

    private final class OptionSlider extends AbstractSliderButton {
        /** 所属行持有统一绑定和数值输入框，滑条不持有另一份业务状态。 */
        private final OptionEntry row;
        /** 本次鼠标按下前的实际值，用于松开后只记录一次拖动结果。 */
        private double dragStartValue;
        private OptionSlider(OptionEntry row) {
            super(0, 0, 100, 12, Component.literal(row.option.title()), row.option.toSlider(row.option.value()));
            this.row = row;
            dragStartValue = row.option.value();
        }
        private void readValue() {
            // 正在拖动时保留原版控件的精确归一化位置，不能逐帧用业务float回读覆盖鼠标计算。
            if (activeSlider != this) { value = row.option.toSlider(row.option.value()); }
        }
        @Override protected void updateMessage() { }
        @Override public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            // 调用统一暗槽滑条皮肤，原版仍负责点击、拖动与键盘处理。
            RVP_TuningTheme.slider(graphics, getX(), getY(), getWidth(), getHeight(), value, isHoveredOrFocused());
        }
        @Override public void onClick(double mouseX, double mouseY) {
            dragStartValue = row.option.value();
            activeSlider = this;
            super.onClick(mouseX, mouseY);
        }
        @Override public void onRelease(double mouseX, double mouseY) {
            super.onRelease(mouseX, mouseY);
            if (dragStartValue != row.option.value()) {
                RVP_TuningClient.log().info("PARAM source=slider key=" + row.parameterKey
                        + " previous=" + dragStartValue + " value=" + row.option.value()
                        + " position=" + value + " min=" + row.option.min() + " max=" + row.option.max());
            }
        }
        @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            double previous = row.option.value();
            boolean handled = super.keyPressed(keyCode, scanCode, modifiers);
            if (previous != row.option.value()) {
                RVP_TuningClient.log().info("PARAM source=keyboard key=" + row.parameterKey
                        + " previous=" + previous + " value=" + row.option.value());
            }
            return handled;
        }
        @Override protected void applyValue() {
            try {
                // 调用参数声明的范围换算和业务写入；内置参数使用线性映射，中点对应上下限平均值。
                row.option.set(row.option.fromSlider(value));
                row.syncInput();
            } catch (IllegalArgumentException failure) {
                RVP_TuningClient.log().error("SLIDER_WRITE_FAILED key=" + row.parameterKey + " position=" + value, failure);
                feedback(failure.getMessage(), true);
            }
        }
    }
}
