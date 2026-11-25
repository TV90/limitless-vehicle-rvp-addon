package org.ywzj.rvp.client.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.screen.ApricityScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.ywzj.rvp.client.screen.RVP_EquipPanelData.Category;
import org.ywzj.rvp.client.screen.RVP_EquipPanelData.Row;
import org.ywzj.rvp.maintenance.RVP_RepairPlanner;
import org.ywzj.rvp.client.state.RVP_ClientRepairOrderState;
import org.ywzj.rvp.client.state.RVP_ClientRepairOrderState.Order;
import org.ywzj.rvp.maintenance.network.C2SSetRepairOrder;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 透明的三栏辅助设备面板。部件、俯视图和维修队列共用同一辆车的状态。 */
@OnlyIn(Dist.CLIENT)
public class RVP_EquipPanelScreen extends ApricityScreen {
    /** 当前查看的载具。 */
    private final AbstractVehicle vehicle;
    /** AUI 在窗口缩放时重新挂载的页面与原生绘制锚点。 */
    private Document document;
    private Element skeletonCanvas;
    /** 上次显示的部件、维修顺序，均为值快照。 */
    private List<Category> categories = List.of();
    private Order displayedOrder;
    /** 与配额文字同刻刷新的预览目标，避免悬停时每帧重新遍历全车模块。 */
    private Set<String> previewBones = Set.of();
    /** 翻页、缩放时保留用户正在查看的分类和队列。 */
    private String categoryTitle = "";
    private boolean showEra;
    /** 已挂载队列行，用于按当前 AUI 矩形识别拖拽和插入位置。 */
    private final Map<String, Element> queueRows = new LinkedHashMap<>();
    /** 拖动中的骨名和所属队列，窗口重建时清空。 */
    private String draggedBone;
    private boolean draggedEra, dragMoved;
    /** 按下位置和待插入索引；位置使用 Minecraft GUI 坐标。 */
    private double dragStartX, dragStartY;
    private int dropIndex;
    /** 将取数限制为每五个游戏刻一次。 */
    private int refreshTicks;
    /** 俯视图画布矩形采用值（GUI 像素坐标）：scissor 与绘制区域来源。 */
    private int skeletonRectX0, skeletonRectY0, skeletonRectX1, skeletonRectY1;
    /** 是否已有稳定有效的画布矩形采用值（首帧/布局未完成前为 false，跳过俯视图绘制）。 */
    private boolean skeletonRectValid;
    /** 帧首原始读数（浏览器 px）：连续两帧一致才更新采用值，过滤布局缓存失效期的瞬时读数。 */
    private double skeletonRectFreshX, skeletonRectFreshY, skeletonRectFreshW, skeletonRectFreshH;
    /** 本次帧首读数是否有效（>8px）；与 {@link #skeletonRectValid}（采用值）分离。 */
    private boolean skeletonRectFreshValid;

    public RVP_EquipPanelScreen(AbstractVehicle vehicle) {
        super("screens/rvp_equipment.html");
        this.vehicle = vehicle;
        setPauseGame(false);
        setShowDefaultBackground(false);
    }

    @Override
    protected void init() {
        super.init();
        document = getLinkedDocument();
        if (document == null) return;
        // AUI 会按虚拟视口缩放；小窗口按实际屏幕像素补足中文字号。
        double pixelScale = document.getViewport().renderScale() * minecraft.getWindow().getGuiScale();
        Element frame = el("equipment-frame");
        frame.setClassName(pixelScale < .90 ? "frame compact" : "frame");
        double factor = Math.max(1, .94 / pixelScale);
        frame.setInlineStyleProperty("--eq-base", Math.round(17 * factor) + "px");
        frame.setInlineStyleProperty("--eq-detail", Math.round(15 * factor) + "px");
        frame.setInlineStyleProperty("--eq-heading", Math.round(19 * factor) + "px");
        frame.setInlineStyleProperty("--eq-title", Math.round(22 * factor) + "px");
        draggedBone = null;
        skeletonCanvas = el("skeleton-canvas");
        skeletonRectValid = skeletonRectFreshValid = false;
        el("vehicle-name").setTextContent(vehicle.getDisplayName().getString());
        el("close-button").setAttribute("aria-label", I18n.get("gui.done"));
        el("close-button").addEventListener("click", event -> onClose());
        el("era-tab").addEventListener("click", event -> selectQueue(true));
        el("dev-tab").addEventListener("click", event -> selectQueue(false));
        // 每次挂载都构建新 DOM，状态相同也不能复用上一个 Document 的元素。
        refresh(true);
        if (RVP_EquipPanelData.hasMaintenance(vehicle)) {
            // 请求服务器保存的车组共享顺序，首次打开也能完整展示。
            RVP_Network.CHANNEL.sendToServer(new C2SSetRepairOrder(vehicle.getId()));
        }
    }

