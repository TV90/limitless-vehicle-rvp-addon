package org.ywzj.rvp.client.screen;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.screen.ApricityScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11;
import org.ywzj.rvp.config.RVP_DeployableUavCatalog;
import org.ywzj.rvp.config.RVP_DeployableUavConfigCache;
import org.ywzj.rvp.client.state.RVP_DeployableUavClientStateCache;
import org.ywzj.rvp.network.C2SRequestDeployableUavState;
import org.ywzj.rvp.network.C2SSelectDeployableUavOption;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.vehicle.custom.CommonAssetsManager;
import org.ywzj.vehicle.custom.vehicle.BaseVehicleData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 独立的辅助载具配置界面。预览实体只用于渲染，绝不加入世界。 */
public final class RVP_AuiAuxiliaryVehicleScreen extends ApricityScreen {
    /** AUI 模板资源路径。 */
    private static final String TEMPLATE = "screens/rvp_auxiliary_vehicles.html";
    /** 服务端状态请求间隔，单位 tick。 */
    private static final long STATE_POLL_TICKS = 20L;
    /** 选择回包等待上限，单位 tick。 */
    private static final long REQUEST_TIMEOUT_TICKS = 100L;

    // 上层本体改装屏和母车仅在本屏存活期间引用，关闭后释放。
    /** 关闭后返回的本体改装屏。 */
    private Screen parent;
    /** 正在改装的母车实例。 */
    private AbstractVehicle parentVehicle;
    // 候选列表来自车型配置缓存；服务器快照决定当前配置与是否可提交。
    /** 当前车型的候选列表。 */
    private final List<RVP_DeployableUavCatalog.Option> options = new ArrayList<>();
    /** 当前屏的 AUI 文档。 */
    private Document document;
    /** 候选列表容器。 */
    private Element optionList;
    /** 当前浏览候选的名称节点。 */
    private Element selectedName;
    /** 当前浏览候选的载具 ID 节点。 */
    private Element selectedId;
    /** 当前浏览候选的联动角色节点。 */
    private Element selectedRole;
    /** 生成参数说明节点。 */
    private Element selectedDetails;
    /** 部署和冷却状态提示节点。 */
    private Element stateText;
    /** 提交选择按钮。 */
    private Element applyButton;
    /** 3D 预览边界节点。 */
    private Element previewAnchor;
    /** 资源不可预览时的提示节点。 */
    private Element previewUnavailable;
    // focusedIndex 是本地浏览项；appliedOptionId 始终只由服务器快照更新。
    /** 当前本地浏览的候选索引。 */
    private int focusedIndex;
    /** 当前浏览候选的稳定 ID。 */
    private String focusedOptionId;
    /** 服务端确认的当前候选 ID。 */
    private String appliedOptionId;
    /** 等待服务端确认的候选 ID。 */
    private String requestedOptionId;
    /** 一次操作的结果提示。 */
    private String feedbackText;
    /** 选择请求所依据的服务端版本。 */
    private long requestedRevision;
    // 只用于预览，不 addFreshEntity，不参与母车/子机真实状态。
    /** 仅用于客户端渲染的独立载具。 */
    private AbstractVehicle previewVehicle;
    /** 鼠标是否正在拖动预览。 */
    private boolean previewDragging;
    /** 选择请求是否仍在等待回复。 */
    private boolean selectPending;
    /** 最近已处理的缓存对象，用于识别新回包。 */
    private RVP_DeployableUavClientStateCache.StateSnapshot stateBaseline;
    // 服务端给的是 tick 冷却快照；以下时间戳用于客户端平滑递减。
    /** 上次查询的客户端游戏 tick。 */
    private long lastRequestTick;
    /** 最近状态到达时的客户端 tick。 */
    private long stateReceivedTick;
    /** 本次选择请求发出时的客户端 tick。 */
    private long selectSentTick;
    /** 倒计时归零后是否已经要求重新同步。 */
    private boolean cooldownRefreshRequested;
    /** 预览模型缩放倍数。 */
    private float viewScale = 1.0f;
    /** 预览模型 X 轴旋转角，度。 */
    private float viewRotX;
    /** 预览模型 Y 轴旋转角，度。 */
    private float viewRotY;
    /** 预览模型水平平移量。 */
    private float viewShiftX;
    /** 预览模型竖直平移量。 */
    private float viewShiftY;
    /** 当前用于渲染和提交门禁的权威快照。 */
    private RVP_DeployableUavClientStateCache.StateSnapshot state;

