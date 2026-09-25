package org.ywzj.rvp.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.ywzj.rvp.client.state.RVP_ClientBoneModuleState;
import org.ywzj.rvp.client.state.RVP_ClientRepairOrderState;
import org.ywzj.rvp.maintenance.network.C2SSetRepairOrder;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.vehicle.BoneModuleType;
import org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.screen.ApricityScreen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 辅助设备面板（AUI ApricityScreen，O 键打开，布局=2026-09-25 v4 定稿）：
 * <ul>
 *   <li>左上俯视图：HTML 放占位锚点，{@link RVP_EquipSkeletonRenderer} 在 scissor 内
 *       原生绘制车体+骨模块块（参考本体/RVP 观瞄俯视图的投影设计，坐标换算用项目唯一
 *       合法公式 {@code getBoundingClientRect × renderScale}）；</li>
 *   <li>左下快速维修顺序设置：爆反/辅助设备双队列（自绘下拉切换，规避 AUI 对原生
 *       {@code <select>} 支持未知），点击右侧失效行入队、✕ 移除；编辑即经
 *       {@link C2SSetRepairOrder} 上行，触发维修仍由玩家用快修工具完成（无开始按钮）；
 *       载具未配置快修时整栏隐藏（可用性由快修模块决定）；</li>
 *   <li>右半页：按设备类型分栏（同屏四栏目 2×2 等高，卡内滚动+外层滚动），
 *       行 = 序号（载具数据顺序）+ 别名（无别名回退骨名）+ 状态，失效行置顶、红框，
 *       生效绿框；栏目按"无设备不画"门控。</li>
 * </ul>
 * 数据全部来自客户端现成缓存（骨模块配置 + S2C 快照），零新增 S2C 包；
 * 动态状态每 10 render 帧节流重建（约 0.5s 内跟上 ERA 被打掉等变化）。
 */
@OnlyIn(Dist.CLIENT)
public class RVP_EquipPanelScreen extends ApricityScreen {

    /** AUI 模板逻辑路径（相对 assets/apricityui/apricity/）。 */
    private static final String TEMPLATE = "screens/rvp_equipment.html";

    private final AbstractVehicle vehicle;

    private Document document;
    private Element panelTitle;
    private Element hpText;
    private Element hpFill;
    /** 俯视图画布：注入绝对定位 div 的容器（HTML 渲染路径，弃用 scissor 原生绘制）。 */
    private Element skeletonCanvas;
    private Element skeletonHeaderText;
    private Element categoryScroll;
    private Element maintPanel;
    private Element queuePicker;
    private Element queuePickerValue;
    private Element queueMenu;
    private Element queueEraList;
    private Element queueDevList;
    /** 预计维修量提示行（"预计维修 x 块爆反 · x 个辅助设备"）。 */
    private Element repairForecast;
    /** 当前查看的维修队列（era=爆反 / dev=辅助设备）。 */
    private String selectedQueue = RVP_EquipPanelData.QUEUE_ERA;
    /** 下拉菜单展开态。 */
    private boolean queueMenuOpen;
    /** render 帧计数：节流刷新动态区。 */
    private int refreshCounter;
    /** 动态区签名：内容未变化时跳过 DOM 重建（根治高频抖动——重建会重置滚动/悬停态）。 */
    private String lastDynamicSignature = "";
    /** DOM 变更后待强制重绘标记：AUI 对 init 之后的 DOM 变更是惰性绘制的，
     *  requestStyleRecalc 只重算样式不触发重绘；置位后在下一帧走一次程序化 resize
     *  （窗口缩放同款完整重布局+重绘路径）。 */
    private boolean pendingRepaint;

    public RVP_EquipPanelScreen(AbstractVehicle vehicle) {
        super(TEMPLATE);
        this.vehicle = vehicle;
        // 与 RVP_AuiVariantScreen 同款：不暂停游戏、无默认遮罩
        setPauseGame(false);
        setShowDefaultBackground(false);
    }

