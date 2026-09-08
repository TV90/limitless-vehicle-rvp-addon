package org.ywzj.rvp.client.firesupport;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import org.ywzj.rvp.network.firesupport.RVP_FireSupportClientEndpoint;
import org.ywzj.rvp.network.firesupport.S2CFireSupportMissionUpdate;
import org.ywzj.rvp.network.firesupport.S2CFireSupportProfileSnapshot;
import org.ywzj.rvp.network.firesupport.S2CFireSupportRequestResult;
import org.ywzj.rvp.firesupport.server.RVP_FireSupportTerminalIdentity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 阶段 C 客户端不可变快照；阶段 D 地图工具将只读取这里的权威状态。 */
public final class RVP_ClientFireSupportState implements RVP_FireSupportClientEndpoint.Listener {
    /** 单例监听器，由客户端 bootstrap 安装。 */ public static final RVP_ClientFireSupportState INSTANCE = new RVP_ClientFireSupportState();
    /** 当前服务端 profile revision。 */ private volatile long revision;
    /** 是否已经收到至少一份服务端 profile 快照。 */ private volatile boolean profileSnapshotReady;
    /** 规范化 profile JSON；String 与 Map 均不可变，阶段 D 读取时再解析。 */ private volatile Map<ResourceLocation, String> profiles = Map.of();
    /** 最近一次请求结果。 */ private volatile S2CFireSupportRequestResult lastResult;
    /** 当前玩家可见的任务状态。 */ private final Map<UUID, S2CFireSupportMissionUpdate> missions = new LinkedHashMap<>();
    /** 当前客户端会话内保留的终端草稿；profile revision 改变时清空。 */ private DraftSelection draftSelection;
    /** 当前 profile 快照生成的终端 ItemStack 变体。 */ private volatile java.util.List<ItemStack> itemVariants = java.util.List.of();
    /** 玩家尚未进入世界时延迟执行的创造栏重建请求。 */ private volatile boolean creativeRebuildPending;

    private RVP_ClientFireSupportState() {}

    @Override
    public synchronized void onProfiles(S2CFireSupportProfileSnapshot message) {
        if (revision != message.revision()) draftSelection = null;
        profiles = Map.copyOf(message.profiles());
        revision = message.revision();
        profileSnapshotReady = true;
        java.util.List<ItemStack> variants = new java.util.ArrayList<>();
        profiles.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            try {
                RVP_ClientFireSupportProfile profile = RVP_ClientFireSupportProfile.parse(entry.getKey(), entry.getValue());
                // 调用本项目终端身份工厂：把规范化 profile 转成带 profile NBT 的展示 ItemStack。
                variants.add(RVP_FireSupportTerminalIdentity.createForProfile(
                        profile.id(), profile.itemTranslationKey()));
            } catch (RuntimeException ignored) {
                // 错误 profile 由地图工具显示，不进入创造栏，避免生成不可用物品。
            }
        });
        itemVariants = java.util.List.copyOf(variants);
        creativeRebuildPending = true;
    }

    @Override
    public void onRequestResult(S2CFireSupportRequestResult message) {
        lastResult = message;
    }

    @Override
    public synchronized void onMissionUpdate(S2CFireSupportMissionUpdate message) {
        missions.put(message.missionId(), message);
    }

    /** @return 当前权威 revision。 */ public long revision() { return revision; }
    /** @return 当前规范化 profile JSON 不可变视图。 */ public Map<ResourceLocation, String> profiles() { return profiles; }
    /** @return 客户端快照中是否仍存在指定 profile；快照尚未同步时暂不判失效。 */
    public boolean isProfileAvailable(ResourceLocation profileId) {
        return !profileSnapshotReady || profiles.containsKey(profileId);
    }
    /** @return 当前 profile 快照对应的终端 ItemStack 变体。 */ public java.util.List<ItemStack> itemVariants() { return itemVariants; }
    /** 在客户端 Tick 中重建创造栏，使新 profile 变体及时可见。 */
    public void clientTick() {
        if (!creativeRebuildPending) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) return;
        CreativeModeTabs.tryRebuildTabContents(minecraft.player.connection.enabledFeatures(), true,
                minecraft.level.registryAccess());
        creativeRebuildPending = false;
    }
    /** @return 最近一次请求结果，可为空。 */ public S2CFireSupportRequestResult lastResult() { return lastResult; }
    /** @return 当前任务状态不可变副本。 */ public synchronized Map<UUID, S2CFireSupportMissionUpdate> missions() { return Map.copyOf(missions); }
    /** @return 当前会话草稿；尚未编辑或已经 reload 时为空。 */ public synchronized DraftSelection draftSelection() { return draftSelection; }
    /** 保存终端界面草稿，使关闭地图后重新打开仍可恢复全部选择。 */
    public synchronized void rememberDraft(DraftSelection draft) { draftSelection = draft; }
    /** 重置按钮调用此方法清除草稿；当前界面随后按 profile 默认值重建。 */
    public synchronized void resetDraft() { draftSelection = null; }

    /** @return 最近收到更新的任务；优先返回未终结任务，否则返回最近终态。 */
    public synchronized S2CFireSupportMissionUpdate latestMission() {
        S2CFireSupportMissionUpdate latest = null;
        for (S2CFireSupportMissionUpdate mission : missions.values()) {
            if (!terminal(mission)) latest = mission;
            else if (latest == null) latest = mission;
        }
        if (latest != null && !terminal(latest)) return latest;
        for (S2CFireSupportMissionUpdate mission : missions.values()) latest = mission;
        return latest;
    }

    private static boolean terminal(S2CFireSupportMissionUpdate mission) {
        return switch (mission.state()) {
            case COMPLETED, CANCELLED, CEASED, FAILED -> true;
            default -> false;
        };
    }

    /** 一份与 Screen 生命周期解耦的客户端会话草稿。 */
    public record DraftSelection(
            /** profile 资源 ID。 */ ResourceLocation profileId,
            /** profile 内弹药方案 ID。 */ String munitionId,
            /** profile 内打击模式 ID。 */ String fireModeId,
            /** profile 内打击预设 ID。 */ String patternId,
            /** 当前动态参数不可变副本。 */ Map<String, Double> parameters,
            /** 当前目标锚点 X。 */ double anchorX,
            /** 当前目标锚点 Z。 */ double anchorZ,
            /** 是否已经指定目标锚点。 */ boolean hasAnchor,
            /** 当前落区方向角，单位度。 */ double headingDegrees,
            /** 当前独立入场方向角，单位度。 */ double inboundHeadingDegrees) {
        public DraftSelection { parameters = Map.copyOf(parameters); }
    }
}
