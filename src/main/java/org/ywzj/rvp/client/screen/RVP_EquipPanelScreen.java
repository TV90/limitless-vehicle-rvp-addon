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
import org.ywzj.rvp.client.state.RVP_ClientBoneModuleState;
import org.ywzj.rvp.client.state.RVP_ClientRepairOrderState;
import org.ywzj.rvp.client.state.RVP_ClientRepairOrderState.Order;
import org.ywzj.rvp.maintenance.network.C2SSetRepairOrder;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.vehicle.BoneModuleType;
import org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.ArrayList;
import java.util.List;

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
    /** 翻页、缩放时保留用户正在查看的分类和队列。 */
    private String categoryTitle = "";
    private boolean showEra = true;
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
        skeletonCanvas = el("skeleton-canvas");
        skeletonRectValid = skeletonRectFreshValid = false;
        el("vehicle-name").setTextContent(vehicle.getDisplayName().getString());
        el("close-button").setAttribute("aria-label", I18n.get("gui.done"));
        el("close-button").addEventListener("click", event -> onClose());
        el("era-tab").addEventListener("click", event -> selectQueue(true));
        el("dev-tab").addEventListener("click", event -> selectQueue(false));
        // 每次挂载都构建新 DOM，状态相同也不能复用上一个 Document 的元素。
        refresh(true);
    }

    private Element el(String id) { return document.getElementById(id); }

    @Override
    public void tick() {
        super.tick();
        if (document != null && ++refreshTicks >= 5) {
            refreshTicks = 0;
            refresh(false);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (document != null) querySkeletonCanvasRect();
        super.render(graphics, mouseX, mouseY, partialTick);
        renderSkeletonNative(graphics, partialTick);
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
        Order order = RVP_ClientRepairOrderState.get(vehicle.getId());
        List<String> era = new ArrayList<>(order.eraBones());
        List<String> dev = new ArrayList<>(order.deviceBones());
        boolean pruned = era.removeIf(b -> !queueEntryStillFailed(b));
        pruned |= dev.removeIf(b -> !queueEntryStillFailed(b));
        if (pruned) order = saveOrder(era, dev);
        if (changed || !order.equals(displayedOrder)) drawQueue(order);
        var forecast = RVP_EquipPanelData.repairForecast(vehicle);
        text(el("repair-forecast"), label("forecast", forecast.eraQuota(), (int) Math.ceil(forecast.deviceExpected()),
                forecast.deviceDestroyed(), forecast.deviceChancePercent()));
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
                boolean actionable = row.queueKey() != null && (!row.active() || row.damaged())
                        && RVP_EquipPanelData.hasMaintenance(vehicle);
                Element item = append(list, actionable ? "button" : "div", actionable ? "part action" : "part", "");
                append(item, "span", "idx", String.valueOf(row.index()));
                String alias = "__vehicle__".equals(row.alias()) ? category.title() : row.alias();
                String detail = row.extra() == null ? "" : row.extra();
                append(item, "span", "part-name", detail.isBlank() ? alias : alias + "\n" + detail);
                String state = row.damaged() ? "damaged" : row.active() ? "ok" : "bad";
                append(item, "span", "status " + state, label("state_" + state));
                if (actionable) item.addEventListener("click", event -> editQueue(
                        RVP_EquipPanelData.QUEUE_ERA.equals(row.queueKey()), row.boneName(), true));
            }
        }
    }

    private void selectQueue(boolean era) {
        if (era == showEra) return;
        showEra = era;
        drawQueue(RVP_ClientRepairOrderState.get(vehicle.getId()));
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
        List<String> bones = showEra ? order.eraBones() : order.deviceBones();
        if (bones.isEmpty()) append(list, "div", "empty", label("queue_empty"));
        for (int i = 0; i < bones.size(); i++) {
            String bone = bones.get(i);
            Element item = append(list, "div", "queue-row", "");
            append(item, "span", "idx", Integer.toString(i + 1));
            Row part = categories.stream().flatMap(c -> c.rows().stream())
                    .filter(row -> bone.equals(row.boneName())).findFirst().orElse(null);
            String name = RVP_VehicleHitboxFactorManager.INSTANCE.resolveHitboxDisplayNameLocalized(vehicle, bone);
            append(item, "span", "name", part == null ? name : "#" + part.index() + " " + name);
            Element remove = append(item, "button", "queue-x", "×");
            remove.setAttribute("aria-label", I18n.get("selectWorld.delete") + " " + name);
            remove.addEventListener("click", event -> editQueue(showEra, bone, false));
        }
    }

    private void editQueue(boolean eraQueue, String bone, boolean add) {
        if (add && !queueEntryStillFailed(bone)) return;
        Order order = RVP_ClientRepairOrderState.get(vehicle.getId());
        List<String> era = new ArrayList<>(order.eraBones()), dev = new ArrayList<>(order.deviceBones());
        List<String> target = eraQueue ? era : dev;
        if (add) {
            if (target.size() >= 64 || target.contains(bone)) return;
            target.add(bone);
        } else if (!target.remove(bone)) return;
        showEra = eraQueue;
        drawQueue(saveOrder(era, dev));
    }

    /** 本地立即回显，同一条原有网络消息保存两组队列。 */
    private Order saveOrder(List<String> era, List<String> dev) {
        RVP_ClientRepairOrderState.set(vehicle.getId(), era, dev);
        Order order = RVP_ClientRepairOrderState.get(vehicle.getId());
        RVP_Network.CHANNEL.sendToServer(new C2SSetRepairOrder(vehicle.getId(), order.eraBones(), order.deviceBones()));
        return order;
    }

    private boolean queueEntryStillFailed(String bone) {
        var types = RVP_EquipPanelData.boneModules(vehicle).get(bone);
        if (types == null) {
            return false; // 配置里已无该骨（如配置热重载移除）：视为不再待修
        }
        for (BoneModuleType type : types) {
            if (type == BoneModuleType.MAINTENANCE || type == BoneModuleType.TRACK) {
                continue;
            }
            if (!RVP_ClientBoneModuleState.isModuleActive(vehicle.getId(), bone, type)) {
                return true;
            }
        }
        return false;
    }

    /** 在现有 AUI 画布内调用本体俯视图；保留 scissor、稳定画布和原生绘制时序。 */
    private void renderSkeletonNative(GuiGraphics guiGraphics, float partialTick) {
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
                    skeletonRectX0, skeletonRectY0, skeletonRectX1, skeletonRectY1, partialTick);
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