    @Override
    protected void init() {
        super.init();
        // resize / 首次打开都会重建 Document：每次全量重取引用（勿跨 init 缓存 Element）
        document = getLinkedDocument();
        if (document == null) {
            return;
        }
        panelTitle = document.getElementById("panel-title");
        hpText = document.getElementById("hp-text");
        hpFill = document.getElementById("hp-fill");
        skeletonCanvas = document.getElementById("skeleton-canvas");
        skeletonHeaderText = document.getElementById("skeleton-header-text");
        categoryScroll = document.getElementById("category-scroll");
        maintPanel = document.getElementById("maint-panel");
        queuePicker = document.getElementById("queue-picker");
        queuePickerValue = document.getElementById("queue-picker-value");
        queueMenu = document.getElementById("queue-menu");
        queueEraList = document.getElementById("queue-era-list");
        queueDevList = document.getElementById("queue-dev-list");
        repairForecast = document.getElementById("repair-forecast");

        Element closeButton = document.getElementById("close-button");
        if (closeButton != null) {
            closeButton.addEventListener("click", event -> onClose());
        }
        if (queueMenu != null) {
            // 自绘下拉：菜单项点击 = 切换队列（点击事件在 AUI 已验证可用，故不用原生 select）
            for (Element option : queueMenu.querySelectorAll(".queue-option")) {
                String key = option.getAttribute("data-queue");
                option.addEventListener("click", event -> {
                    event.stopPropagation();
                    selectQueue(key);
                });
            }
        }
        if (queuePicker != null) {
            queuePicker.addEventListener("click", event -> toggleQueueMenu());
        }
        rebuildAll();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // DOM 变更后的强制重绘：走 resize 完整重布局（同窗口缩放路径），AUI 必然整体重绘
        if (pendingRepaint) {
            pendingRepaint = false;
            resize(minecraft, width, height);
        }
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        // 节流刷新动态状态（俯视图线框/栏目行失效态/血量/队列），签名未变化时零 DOM 操作
        if (++refreshCounter >= 10) {
            refreshCounter = 0;
            refreshDynamic();
        }
    }

    @Override
    public void onClose() {
        super.onClose();
    }

    // ─────────────────────────────────────────────────────────────
    // DOM 构建
    // ─────────────────────────────────────────────────────────────

    /** 全量重建：标题/血量/维修栏可用性/栏目/队列。 */
    /** 全量重建：标题/血量/俯视图/维修栏可用性/栏目/队列，并记录动态区签名（init 与开屏时调用）。 */
    private void rebuildAll() {
        if (document == null) {
            return;
        }
        if (panelTitle != null) {
            panelTitle.setTextContent(I18n.get("gui.ywzj_rvp.equipment.title")
                    + " · " + vehicle.getDisplayName().getString());
        }
        if (skeletonHeaderText != null) {
            // 单文本节点：AUI 下"文本 + 子元素"混排会重叠，汇总信息并入同一节点
            skeletonHeaderText.setTextContent(I18n.get("gui.ywzj_rvp.equipment.skeleton"));
        }
        updateHp();
        // 维修顺序栏可用性：未配置快修的载具整栏隐藏（由快修模块决定）
        if (maintPanel != null) {
            boolean visible = RVP_EquipPanelData.hasMaintenance(vehicle);
            maintPanel.setClassName("card card-accent-gold maint-panel" + (visible ? "" : " hidden"));
        }
        // init 每次都会重建 Document：俯视图本轮先尝试注入（画布未布局好会在下轮刷新自动重试）
        rebuildSkeleton();
        rebuildCategories();
        rebuildQueues();
        applyQueueVisibility();
        lastDynamicSignature = buildDynamicSignature();
    }

    /** 节流刷新：俯视图每轮都重建（实时 OBB 位置，炮塔/车体姿态变化 0.5s 内跟上）；
     *  其余区域按内容签名门控（未变化时重建会重置滚动/悬停态 → 高频抖动）。 */
    private void refreshDynamic() {
        rebuildSkeleton();
        String signature = buildDynamicSignature();
        if (signature.equals(lastDynamicSignature)) {
            return;
        }
        lastDynamicSignature = signature;
        updateHp();
        rebuildCategories();
        rebuildQueues();
        pendingRepaint = true;
    }

    /** 动态区内容签名：整车血量 + 各栏目行生效态 + 两条维修队列。 */
    private String buildDynamicSignature() {
        StringBuilder sb = new StringBuilder(256);
        sb.append('H').append((int) vehicle.getHealth()).append('/');
        for (RVP_EquipPanelData.Category category : RVP_EquipPanelData.buildCategories(vehicle)) {
            sb.append(category.summary()).append(';');
            for (RVP_EquipPanelData.Row row : category.rows()) {
                sb.append(row.index()).append(row.active() ? 'a' : 'b');
            }
            sb.append('|');
        }
        RVP_ClientRepairOrderState.Order order = RVP_ClientRepairOrderState.get(vehicle.getId());
        sb.append("Q").append(String.join(",", order.eraBones()))
                .append("#").append(String.join(",", order.deviceBones()));
        return sb.toString();
    }

