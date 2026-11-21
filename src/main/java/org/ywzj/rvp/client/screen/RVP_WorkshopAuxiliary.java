package org.ywzj.rvp.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.client.state.RVP_DeployableUavClientStateCache;
import org.ywzj.rvp.config.RVP_DeployableUavCatalog;
import org.ywzj.rvp.config.RVP_DeployableUavConfigCache;
import org.ywzj.rvp.network.C2SRequestDeployableUavState;
import org.ywzj.rvp.network.C2SSelectDeployableUavOption;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.vehicle.custom.CommonAssetsManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.List;

/** 同页辅助载具选择。候选预览留在客户端，当前装备以母车的服务端快照为准。 */
final class RVP_WorkshopAuxiliary {
    private static final long STATE_POLL_TICKS = 20L;
    private static final long REQUEST_TIMEOUT_TICKS = 100L;
    private final AbstractVehicle parentVehicle;
    final List<RVP_DeployableUavCatalog.Option> options;
    private RVP_DeployableUavClientStateCache.StateSnapshot state, stateBaseline;
    private long lastRequestTick, stateReceivedTick, selectSentTick, requestedRevision;
    private boolean selectPending, cooldownRefreshRequested;
    private String requestedOptionId, feedbackText;
    private int queuedIndex = -1, requestedIndex = -1;
    private long queuedTick;
    int focusedIndex = -1;
    AbstractVehicle previewVehicle;

    RVP_WorkshopAuxiliary(AbstractVehicle parentVehicle) {
        this.parentVehicle = parentVehicle;
        options = List.copyOf(RVP_DeployableUavConfigCache.getOptions(parentVehicle.getVehicleId()));
    }

    void enter() {
        // 选项提交需要本次打开后的回包，即使本机已有缓存。
        if (!selectPending) {
            state = null;
            stateBaseline = RVP_DeployableUavClientStateCache.get(parentVehicle.getUUID());
            feedbackText = null;
        }
        requestState(true);
    }

    int appliedIndex() {
        if (state == null) return -1;
        for (int i = 0; i < options.size(); i++)
            if (options.get(i).id().equals(state.selectedOptionId())) return i;
        return -1;
    }

    String appliedName() {
        int index = appliedIndex();
        if (index >= 0) return resolveOptionName(options.get(index));
        return I18n.get(state == null ? "gui.ywzj_rvp.auxiliary.syncing" : "gui.ywzj_rvp.auxiliary.none");
    }

    /** 联动角色的可读名称；没有对应翻译时回退原始角色值。 */
    static String resolveRoleLabel(String role) {
        if (role == null || role.isBlank()) return "";
        String key = "gui.ywzj_rvp.auxiliary.role." + role.toLowerCase(java.util.Locale.ROOT);
        return I18n.exists(key) ? I18n.get(key) : role;
    }