    public RVP_AuiAuxiliaryVehicleScreen(AbstractVehicle parentVehicle, Screen parent) {
        super(TEMPLATE);
        this.parentVehicle = parentVehicle;
        this.parent = parent;
        this.options.addAll(RVP_DeployableUavConfigCache.getOptions(parentVehicle.getVehicleId()));
        this.setPauseGame(false);
        this.setShowDefaultBackground(false);
    }

    @Override
    protected void init() {
        super.init();
        document = getLinkedDocument();
        if (document == null) return;
        optionList = document.getElementById("aux-option-list");
        selectedName = document.getElementById("aux-selected-name");
        selectedId = document.getElementById("aux-selected-id");
        selectedRole = document.getElementById("aux-selected-role");
        selectedDetails = document.getElementById("aux-selected-details");
        stateText = document.getElementById("aux-state");
        applyButton = document.getElementById("aux-apply");
        previewAnchor = document.getElementById("aux-vehicle-preview");
        previewUnavailable = document.getElementById("aux-preview-unavailable");
        Element closeButton = document.getElementById("close-button");
        if (closeButton != null) closeButton.addEventListener("click", event -> onClose());
        if (applyButton != null) applyButton.addEventListener("click", event -> submitSelection());
        buildOptionList();
        if (!options.isEmpty()) selectOption(0);
        // 旧缓存不可充当本次打开界面的 READY 证明，等待本次状态请求的回包。
        state = null;
        stateBaseline = RVP_DeployableUavClientStateCache.get(parentVehicle.getUUID());
        requestState(true);
        refreshStateWidgets();
    }

    private void buildOptionList() {
        if (optionList == null) return;
        optionList.setTextContent("");
        for (int i = 0; i < options.size(); i++) {
            RVP_DeployableUavCatalog.Option option = options.get(i);
            Element entry = document.createElement("button");
            entry.setClassName("aux-option" + (i == focusedIndex ? " active" : ""));
            entry.setAttribute("type", "button");
            Element name = document.createElement("span");
            name.setClassName("aux-option-name");
            name.setTextContent(resolveOptionName(option));
            Element id = document.createElement("span");
            id.setClassName("aux-option-id");
            id.setTextContent(option.vehicleId().toString());
            entry.appendChild(name);
            entry.appendChild(id);
            int index = i;
            entry.addEventListener("click", event -> selectOption(index));
            optionList.appendChild(entry);
        }
    }

    /** 候选名称按当前语言解析；中文配置字面量不能覆盖载具已有的中英文翻译。 */
    private static String resolveOptionName(RVP_DeployableUavCatalog.Option option) {
        String configuredName = option.name();
        // 配置可直接填写自定义翻译键，优先保留同一载具不同候选的命名能力。
        if (configuredName != null && !configuredName.isBlank() && I18n.exists(configuredName)) {
            return I18n.get(configuredName);
        }
        // 沿用本体 VehicleDataManager 的 entity.<namespace>.<path> 命名规则，不创建预览实体。
        String vehicleNameKey = "entity." + option.vehicleId().getNamespace() + "." + option.vehicleId().getPath();
        if (I18n.exists(vehicleNameKey)) {
            return I18n.get(vehicleNameKey);
        }
        // 缺少语言资源时保留作者配置名称，最终使用载具 ID 兜底。
        return configuredName == null || configuredName.isBlank() ? option.vehicleId().toString() : configuredName;
    }

    private void selectOption(int index) {
        if (index < 0 || index >= options.size()) return;
        focusedIndex = index;
        RVP_DeployableUavCatalog.Option option = options.get(index);
        focusedOptionId = option.id();
        if (optionList != null) {
            List<Element> entries = optionList.querySelectorAll(".aux-option");
            for (int i = 0; i < entries.size(); i++) {
                entries.get(i).setClassName("aux-option" + (i == focusedIndex ? " active" : "")
                    + (options.get(i).id().equals(appliedOptionId) ? " current" : ""));
            }
        }
        if (selectedName != null) selectedName.setTextContent(resolveOptionName(option));
        if (selectedId != null) selectedId.setTextContent(option.vehicleId().toString());
        if (selectedRole != null) selectedRole.setTextContent(option.role());
        if (selectedDetails != null) {
            var offset = option.spawnOffset();
            selectedDetails.setTextContent(I18n.get("gui.ywzj_rvp.auxiliary.details",
                    String.format(java.util.Locale.ROOT, "%.1f, %.1f, %.1f", offset.x, offset.y, offset.z),
                    option.spawnYawMode(), option.initialSpeed()));
        }
        createPreview(option);
        refreshStateWidgets();
    }