    /** 顶栏整车血量。 */
    private void updateHp() {
        float health = vehicle.getHealth();
        float maxHealth = vehicle.getMaxHealth();
        if (hpText != null) {
            hpText.setTextContent(I18n.get("gui.ywzj_rvp.equipment.hull")
                    + " " + (int) health + "/" + (int) maxHealth);
        }
        if (hpFill != null && maxHealth > 0) {
            int pct = (int) Math.max(0, Math.min(100, health / maxHealth * 100f));
            hpFill.setInlineStyleProperty("width", pct + "%");
        }
    }

    /** 右半页栏目：同屏四卡 2×2，由数据模型逐行构建。 */
    private void rebuildCategories() {
        if (categoryScroll == null || document == null) {
            return;
        }
        categoryScroll.replaceChildren();
        List<RVP_EquipPanelData.Category> categories = RVP_EquipPanelData.buildCategories(vehicle);
        boolean first = true;
        for (RVP_EquipPanelData.Category category : categories) {
            Element card = document.createElement("section");
            card.setClassName("card cat-card" + (first ? " card-accent-green" : ""));
            first = false;
            // 标题与汇总并入同一文本节点：AUI 下"文本 + 子元素"混排渲染会重叠
            Element header = document.createElement("div");
            header.setClassName("card-header");
            header.setTextContent(category.title() + "　" + category.summary());
            Element body = document.createElement("div");
            body.setClassName("card-body cat-body");
            for (RVP_EquipPanelData.Row row : category.rows()) {
                body.appendChild(buildRow(row));
            }
            card.appendChild(header);
            card.appendChild(body);
            categoryScroll.appendChild(card);
        }
    }

    /** 单行：序号 / 别名（+行尾附加文本）/ 状态徽标；失效且可入队的行绑定点击入队。 */
    private Element buildRow(RVP_EquipPanelData.Row row) {
        Element div = document.createElement("div");
        div.setClassName("mod-row " + (row.active() ? "ok" : "bad"));
        Element idx = document.createElement("span");
        idx.setClassName("row-idx");
        idx.setTextContent(String.valueOf(row.index()));
        div.appendChild(idx);
        Element alias = document.createElement("span");
        alias.setClassName("row-alias");
        alias.setTextContent(row.alias());
        div.appendChild(alias);
        if (row.extra() != null) {
            Element extra = document.createElement("span");
            extra.setClassName("row-extra");
            extra.setTextContent(row.extra());
            div.appendChild(extra);
        }
        Element state = document.createElement("span");
        state.setClassName("row-state " + (row.active() ? "ok" : "bad"));
        state.setTextContent(I18n.get(row.active()
                ? "gui.ywzj_rvp.equipment.state_ok"
                : "gui.ywzj_rvp.equipment.state_bad"));
        div.appendChild(state);
        if (!row.active() && row.queueKey() != null) {
            div.addEventListener("click", event -> addToQueue(row.queueKey(), row.boneName(), row.alias()));
        }
        return div;
    }

    // ─────────────────────────────────────────────────────────────
    // 维修顺序队列（爆反 / 辅助设备）
    // ─────────────────────────────────────────────────────────────

    /** 队列区重建：两条队列各自渲染为"#顺序号 自身序号 别名 ✕"行，空队列显示占位提示。 */
    private void rebuildQueues() {
        if (document == null) {
            return;
        }
        // 骨名 → 栏目自身序号：同名部件（如 6 块"车体侧爆反"）靠自身序号区分对应关系
        Map<String, Integer> ownIndexByBone = new HashMap<>();
        for (RVP_EquipPanelData.Category category : RVP_EquipPanelData.buildCategories(vehicle)) {
            for (RVP_EquipPanelData.Row row : category.rows()) {
                if (row.boneName() != null) {
                    ownIndexByBone.putIfAbsent(row.boneName(), row.index());
                }
            }
        }
        RVP_ClientRepairOrderState.Order order = RVP_ClientRepairOrderState.get(vehicle.getId());
        List<String> era = new ArrayList<>(order.eraBones());
        List<String> dev = new ArrayList<>(order.deviceBones());
        // [RVP] 已修好的部件/爆反自动踢出待修列表（客户端踢除后即上行同步服务端副本）：
        // 捆绑口径按骨级判定——骨上仍有任意失效可修模块就算待修，全修好才移除
        boolean pruned = era.removeIf(bone -> !queueEntryStillFailed(bone));
        pruned |= dev.removeIf(bone -> !queueEntryStillFailed(bone));
        if (pruned) {
            RVP_ClientRepairOrderState.set(vehicle.getId(), era, dev);
            syncOrderToServer();
            order = RVP_ClientRepairOrderState.get(vehicle.getId());
        }
        fillQueueList(queueEraList, order.eraBones(), ownIndexByBone);
        fillQueueList(queueDevList, order.deviceBones(), ownIndexByBone);
        updateForecast();
    }

