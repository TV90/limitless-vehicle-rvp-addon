package org.ywzj.rvp.client.screen;

import com.mojang.blaze3d.vertex.PoseStack;
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
import java.util.Set;

/**
 * 辅助设备面板（AUI ApricityScreen，O 键打开，布局=2026-09-25 v4 定稿）：
 * <ul>
 *   <li>左上俯视图：HTML 放占位锚点，{@link RVP_EquipSkeletonRenderer} 在 AUI
 *       {@code super.render} 之后的原生 scissor 内每帧绘制（本体观瞄 OBB 俯视图同款画法：
 *       实时姿态+炮塔朝上；坐标换算用项目唯一合法公式
 *       {@code getBoundingClientRect × renderScale}，深度抬 z=400 盖过 AUI 文档元素深度
 *       ——此前三次"原生不可见"的根因即 GuiGraphics.fill 的 z=0 被 AUI 深度剔除）；</li>
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
    /** 俯视图画布锚点：原生 scissor 绘制区域由它的布局矩形 × renderScale 换算。 */
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
    /** 骨名→模块类型分类表：随 refreshDynamic 10 帧节流刷新，俯视图逐帧绘制共用（OBB/失效态逐帧现读）。 */
    private Map<String, Set<BoneModuleType>> cachedModules = Map.of();

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
        // Document 重建（resize/首开）：行与卡片缓存全部失效清空（旧 Element 引用不可复用）
        catBodyByKey.clear();
        rowBindingByKey.clear();
        // Document 重建（resize/首开）：行与卡片缓存全部失效清空（旧 Element 引用不可复用）
        catBodyByKey.clear();
        rowBindingByKey.clear();
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
        // [RVP] 动态 DOM 更新（replaceChildren/setTextContent）后**零刷新调用**——AUI 渲染
        // 每帧读 DOM 当前态，天然生效（2026-09-28 对齐本体改装屏 buildDisplayCatalog 同款
        // 零调用模式）。此前 resize()/Document.refresh() 都是全量重布局，为"左右闪"根因。
        // 窗口缩放仍由 Minecraft 触发 init/resize 全量路径，不受影响。
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        // 节流刷新动态状态（栏目行失效态/血量/队列），签名未变化时零 DOM 操作
        if (++refreshCounter >= 10) {
            refreshCounter = 0;
            refreshDynamic();
        }
        // 俯视图：AUI 文档上屏后的原生 scissor 绘制（每帧重绘，实时姿态）
        renderSkeletonNative(guiGraphics, partialTick);
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
        // init 每次都会重建 Document：分类表先刷新（俯视图逐帧原生绘制消费）
        cachedModules = RVP_EquipPanelData.boneModules(vehicle);
        rebuildCategories();
        rebuildQueues();
        applyQueueVisibility();
        lastDynamicSignature = buildDynamicSignature();
    }

    /** 节流刷新：分类表/血量/栏目/队列按内容签名门控（未变化时重建会重置滚动/悬停态 → 高频抖动）；
     *  俯视图已改原生每帧重绘，不再走 DOM。 */
    private void refreshDynamic() {
        cachedModules = RVP_EquipPanelData.boneModules(vehicle);
        String signature = buildDynamicSignature();
        if (signature.equals(lastDynamicSignature)) {
            return;
        }
        lastDynamicSignature = signature;
        updateHp();
        rebuildCategories();
        rebuildQueues();
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
        // [RVP] 引擎受损档位入签名（2026-09-27）：受损标注（extra 文本）变化时触发面板重绘——
        // 否则受损后行内容变了但签名未变，面板永不刷新受损标注
        var engineBones = RVP_VehicleHitboxFactorManager.INSTANCE.resolveEngineModules(vehicle);
        if (engineBones != null) {
            for (String bone : engineBones.keySet()) {
                sb.append('E').append(bone).append(':')
                        .append(org.ywzj.rvp.client.state.RVP_ClientEngineDamageState.getStage(vehicle.getId(), bone));
            }
        }
        // [RVP] 模块虚拟血量入签名（2026-09-28 全模块累计化）：累计值变化触发面板重绘
        //（取配置骨并集，避免依赖栏目结构）
        var modules = RVP_VehicleHitboxFactorManager.INSTANCE.resolveBoneModules(vehicle);
        if (modules != null) {
            for (String bone : modules.keySet()) {
                sb.append('D').append(bone).append(':')
                        .append((int) org.ywzj.rvp.client.state.RVP_ClientBoneDamageProgress
                                .getAccumulated(vehicle.getId(), bone));
            }
        }
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
    /** 栏目卡缓存：栏目 key → body Element（原地 diff 更新复用，防全量重建抖动）。 */
    private final Map<String, Element> catBodyByKey = new HashMap<>();
    /** 行缓存：栏目 key|骨名|序号 → 行绑定（div + 可变行数据，原地更新复用）。 */
    private final Map<String, RowBinding> rowBindingByKey = new HashMap<>();

    /**
     * 栏目/行原地 diff 更新（2026-09-28 修"ERA 栏目左右闪"）：替换原 replaceChildren
     * 全量重建——新建元素首帧布局抖动，高频状态更新（虚拟血量）时栏目卡片左右晃。
     * 现按栏目 key / 行 key 复用现有 Element 原地更新文本与类名，仅新增/消失行做 DOM
     * 增删，顺序按目标序 appendChild 重排（已存在节点 append = 移动）。AUI 每帧读 DOM，零刷新调用。
     */
    private void rebuildCategories() {
        if (categoryScroll == null || document == null) {
            return;
        }
        List<RVP_EquipPanelData.Category> categories = RVP_EquipPanelData.buildCategories(vehicle);
        java.util.Set<String> seenCards = new java.util.HashSet<>();
        boolean first = true;
        for (RVP_EquipPanelData.Category category : categories) {
            String cardKey = category.title();
            Element body = catBodyByKey.get(cardKey);
            if (body == null) {
                Element card = document.createElement("section");
                card.setClassName("card cat-card" + (first ? " card-accent-green" : ""));
                Element header = document.createElement("div");
                header.setClassName("card-header");
                header.setTextContent(category.title() + "　" + category.summary());
                body = document.createElement("div");
                body.setClassName("card-body cat-body");
                card.appendChild(header);
                card.appendChild(body);
                categoryScroll.appendChild(card);
                catBodyByKey.put(cardKey, body);
            } else {
                // 标题汇总原地更新（summary 变化不重建卡）；卡片已挂载不重挂（栏目顺序稳定）
                Element header = body.children.isEmpty() ? null : body.children.get(0);
                if (header != null) {
                    header.setTextContent(category.title() + "　" + category.summary());
                }
            }
            first = false;
            // 行 diff：原地更新 / 新增 / 移除，并按目标顺序重排
            java.util.Set<String> seenRows = new java.util.HashSet<>();
            for (RVP_EquipPanelData.Row row : category.rows()) {
                String rowKey = cardKey + "|" + row.boneName() + "|" + row.index();
                RowBinding binding = rowBindingByKey.get(rowKey);
                if (binding == null) {
                    binding = buildRow(row);
                    rowBindingByKey.put(rowKey, binding);
                } else {
                    updateRowElement(binding, row);
                }
                seenRows.add(rowKey);
                body.appendChild(binding.div());
            }
            // 移除消失行
            rowBindingByKey.keySet().removeIf(key -> {
                if (!key.startsWith(rowPrefixOf(cardKey)) || seenRows.contains(key)) {
                    return false;
                }
                RowBinding binding = rowBindingByKey.remove(key);
                if (binding != null && binding.div().getParentElement() != null) {
                    binding.div().getParentElement().removeChild(binding.div());
                }
                return true;
            });
            seenCards.add(cardKey);
        }
        // 移除消失栏目卡（连带清其行缓存）
        catBodyByKey.keySet().removeIf(key -> {
            if (seenCards.contains(key)) {
                return false;
            }
            Element body = catBodyByKey.remove(key);
            if (body != null && body.getParentElement() != null) {
                Element card = body.getParentElement();
                if (card.getParentElement() != null) {
                    card.getParentElement().removeChild(card);
                }
            }
            rowBindingByKey.keySet().removeIf(k -> k.startsWith(rowPrefixOf(key)));
            return true;
        });
    }

    /** 栏目 key 前缀（行键 = 前缀 + 骨名|序号）。 */
    private static String rowPrefixOf(String cardKey) {
        return cardKey + "|";
    }

    /** 更新既有行 Element：类名 / 序号 / 别名 / 附加文本 / 状态徽标原地写入，并刷新行数据。 */
    private static void updateRowElement(RowBinding binding, RVP_EquipPanelData.Row row) {
        binding.holder().set(row);
        Element div = binding.div();
        div.setClassName("mod-row " + (row.active() ? "ok" : "bad"));
        if (div.children.size() >= 4) {
            div.children.get(0).setTextContent(String.valueOf(row.index()));
            div.children.get(1).setTextContent(row.alias());
            div.children.get(2).setTextContent(row.extra() == null ? "" : row.extra());
            div.children.get(3).setClassName("row-state " + (row.active() ? "ok" : "bad"));
            div.children.get(3).setTextContent(I18n.get(row.active()
                    ? "gui.ywzj_rvp.equipment.state_ok"
                    : "gui.ywzj_rvp.equipment.state_bad"));
        }
    }

    /** 单行：序号 / 别名（+行尾附加文本）/ 状态徽标；失效且可入队的行绑定点击入队。 */
    /** 行绑定：div + 可变行数据（原地更新时刷新，监听闭包读当前值）。 */
    private record RowBinding(Element div,
                              java.util.concurrent.atomic.AtomicReference<RVP_EquipPanelData.Row> holder) {
    }

    /** 构建行 Element（extra 槽恒建，空文本占位——原地更新按固定子序写入）。 */
    private RowBinding buildRow(RVP_EquipPanelData.Row row) {
        java.util.concurrent.atomic.AtomicReference<RVP_EquipPanelData.Row> holder =
                new java.util.concurrent.atomic.AtomicReference<>(row);
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
        Element extra = document.createElement("span");
        extra.setClassName("row-extra");
        extra.setTextContent(row.extra() == null ? "" : row.extra());
        div.appendChild(extra);
        Element state = document.createElement("span");
        state.setClassName("row-state " + (row.active() ? "ok" : "bad"));
        state.setTextContent(I18n.get(row.active()
                ? "gui.ywzj_rvp.equipment.state_ok"
                : "gui.ywzj_rvp.equipment.state_bad"));
        div.appendChild(state);
        // 点击入队：读 holder 当前行（原地更新后语义同步）——失效且可入队才生效
        div.addEventListener("click", event -> {
            RVP_EquipPanelData.Row current = holder.get();
            if (!current.active() && current.queueKey() != null) {
                addToQueue(current.queueKey(), current.boneName(), current.alias());
            }
        });
        return new RowBinding(div, holder);
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
            alias.setTextContent(RVP_VehicleHitboxFactorManager.INSTANCE.resolveHitboxDisplayNameLocalized(vehicle, bone));
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

    /** 预计维修量提示行：ERA 为配额确定值；辅助设备为概率制——期望修复数向上取整（维修以
     *  整台为单位，"预计修 0.3 台"不成立；用户 2026-09-27 定版），括号保留失效数与单台概率。 */
    private void updateForecast() {
        if (repairForecast == null) {
            return;
        }
        RVP_EquipPanelData.Forecast forecast = RVP_EquipPanelData.repairForecast(vehicle);
        // 期望修复数向上取整（1 台×25% → 1；6 台×25% 期望 1.5 → 2）：保守估计，即"修完
        // 全部失效设备最多需要的快修次数"量级
        int expected = (int) Math.ceil(forecast.deviceExpected());
        repairForecast.setTextContent(I18n.get("gui.ywzj_rvp.equipment.forecast",
                forecast.eraQuota(), expected, forecast.deviceDestroyed(), forecast.deviceChancePercent()));
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

    // ─────────────────────────────────────────────────────────────
    // 俯视图（原生 scissor 绘制，div 注入路径已废弃——见 RVP_EquipSkeletonRenderer）
    // ─────────────────────────────────────────────────────────────

    /**
     * 俯视图原生绘制：AUI {@code super.render} 之后执行（AUI 无 post-render 钩子，不会被覆盖）。
     * 画布矩形 = {@code #skeleton-canvas} 布局矩形 × renderScale（项目唯一合法换算公式，
     * RVP_AuiVariantScreen 实证同款）；scissor 裁剪画布区域，深度抬 z=400（tooltip 同级）——
     * AUI 文档元素开深度写入且 z 从 1.0 起递增（物品最高 ~250），GuiGraphics.fill 的 z=0
     * 顶点会被深度剔除，这是此前三次"原生不可见"的根因。
     */
    private void renderSkeletonNative(GuiGraphics guiGraphics, float partialTick) {
        if (skeletonCanvas == null || document == null) {
            return;
        }
        Element.DOMRect rect = skeletonCanvas.getBoundingClientRect();
        if (rect.width <= 8 || rect.height <= 8) {
            // DOM 尚未布局完成：本轮跳过（原生每帧重试，无需节流）
            return;
        }
        double renderScale = document.getViewport().renderScale();
        int x0 = (int) Math.floor(rect.x * renderScale);
        int y0 = (int) Math.floor(rect.y * renderScale);
        int x1 = (int) Math.ceil((rect.x + rect.width) * renderScale);
        int y1 = (int) Math.ceil((rect.y + rect.height) * renderScale);
        guiGraphics.enableScissor(x0, y0, x1, y1);
        PoseStack poseStack = guiGraphics.pose();
        poseStack.pushPose();
        try {
            poseStack.translate(0, 0, 400);
            RVP_EquipSkeletonRenderer.render(guiGraphics, vehicle, cachedModules, x0, y0, x1, y1, partialTick);
            guiGraphics.flush();
        } finally {
            poseStack.popPose();
            guiGraphics.disableScissor();
        }
    }
}