    /** 候选名称按当前语言解析；中文配置字面量不能覆盖载具已有的中英文翻译。 */
    static String resolveOptionName(RVP_DeployableUavCatalog.Option option) {
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

    String deploymentDescription() {
        var option = options.get(focusedIndex);
        String hint = I18n.get("parent".equalsIgnoreCase(option.spawnYawMode())
                ? "gui.ywzj_rvp.auxiliary.deploy_follow" : "gui.ywzj_rvp.auxiliary.deploy_fixed");
        return option.initialSpeed() <= 0 ? hint : I18n.get("gui.ywzj_rvp.auxiliary.deploy_speed", hint,
                String.format(java.util.Locale.ROOT, "%.1f", option.initialSpeed()));
    }

    void focus(int index) {
        if (index == focusedIndex) return;
        focusedIndex = index;
        previewVehicle = null;
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        var data = CommonAssetsManager.vehicleDataManager().getVehicleData(options.get(index).vehicleId()).orElse(null);
        if (data == null) return;
        try {
            var model = data.construct(level, Vec3.ZERO, 0, 0);
            model.initData();
            model.initDisplayData();
            previewVehicle = model;
        } catch (RuntimeException unavailable) {
            previewVehicle = null;
        }
    }

    private void requestState(boolean force) {
        if (Minecraft.getInstance().level == null) return;
        long now = Minecraft.getInstance().level.getGameTime();
        if (!force && now - lastRequestTick < STATE_POLL_TICKS) return;
        lastRequestTick = now;
        RVP_Network.CHANNEL.sendToServer(new C2SRequestDeployableUavState(
                parentVehicle.getId(), parentVehicle.getUUID()));
    }

    int selectedIndex() {
        if (queuedIndex >= 0) return queuedIndex;
        return selectPending ? requestedIndex : appliedIndex();
    }

    /** 点击时提交；若上一笔尚未回包，只保留用户最后点选的候选。 */
    void choose(int index) {
        if (Minecraft.getInstance().level == null) return;
        queuedIndex = index;
        queuedTick = Minecraft.getInstance().level.getGameTime();
        submitQueued();
    }

    private void submitQueued() {
        if (queuedIndex < 0 || state == null || selectPending) return;
        int index = queuedIndex;
        queuedIndex = -1;
        var option = options.get(index);
        if (option.id().equals(state.selectedOptionId()) || state.deployed() || state.remainingCooldownTick() > 0
                || state.lockedReason() != null && !state.lockedReason().isBlank()) return;
        requestedIndex = index;
        requestedOptionId = option.id();
        requestedRevision = state.revision();
        feedbackText = null;
        RVP_Network.CHANNEL.sendToServer(new C2SSelectDeployableUavOption(
                parentVehicle.getId(), parentVehicle.getUUID(), requestedOptionId, requestedRevision));
        selectPending = true;
        selectSentTick = Minecraft.getInstance().level == null ? 0 : Minecraft.getInstance().level.getGameTime();
    }

    void tick(boolean visible) {
        if (!visible && !selectPending && queuedIndex < 0 && state != null) return;
        if (Minecraft.getInstance().level == null) return;
        long now = Minecraft.getInstance().level.getGameTime();
        if (visible || selectPending || queuedIndex >= 0) requestState(false);
        RVP_DeployableUavClientStateCache.StateSnapshot latest =
                RVP_DeployableUavClientStateCache.get(parentVehicle.getUUID());
        if (latest != null && latest != stateBaseline) {
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
            } else {
                // 后续服务端状态（例如开始冷却）覆盖一次性成功反馈。
                feedbackText = null;
            }
        }
        if (state != null && state.remainingCooldownTick() > 0 && remainingCooldownSeconds() == 0
                && !cooldownRefreshRequested) {
            cooldownRefreshRequested = true;
            requestState(true);
        }
        if ((selectPending && now - selectSentTick >= REQUEST_TIMEOUT_TICKS)
                || (queuedIndex >= 0 && now - queuedTick >= REQUEST_TIMEOUT_TICKS)) {
            queuedIndex = -1;
            selectPending = false;
            feedbackText = I18n.get("gui.ywzj_rvp.auxiliary.timeout");
            requestState(true);
        } else {
            submitQueued();
        }
    }

    String status() {
        if (selectPending) return I18n.get("gui.ywzj_rvp.auxiliary.waiting");
        if (feedbackText != null) return feedbackText;
        if (state == null) return I18n.get("gui.ywzj_rvp.auxiliary.syncing");
        if (state.deployed()) return I18n.get("ui.ywzj_rvp.uav.option_deployed");
        String reason = state.lockedReason();
        long cooldown = remainingCooldownSeconds();
        if (cooldown > 0 || "cooldown".equals(reason)) return I18n.get("ui.ywzj_rvp.uav.option_cooldown", cooldown);
        if (reason != null && !reason.isBlank()) return I18n.get("ui.ywzj_rvp.uav.option_locked", translateReason(reason));
        return I18n.get("ui.ywzj_rvp.uav.option_current", appliedName());
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
}