    /**
     * 队列项是否仍处于待修状态（捆绑口径按骨级判定）：骨上任意可修模块
     * （不含 MAINTENANCE/TRACK——前者永不失效、后者无可修消费点）仍失效即保留。
     */
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

    private void fillQueueList(Element container, List<String> bones, Map<String, Integer> ownIndexByBone) {
        if (container == null || document == null) {
            return;
        }
        container.replaceChildren();
        if (bones.isEmpty()) {
            Element empty = document.createElement("div");
            empty.setClassName("queue-empty");
            empty.setTextContent(I18n.get("gui.ywzj_rvp.equipment.queue_empty"));
            container.appendChild(empty);
            return;
        }
        int index = 1;
        for (String bone : bones) {
            Element row = document.createElement("div");
            row.setClassName("queue-row");
            Element idx = document.createElement("span");
            idx.setClassName("queue-idx");
            // #N = 修复顺序序号；后面跟部件在自身栏目的序号（同名部件靠它区分）
            idx.setTextContent("#" + index++);
            row.appendChild(idx);
            Element own = document.createElement("span");
            own.setClassName("queue-own");
            Integer ownIndex = ownIndexByBone.get(bone);
            own.setTextContent(ownIndex == null ? "?" : String.valueOf(ownIndex));
            row.appendChild(own);
            Element alias = document.createElement("span");
            // 队列行同样只显示别名（无别名回退骨名）；去重按骨名 id 在状态表内完成
            alias.setTextContent(RVP_VehicleHitboxFactorManager.INSTANCE.resolveHitboxDisplayName(vehicle, bone));
            row.appendChild(alias);
            Element remove = document.createElement("span");
            remove.setClassName("queue-x");
            remove.setTextContent("✕");
            String boneName = bone;
            remove.addEventListener("click", event -> removeFromQueue(queueKeyOfContainer(container), boneName));
            row.appendChild(remove);
            container.appendChild(row);
        }
    }

    /** 由容器元素反查队列键（era/dev）。 */
    private String queueKeyOfContainer(Element container) {
        return container == queueEraList ? RVP_EquipPanelData.QUEUE_ERA : RVP_EquipPanelData.QUEUE_DEV;
    }

    /** 预计维修量提示行：ERA 为配额确定值；辅助设备显示按失效数×单台概率算出的期望修复数。 */
    private void updateForecast() {
        if (repairForecast == null) {
            return;
        }
        RVP_EquipPanelData.Forecast forecast = RVP_EquipPanelData.repairForecast(vehicle);
        // 期望修复数：整数就显示整数，否则保留一位小数（如 4 台×25% = 1，6 台×25% = 1.5）
        double expected = forecast.deviceExpected();
        String expectedText = expected == Math.floor(expected)
                ? String.valueOf((long) expected)
                : String.format("%.1f", expected);
        repairForecast.setTextContent(I18n.get("gui.ywzj_rvp.equipment.forecast",
                forecast.eraQuota(), expectedText, forecast.deviceDestroyed(), forecast.deviceChancePercent()));
    }