    /** 每次切换候选时构造一次独立预览对象；该对象不会注册进 Level。 */
    private void createPreview(RVP_DeployableUavCatalog.Option option) {
        previewVehicle = null;
        setPreviewUnavailable("");
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            setPreviewUnavailable(I18n.get("gui.ywzj_rvp.auxiliary.preview_unavailable"));
            return;
        }
        BaseVehicleData<?> vehicleData = CommonAssetsManager.vehicleDataManager()
                .getVehicleData(option.vehicleId()).orElse(null);
        if (vehicleData == null) {
            setPreviewUnavailable(I18n.get("gui.ywzj_rvp.auxiliary.preview_unavailable"));
            return;
        }
        try {
            previewVehicle = vehicleData.construct(mc.level, Vec3.ZERO, 0, 0);
            previewVehicle.initData();
            previewVehicle.initDisplayData();
            viewScale = 1.0f;
            viewRotX = 180.0f;
            viewRotY = mc.player == null ? 180.0f : 180.0f + mc.player.getYRot();
            viewShiftX = 0;
            viewShiftY = 0;
        } catch (RuntimeException exception) {
            previewVehicle = null;
            setPreviewUnavailable(I18n.get("gui.ywzj_rvp.auxiliary.preview_unavailable"));
        }
    }

    private void setPreviewUnavailable(String text) {
        if (previewUnavailable != null && !text.equals(previewUnavailable.getTextContent())) {
            previewUnavailable.setTextContent(text);
        }
    }

    private void requestState(boolean force) {
        if (parentVehicle == null || Minecraft.getInstance().level == null) return;
        long now = Minecraft.getInstance().level.getGameTime();
        if (!force && now - lastRequestTick < STATE_POLL_TICKS) return;
        lastRequestTick = now;
        RVP_Network.CHANNEL.sendToServer(new C2SRequestDeployableUavState(
                parentVehicle.getId(), parentVehicle.getUUID()));
    }

    private void submitSelection() {
        if (!canSubmit() || parentVehicle == null) return;
        RVP_DeployableUavCatalog.Option option = options.get(focusedIndex);
        requestedOptionId = option.id();
        requestedRevision = state.revision();
        feedbackText = null;
        RVP_Network.CHANNEL.sendToServer(new C2SSelectDeployableUavOption(
                parentVehicle.getId(), parentVehicle.getUUID(), requestedOptionId, requestedRevision));
        selectPending = true;
        selectSentTick = Minecraft.getInstance().level == null ? 0 : Minecraft.getInstance().level.getGameTime();
        refreshStateWidgets();
    }

    private boolean canSubmit() {
        if (state == null || selectPending || focusedIndex < 0 || focusedIndex >= options.size()) return false;
        if (focusedOptionId == null || focusedOptionId.equals(state.selectedOptionId())) return false;
        return !state.deployed() && state.remainingCooldownTick() <= 0
                && (state.lockedReason() == null || state.lockedReason().isBlank());
    }

    @Override
    public void tick() {
        super.tick();
        if (parentVehicle == null || Minecraft.getInstance().level == null) return;
        long now = Minecraft.getInstance().level.getGameTime();
        if (parentVehicle.isRemoved() || parentVehicle.level() != Minecraft.getInstance().level) {
            onClose();
            return;
        }
        requestState(false);
        RVP_DeployableUavClientStateCache.StateSnapshot latest =
                RVP_DeployableUavClientStateCache.get(parentVehicle.getUUID());
        if (latest != null && latest != stateBaseline) {
            String previousSelectedOptionId = state == null ? null : state.selectedOptionId();
            boolean wasSelectPending = selectPending;
            state = latest;
            stateBaseline = latest;
            stateReceivedTick = now;
            cooldownRefreshRequested = false;
            if (selectPending) {
                if (requestedOptionId != null && requestedOptionId.equals(latest.selectedOptionId())
                        && latest.revision() >= requestedRevision) {
                    selectPending = false;
                    feedbackText = I18n.get("gui.ywzj_rvp.auxiliary.selected");
                } else if (latest.revision() > requestedRevision
                        || (latest.lockedReason() != null && !latest.lockedReason().isBlank())) {
                    selectPending = false;
                    String reason = latest.lockedReason();
                    feedbackText = I18n.get("gui.ywzj_rvp.auxiliary.rejected",
                            reason == null || reason.isBlank()
                                    ? I18n.get("gui.ywzj_rvp.auxiliary.state_changed") : translateReason(reason));
                }
            } else if (!wasSelectPending) {
                // 后续服务端状态（例如开始冷却）覆盖一次性成功反馈。
                feedbackText = null;
            }
            appliedOptionId = latest.selectedOptionId();
            if (!java.util.Objects.equals(previousSelectedOptionId, appliedOptionId)) refreshCurrentMarkers();
        }
        if (state != null && state.remainingCooldownTick() > 0 && remainingCooldownSeconds() == 0
                && !cooldownRefreshRequested) {
            cooldownRefreshRequested = true;
            requestState(true);
        }
        if (selectPending && now - selectSentTick >= REQUEST_TIMEOUT_TICKS) {
            selectPending = false;
            feedbackText = I18n.get("gui.ywzj_rvp.auxiliary.timeout");
            requestState(true);
        }
        refreshStateWidgets();
    }

    private void refreshCurrentMarkers() {
        if (optionList == null) return;
        List<Element> entries = optionList.querySelectorAll(".aux-option");
        for (int i = 0; i < entries.size() && i < options.size(); i++) {
            entries.get(i).setClassName("aux-option" + (i == focusedIndex ? " active" : "")
                    + (options.get(i).id().equals(appliedOptionId) ? " current" : ""));
        }
    }

    private void refreshStateWidgets() {
        if (stateText != null) {
            String text;
            if (selectPending) {
                text = I18n.get("gui.ywzj_rvp.auxiliary.waiting");
            } else if (feedbackText != null) {
                text = feedbackText;
            } else if (state == null) {
                text = I18n.get("gui.ywzj_rvp.auxiliary.syncing");
            } else if (state.deployed()) {
                text = I18n.get("ui.ywzj_rvp.uav.option_deployed");
            } else {
                long cooldown = remainingCooldownSeconds();
                String reason = state.lockedReason();
                if (cooldown > 0 || "cooldown".equals(reason)) {
                    text = I18n.get("ui.ywzj_rvp.uav.option_cooldown", cooldown);
                } else if (reason != null && !reason.isBlank()) {
                    text = I18n.get("ui.ywzj_rvp.uav.option_locked", translateReason(reason));
                } else {
                    String selectedOptionId = state.selectedOptionId();
                    // 当前配置同样使用本地化名称；提交和状态匹配仍保留服务端稳定 ID。
                    String currentName = options.stream().filter(option -> option.id().equals(selectedOptionId))
                            .findFirst().map(RVP_AuiAuxiliaryVehicleScreen::resolveOptionName)
                            .orElse(selectedOptionId == null
                                    ? I18n.get("gui.ywzj_rvp.auxiliary.none") : selectedOptionId);
                    text = I18n.get("ui.ywzj_rvp.uav.option_current", currentName);
                }
            }
            if (!text.equals(stateText.getTextContent())) stateText.setTextContent(text);
        }
        if (applyButton != null) {
            boolean enabled = canSubmit();
            String className = "button button-primary" + (enabled ? "" : " disabled");
            if (!className.equals(applyButton.getAttribute("class"))) applyButton.setClassName(className);
            String ariaDisabled = Boolean.toString(!enabled);
            if (!ariaDisabled.equals(applyButton.getAttribute("aria-disabled"))) {
                applyButton.setAttribute("aria-disabled", ariaDisabled);
            }
        }
    }

    private String translateReason(String reason) {
        return switch (reason) {
            case "cooldown" -> I18n.get("ui.ywzj_rvp.uav.option_cooldown", remainingCooldownSeconds());
            case "returning" -> I18n.get("gui.ywzj_rvp.auxiliary.reason.returning");
            case "terminating" -> I18n.get("gui.ywzj_rvp.auxiliary.reason.terminating");
            case "too_far" -> I18n.get("gui.ywzj_rvp.auxiliary.reason.too_far");
            case "tool_required" -> I18n.get("gui.ywzj_rvp.auxiliary.reason.tool_required");
            case "option_invalid" -> I18n.get("gui.ywzj_rvp.auxiliary.reason.option_invalid");
            case "option_unavailable" -> I18n.get("ui.ywzj_rvp.uav.option_unavailable");
            case "modification_locked" -> I18n.get("gui.ywzj_rvp.auxiliary.reason.modification_locked");
            case "stale_revision" -> I18n.get("gui.ywzj_rvp.auxiliary.reason.stale_revision");
            case "parent_unavailable" -> I18n.get("gui.ywzj_rvp.auxiliary.reason.parent_unavailable");
            case "deployed" -> I18n.get("gui.ywzj_rvp.auxiliary.reason.deployed");
            default -> reason;
        };
    }

    private long remainingCooldownSeconds() {
        if (state == null || Minecraft.getInstance().level == null) return 0;
        long elapsed = Math.max(0, Minecraft.getInstance().level.getGameTime() - stateReceivedTick);
        long ticks = Math.max(0, state.remainingCooldownTick() - elapsed);
        return (ticks + 19) / 20;
    }

    @Override
    public void onClose() {
        Screen returnTo = parent;
        super.onClose();
        Minecraft.getInstance().setScreen(returnTo);
        releaseScreenReferences();
    }

    @Override
    public void removed() {
        super.removed();
        releaseScreenReferences();
    }

    private void releaseScreenReferences() {
        previewVehicle = null;
        options.clear();
        state = null;
        stateBaseline = null;
        feedbackText = null;
        document = null;
        optionList = null;
        previewAnchor = null;
        previewUnavailable = null;
        parentVehicle = null;
        parent = null;
    }

    private record Bounds(double x, double y, double x2, double y2) {
        boolean contains(double mx, double my) { return mx >= x && mx <= x2 && my >= y && my <= y2; }
    }

    private Bounds getPreviewBounds() {
        if (previewAnchor == null || document == null) return null;
        Element.DOMRect rect = previewAnchor.getBoundingClientRect();
        if (rect.width <= 0 || rect.height <= 0) return null;
        double scale = document.getViewport().renderScale();
        return new Bounds(rect.x * scale, rect.y * scale,
                (rect.x + rect.width) * scale, (rect.y + rect.height) * scale);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        Bounds bounds = getPreviewBounds();
        if (bounds != null && bounds.contains(mouseX, mouseY)) {
            viewScale = net.minecraft.util.Mth.clamp(viewScale * (float) Math.pow(1.1, delta), 0.1f, 10.0f);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        Bounds bounds = getPreviewBounds();
        if ((button == 0 || button == 1) && bounds != null && bounds.contains(mouseX, mouseY)) {
            previewDragging = true;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (previewDragging && button == 0) { viewRotY += (float) dragX; viewRotX += (float) dragY; return true; }
        if (previewDragging && button == 1) { viewShiftX += (float) dragX; viewShiftY += (float) dragY; return true; }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (previewDragging && (button == 0 || button == 1)) { previewDragging = false; return true; }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        renderVehiclePreview(guiGraphics);
    }

    private void renderVehiclePreview(GuiGraphics guiGraphics) {
        AbstractVehicle vehicle = previewVehicle;
        Bounds bounds = getPreviewBounds();
        if (vehicle == null || bounds == null) return;
        double width = bounds.x2() - bounds.x();
        double height = bounds.y2() - bounds.y();
        double scale = Math.min(width, height) * 1.5 / Math.max(vehicle.getStructureLength(), 3) * viewScale;
        guiGraphics.enableScissor((int) bounds.x(), (int) bounds.y(), (int) Math.ceil(bounds.x2()), (int) Math.ceil(bounds.y2()));
        boolean scissorEnabled = true;
        PoseStack poseStack = guiGraphics.pose();
        poseStack.pushPose();
        var dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        try {
            poseStack.translate((float) (bounds.x() + bounds.x2()) / 2 + viewShiftX,
                    (float) (bounds.y() + bounds.y2()) / 2 + viewShiftY, 512);
            poseStack.mulPoseMatrix(new org.joml.Matrix4f().scaling((float) scale, (float) scale, (float) -scale));
            poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(viewRotX));
            poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(viewRotY));
            Vec3 centerOffset = vehicle.getBoundingBox().getCenter().subtract(vehicle.position());
            poseStack.translate((float) -centerOffset.x, (float) -centerOffset.y, (float) -centerOffset.z);
            dispatcher.setRenderShadow(false);
            Lighting.setupForEntityInInventory();
            RenderSystem.runAsFancy(() -> dispatcher.render(vehicle, 0, 0, 0, 0, 1.0F,
                    poseStack, guiGraphics.bufferSource(), 15728880));
            guiGraphics.flush();
            guiGraphics.disableScissor();
            scissorEnabled = false;
            RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, false);
        } finally {
            dispatcher.setRenderShadow(true);
            Lighting.setupFor3DItems();
            poseStack.popPose();
            if (scissorEnabled) guiGraphics.disableScissor();
        }
    }
}