    private Element el(String id) { return document == null ? null : document.getElementById(id); }

    @Override
    public void tick() {
        super.tick();
        if (document != null && draggedBone == null && ++refreshTicks >= 5) {
            refreshTicks = 0;
            refresh(false);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (document != null) querySkeletonCanvasRect();
        super.render(graphics, mouseX, mouseY, partialTick);
        renderSkeletonNative(graphics, partialTick, over(el("repair-preview"), mouseX, mouseY));
    }

    private static void text(Element element, String value) {
        if (!value.equals(element.getTextContent())) element.setTextContent(value);
    }

    private Element append(Element parent, String tag, String style, String value) {
        Element node = document.createElement(tag);
        node.setClassName(style);
        if (!value.isEmpty()) node.setTextContent(value);
        // AUI 挂载 div 等元素时会返回新的具体节点，后续子项和监听器须绑定到它。
        return parent.appendChild(node);
    }

    private String label(String key, Object... args) {
        return I18n.get("gui.ywzj_rvp.equipment." + key, args);
    }

    /** 读取部件快照一次。车体单独回血不会重新构建或重置滚动列表。 */
    private void refresh(boolean mounted) {
        text(el("hp-text"), label("hull") + " " + (int) vehicle.getHealth() + "/" + (int) vehicle.getMaxHealth());
        String width = Math.round(100 * Math.max(0, Math.min(1, vehicle.getHealth() / Math.max(1, vehicle.getMaxHealth())))) + "%";
        Element bar = el("hp-fill");
        if (!width.equals(bar.getInlineStylePropertyValue("width"))) bar.setInlineStyleProperty("width", width);
        List<Category> next = RVP_EquipPanelData.buildCategories(vehicle);
        boolean changed = mounted || !next.equals(categories);
        if (changed) {
            categories = next;
            if (categories.stream().noneMatch(c -> c.title().equals(categoryTitle))) {
                categoryTitle = categories.isEmpty() ? "" : categories.get(0).title();
            }
            drawCategories();
        }
        boolean repair = RVP_EquipPanelData.hasMaintenance(vehicle);
        String cls = repair ? "col repair" : "col repair hidden";
        if (!cls.equals(el("maint-panel").getAttribute("class"))) el("maint-panel").setClassName(cls);
        if (!repair) return;
        Order order = RVP_EquipPanelData.repairOrder(vehicle);
        RVP_ClientRepairOrderState.set(vehicle.getId(), order.eraBones(), order.deviceBones());
        if (changed || !order.equals(displayedOrder)) drawQueue(order);
        var forecast = RVP_EquipPanelData.repairForecast(vehicle);
        previewBones = forecast.bones();
        text(el("repair-forecast"), label("forecast", forecast.eraQuota(), forecast.deviceQuota()));
    }

    /** 类别栏和部件列表独立滚动，选择分类后回到该分类第一项。 */
    private void drawCategories() {
        Element nav = el("categories");
        Element list = el("parts-list");
        nav.replaceChildren();
        list.replaceChildren();
        if (categories.isEmpty()) append(list, "div", "empty", label("empty_parts"));
        for (Category category : categories) {
            boolean current = category.title().equals(categoryTitle);
            Element tab = append(nav, "button", current ? "category selected" : "category", "");
            tab.setAttribute("aria-pressed", Boolean.toString(current));
            append(tab, "span", "name", category.title());
            append(tab, "span", "summary", category.summary());
            tab.addEventListener("click", event -> {
                if (category.title().equals(categoryTitle)) return;
                categoryTitle = category.title();
                drawCategories();
                list.setScrollTop(0);
            });
            if (!current) continue;
            for (Row row : category.rows()) {
                boolean actionable = row.queueKey() != null && RVP_EquipPanelData.hasMaintenance(vehicle);
                Element item = append(list, actionable ? "button" : "div", actionable ? "part action" : "part", "");
                append(item, "span", "idx", String.valueOf(row.index()));
                String alias = "__vehicle__".equals(row.alias()) ? category.title() : row.alias();
                String detail = row.extra() == null ? "" : row.extra();
                append(item, "span", "part-name", detail.isBlank() ? alias : alias + "\n" + detail);
                String state = row.damaged() ? "damaged" : row.active() ? "ok" : "bad";
                append(item, "span", "status " + state, label("state_" + state));
                if (actionable) item.addEventListener("click", event -> editQueue(
                        RVP_EquipPanelData.QUEUE_ERA.equals(row.queueKey()), row.boneName(), 0));
            }
        }
    }

    private void selectQueue(boolean era) {
        if (era == showEra) return;
        showEra = era;
        drawQueue(RVP_EquipPanelData.repairOrder(vehicle));
        el("queue-list").setScrollTop(0);
    }

    private void drawQueue(Order order) {
        displayedOrder = order;
        el("era-tab").setClassName(showEra ? "tab selected" : "tab");
        el("dev-tab").setClassName(showEra ? "tab" : "tab selected");
        el("era-tab").setAttribute("aria-pressed", Boolean.toString(showEra));
        el("dev-tab").setAttribute("aria-pressed", Boolean.toString(!showEra));
        Element list = el("queue-list");
        list.replaceChildren();
        queueRows.clear();
        List<String> bones = showEra ? order.eraBones() : order.deviceBones();
        if (bones.isEmpty()) append(list, "div", "empty", label("queue_empty"));
        for (int i = 0; i < bones.size(); i++) {
            String bone = bones.get(i);
            Element item = append(list, "div", "queue-row", "");
            item.setAttribute("data-bone", bone);
            queueRows.put(bone, item);
            append(item, "span", "idx", Integer.toString(i + 1));
            Row part = categories.stream().flatMap(c -> c.rows().stream())
                    .filter(row -> bone.equals(row.boneName())).findFirst().orElse(null);
            String name = RVP_VehicleHitboxFactorManager.INSTANCE.resolveHitboxDisplayNameLocalized(vehicle, bone);
            if ("__vehicle__".equals(name)) {
                name = categories.stream().filter(c -> c.rows().stream().anyMatch(r -> bone.equals(r.boneName())))
                        .map(Category::title).findFirst().orElse(name);
            }
            append(item, "span", "name", part == null ? name : "#" + part.index() + " " + name);
            int stage = RVP_EquipPanelData.boneStage(vehicle, bone);
            String state = stage == 1 ? "damaged" : stage >= 2 ? "bad" : "ok";
            append(item, "span", "status " + state, label("state_" + state));
            append(item, "span", "drag-handle", "≡");
        }
    }

    private void editQueue(boolean eraQueue, String bone, int index) {
        Order order = RVP_EquipPanelData.repairOrder(vehicle);
        List<String> era = order.eraBones(), dev = order.deviceBones();
        if (eraQueue) era = RVP_RepairPlanner.move(era, bone, index);
        else dev = RVP_RepairPlanner.move(dev, bone, index);
        showEra = eraQueue;
        drawQueue(saveOrder(era, dev));
        previewBones = RVP_EquipPanelData.repairForecast(vehicle).bones();
    }

    /** 本地立即回显，同一条原有网络消息保存两组队列。 */
    private Order saveOrder(List<String> era, List<String> dev) {
        RVP_ClientRepairOrderState.set(vehicle.getId(), era, dev);
        Order order = RVP_ClientRepairOrderState.get(vehicle.getId());
        RVP_Network.CHANNEL.sendToServer(new C2SSetRepairOrder(vehicle.getId(), order.eraBones(), order.deviceBones()));
        return order;
    }

    /** DOM 矩形换算和点击/悬停共用坐标，拖拽只在可见队列区域内开始。 */
    private boolean over(Element node, double x, double y) {
        if (node == null || document == null) return false;
        var box = node.getBoundingClientRect();
        double scale = document.getViewport().renderScale();
        return box.width > 0 && box.height > 0 && x >= box.x * scale && x < (box.x + box.width) * scale
                && y >= box.y * scale && y < (box.y + box.height) * scale;
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (button == 0 && over(el("queue-list"), x, y)) {
            for (var row : queueRows.entrySet()) {
                if (!over(row.getValue(), x, y)) continue;
                draggedBone = row.getKey();
                draggedEra = showEra;
                dragStartX = x;
                dragStartY = y;
                dragMoved = false;
                return true;
            }
        }
        return super.mouseClicked(x, y, button);
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (button != 0 || draggedBone == null) return super.mouseDragged(x, y, button, dx, dy);
        dragMoved |= Math.hypot(x - dragStartX, y - dragStartY) >= 3;
        if (!dragMoved) return true;
        dropIndex = 0;
        double scale = document.getViewport().renderScale();
        Element scroll = el("queue-list");
        var viewport = scroll.getBoundingClientRect();
        double localY = y / scale;
        if (localY < viewport.y + 22) scroll.setScrollTop(Math.max(0, scroll.getScrollTop() - 12));
        else if (localY > viewport.y + viewport.height - 22) scroll.setScrollTop(scroll.getScrollTop() + 12);
        for (var row : queueRows.entrySet()) {
            row.getValue().setClassName(row.getKey().equals(draggedBone) ? "queue-row dragging" : "queue-row");
            if (row.getKey().equals(draggedBone)) continue;
            var box = row.getValue().getBoundingClientRect();
            if (y >= (box.y + box.height / 2) * scale) dropIndex++;
        }
        List<String> others = new ArrayList<>(queueRows.keySet());
        others.remove(draggedBone);
        if (dropIndex < others.size()) queueRows.get(others.get(dropIndex)).setClassName("queue-row drop-before");
        else if (!others.isEmpty()) queueRows.get(others.get(others.size() - 1)).setClassName("queue-row drop-after");
        return true;
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        if (button != 0 || draggedBone == null) return super.mouseReleased(x, y, button);
        String bone = draggedBone;
        draggedBone = null;
        if (dragMoved) editQueue(draggedEra, bone, dropIndex);
        else drawQueue(RVP_EquipPanelData.repairOrder(vehicle));
        return true;
    }

    /** 在现有 AUI 画布内调用本体俯视图；保留 scissor、稳定画布和原生绘制时序。 */
    private void renderSkeletonNative(GuiGraphics guiGraphics, float partialTick, boolean preview) {
        if (!skeletonRectValid) {
            // 尚无稳定有效矩形（首帧/布局未完成）：跳过，下一帧帧首预读重试
            return;
        }
        guiGraphics.enableScissor(skeletonRectX0, skeletonRectY0, skeletonRectX1, skeletonRectY1);
        PoseStack poseStack = guiGraphics.pose();
        poseStack.pushPose();
        try {
            poseStack.translate(0, 0, 400);
            RVP_EquipSkeletonRenderer.render(guiGraphics, vehicle,
                    skeletonRectX0, skeletonRectY0, skeletonRectX1, skeletonRectY1, partialTick,
                    preview ? previewBones : Set.of());
            guiGraphics.flush();
        } finally {
            poseStack.popPose();
            guiGraphics.disableScissor();
        }
    }

    /**
     * 帧首预读俯视图画布矩形（2026-09-29 回血闪烁根治）：原始读数与上一帧读数比对，
     * 连续两帧一致（±1px 内）才更新采用值——回血期 refreshDynamic 失效布局缓存后的
     * 瞬时塌陷/位移读数是单帧态，被两帧一致过滤；读数无效（≤8px）时采用值保持最近
     * 有效矩形（跳帧空白根源即此处曾被 0 尺寸读数打断）。
     */
    private void querySkeletonCanvasRect() {
        if (skeletonCanvas == null || document == null) {
            return;
        }
        Element.DOMRect rect = skeletonCanvas.getBoundingClientRect();
        boolean valid = rect.width > 8 && rect.height > 8;
        boolean stable = valid && skeletonRectFreshValid
                && Math.abs(rect.x - skeletonRectFreshX) <= 1.0
                && Math.abs(rect.y - skeletonRectFreshY) <= 1.0
                && Math.abs(rect.width - skeletonRectFreshW) <= 1.0
                && Math.abs(rect.height - skeletonRectFreshH) <= 1.0;
        skeletonRectFreshX = rect.x;
        skeletonRectFreshY = rect.y;
        skeletonRectFreshW = rect.width;
        skeletonRectFreshH = rect.height;
        skeletonRectFreshValid = valid;
        if (valid && (stable || !skeletonRectValid)) {
            // 两帧读数一致（或首次拿到有效矩形）才更新采用值；换算用项目唯一合法公式
            double renderScale = document.getViewport().renderScale();
            skeletonRectX0 = (int) Math.floor(rect.x * renderScale);
            skeletonRectY0 = (int) Math.floor(rect.y * renderScale);
            skeletonRectX1 = (int) Math.ceil((rect.x + rect.width) * renderScale);
            skeletonRectY1 = (int) Math.ceil((rect.y + rect.height) * renderScale);
            skeletonRectValid = true;
        }
    }
}