    /** 点击失效行加入对应队列：本地先行更新（即时反馈）再上行服务端。 */
    private void addToQueue(String queueKey, String bone, String alias) {
        // 未失效/已被修好的目标不准入队（行渲染有 ~0.5s 节流，点击瞬间二次校验骨上确实仍有失效模块）
        if (!queueEntryStillFailed(bone)) {
            return;
        }
        RVP_ClientRepairOrderState.Order order = RVP_ClientRepairOrderState.get(vehicle.getId());
        List<String> era = new ArrayList<>(order.eraBones());
        List<String> dev = new ArrayList<>(order.deviceBones());
        List<String> target = queueKey.equals(RVP_EquipPanelData.QUEUE_ERA) ? era : dev;
        if (target.contains(bone) || target.size() >= 64) {
            return;
        }
        target.add(bone);
        RVP_ClientRepairOrderState.set(vehicle.getId(), era, dev);
        syncOrderToServer();
        refreshDynamic();
    }

    /** ✕ 移除队列项。 */
    private void removeFromQueue(String queueKey, String bone) {
        RVP_ClientRepairOrderState.Order order = RVP_ClientRepairOrderState.get(vehicle.getId());
        List<String> era = new ArrayList<>(order.eraBones());
        List<String> dev = new ArrayList<>(order.deviceBones());
        (queueKey.equals(RVP_EquipPanelData.QUEUE_ERA) ? era : dev).remove(bone);
        RVP_ClientRepairOrderState.set(vehicle.getId(), era, dev);
        syncOrderToServer();
        refreshDynamic();
    }

    /** 编辑即上行：服务端按载具记入 RVP_RepairOrderTable（车组共享，快修触发时消费）。 */
    private void syncOrderToServer() {
        RVP_ClientRepairOrderState.Order order = RVP_ClientRepairOrderState.get(vehicle.getId());
        RVP_Network.CHANNEL.sendToServer(
                new C2SSetRepairOrder(vehicle.getId(), order.eraBones(), order.deviceBones()));
    }

    // ─────────────────────────────────────────────────────────────
    // 自绘下拉与俯视图
    // ─────────────────────────────────────────────────────────────

    private void toggleQueueMenu() {
        queueMenuOpen = !queueMenuOpen;
        applyQueueMenu();
    }

    private void selectQueue(String key) {
        selectedQueue = key;
        queueMenuOpen = false;
        if (queuePickerValue != null) {
            queuePickerValue.setTextContent(I18n.get(key.equals(RVP_EquipPanelData.QUEUE_ERA)
                    ? "gui.ywzj_rvp.equipment.queue_era"
                    : "gui.ywzj_rvp.equipment.queue_dev"));
        }
        if (queueMenu != null) {
            for (Element option : queueMenu.querySelectorAll(".queue-option")) {
                String optionKey = option.getAttribute("data-queue");
                option.setClassName("queue-option" + (optionKey.equals(key) ? " active" : ""));
            }
        }
        applyQueueVisibility();
        applyQueueMenu();
    }

    /** 下拉菜单展开/收起。 */
    private void applyQueueMenu() {
        if (queueMenu != null) {
            queueMenu.setClassName("queue-menu" + (queueMenuOpen ? "" : " hidden"));
        }
    }

    /** 两条队列列表按当前选中项显示其一。 */
    private void applyQueueVisibility() {
        if (queueEraList != null) {
            queueEraList.setClassName("queue-items"
                    + (selectedQueue.equals(RVP_EquipPanelData.QUEUE_ERA) ? "" : " hidden"));
        }
        if (queueDevList != null) {
            queueDevList.setClassName("queue-items"
                    + (selectedQueue.equals(RVP_EquipPanelData.QUEUE_DEV) ? "" : " hidden"));
        }
    }

    /**
     * 俯视图（HTML div 注入）：Java 投影骨块/车体 OBB → 视空间矩形，按画布 CSS 尺寸
     * 等比缩放居中后注入绝对定位 div。双层结构（外层描边色 + 内层填充/底色）规避 AUI
     * 对 border 简写的支持不确定性；布局为车体固定视，仅随模块失效/修复经签名节流重建。
     */
    private void rebuildSkeleton() {
        if (skeletonCanvas == null || document == null) {
            return;
        }
        Element.DOMRect rect = skeletonCanvas.getBoundingClientRect();
        if (rect.width <= 8 || rect.height <= 8) {
            // DOM 尚未布局完成：本轮跳过，refreshDynamic 下轮继续重试
            return;
        }
        List<RVP_EquipSkeletonRenderer.ViewRect> rects = RVP_EquipSkeletonRenderer.buildRects(vehicle);
        if (rects.isEmpty()) {
            return;
        }
        skeletonCanvas.replaceChildren();
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        // 缩放包络只算 fit=true 的矩形（炮管等长条部件不参与，防车体被压扁）
        for (RVP_EquipSkeletonRenderer.ViewRect r : rects) {
            if (!r.fit()) {
                continue;
            }
            minX = Math.min(minX, r.x0());
            maxX = Math.max(maxX, r.x1());
            minY = Math.min(minY, r.y0());
            maxY = Math.max(maxY, r.y1());
        }
        float spanX = Math.max(maxX - minX, 0.01f);
        float spanY = Math.max(maxY - minY, 0.01f);
        double scale = Math.min((rect.width - 12) / spanX, (rect.height - 12) / spanY);
        double offX = (rect.width - spanX * scale) / 2 - minX * scale;
        double offY = (rect.height - spanY * scale) / 2 - minY * scale;
        for (RVP_EquipSkeletonRenderer.ViewRect r : rects) {
            double rx = offX + r.x0() * scale;
            double ry = offY + r.y0() * scale;
            double rw = Math.max((r.x1() - r.x0()) * scale, 3);
            double rh = Math.max((r.y1() - r.y0()) * scale, 3);
            appendSkeletonRect(skeletonCanvas, rx, ry, rw, rh, r.line(), r.solid());
        }
        // div 注入后做一次定向样式重算，让新线框立即上屏（无需点击触发）。
        // ★禁止调用 Document.refresh()——那是按模板重建整个文档，会作废全部已注入内容
        document.requestStyleRecalc(skeletonCanvas);
        skeletonCanvas.invalidateStyle();
    }

    /**
     * 注入单个矩形：solid=true → 纯白实心块（普通部件，拼整车轮廓）；
     * solid=false → 4 条边 div 平铺的彩色空心线框（特殊设备骨，2px 加粗便于辨识）。
     * DOM 顺序 = 绘制层级：线框矩形在白底之后注入，永远压在白底之上。
     */
    private void appendSkeletonRect(Element canvas, double x, double y, double w, double h,
                                    String lineColor, boolean solid) {
        int ix = (int) Math.round(x);
        int iy = (int) Math.round(y);
        int iw = Math.max((int) Math.round(w), 4);
        int ih = Math.max((int) Math.round(h), 4);
        if (solid) {
            appendSkeletonEdge(canvas, ix, iy, iw, ih, "#FFFFFF");
            return;
        }
        int t = 2;
        appendSkeletonEdge(canvas, ix, iy, iw, t, lineColor);                      // 上边
        appendSkeletonEdge(canvas, ix, iy + ih - t, iw, t, lineColor);             // 下边
        appendSkeletonEdge(canvas, ix, iy, t, ih, lineColor);                      // 左边
        appendSkeletonEdge(canvas, ix + iw - t, iy, t, ih, lineColor);             // 右边
    }

    /**
     * 注入单个线框矩形：4 条边 div（每边一个细长条、bg=线框色）平铺拼成空心矩形——
     * 本体观瞄小图 drawRectByCorner 的同款观感。
     * ★禁止用嵌套 div 做空心：AUI 不渲染绝对定位 div 的子元素内联样式，会退化成实心色块。
     */
    private void appendSkeletonRect(Element canvas, double x, double y, double w, double h, String lineColor) {
        int ix = (int) Math.round(x);
        int iy = (int) Math.round(y);
        int iw = Math.max((int) Math.round(w), 3);
        int ih = Math.max((int) Math.round(h), 3);
        int t = 1;
        appendSkeletonEdge(canvas, ix, iy, iw, t, lineColor);                      // 上边
        appendSkeletonEdge(canvas, ix, iy + ih - t, iw, t, lineColor);             // 下边
        appendSkeletonEdge(canvas, ix, iy, t, ih, lineColor);                      // 左边
        appendSkeletonEdge(canvas, ix + iw - t, iy, t, ih, lineColor);             // 右边
    }

    /** 注入一条边（细长条 div，bg=线框色）。 */
    private void appendSkeletonEdge(Element canvas, int x, int y, int w, int h, String color) {
        Element edge = document.createElement("div");
        edge.setClassName("skeleton-rect");
        edge.setInlineStyleProperty("left", x + "px");
        edge.setInlineStyleProperty("top", y + "px");
        edge.setInlineStyleProperty("width", w + "px");
        edge.setInlineStyleProperty("height", h + "px");
        edge.setInlineStyleProperty("background", color);
        canvas.appendChild(edge);
    }
}
