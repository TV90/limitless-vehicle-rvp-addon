package org.ywzj.rvp.firesupport.server;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import com.google.gson.JsonObject;
import org.ywzj.rvp.firesupport.data.RVP_FireSupportProfile;
import org.ywzj.rvp.firesupport.data.RVP_FireSupportRequest;
import org.ywzj.rvp.firesupport.pattern.RVP_FireSupportPatternTypes;
import org.ywzj.rvp.weapon.data.RVP_PodFireSupportEntryData;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.CommonAssetsManager;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 坐标指示吊舱内联火力支援注册表（2026-10-13 整合批）。
 *
 * <p>把全部吊舱武器 JSON 的 {@code rvp_fire_support} 内联条目转换为运行时
 * {@link RVP_FireSupportProfile}（id 形如 {@code rvp:pod/<武器path>/<条目id>}），
 * 供 {@code RVP_FireSupportRequestValidator} 的 POD 分支直查——**不进入** datapack
 * profile 快照与客户端同步，因此终端 UI 天然看不到、互不污染。任务生态
 * （呼叫引导/排程/投送/冷却/幂等）由 {@code MissionManager.submit} 完整复用。</p>
 *
 * <p>重建时机：{@code OnDatapackSyncEvent}（数据包 reload 与玩家登录均触发，见
 * {@code #onDatapackSync}）；Validator POD 分支读取前不重建（reload 后必然已重建）。</p>
 */
public final class RVP_PodFireSupportRegistry {

    /** 运行时 profile ID 前缀；终端 UI 据此天然隔离（不在快照内），Validator 据此识别 POD 来源。 */
    public static final String PROFILE_ID_PREFIX = "rvp:pod/";
    /** POD profile 内固定的单一弹药方案/模式/预设 ID（内联条目本身即完整配置，无多层选择）。 */
    public static final String FIXED_MUNITION_ID_SUFFIX = "self";
    public static final String FIXED_FIRE_MODE_ID = "standard";
    public static final String FIXED_PATTERN_ID = "point";
    /** POD 任务的确定性终端占位 UUID；guard 的 terminalLost 分支被 CallStage=false 关闭，仅作记录。 */
    private static final UUID POD_SENTINEL_TERMINAL = UUID.nameUUIDFromBytes("rvp-pod-fire-support".getBytes());

    /** 已转换的运行时 profile 表：key = rvp:pod/<weaponPath>/<entryId>。 */
    private static volatile Map<ResourceLocation, RVP_FireSupportProfile> POD_PROFILES = Map.of();
    /** 按武器 path 索引的内联条目：供触发链与吊舱 UI 读取。 */
    private static volatile Map<String, List<RVP_PodFireSupportEntryData>> ENTRIES_BY_WEAPON = Map.of();

    private RVP_PodFireSupportRegistry() {}

    /** 数据包同步事件回调：从全部武器数据重建注册表（服务器端执行；reload/登录均会触发）。 */
    public static void rebuild() {
        Map<ResourceLocation, RVP_FireSupportProfile> profiles = new HashMap<>();
        Map<String, List<RVP_PodFireSupportEntryData>> byWeapon = new HashMap<>();
        // 调用本体武器管理器索引：遍历全部已加载武器 JSON，收集吊舱内联火力条目。
        CommonAssetsManager.vehicleWeaponManager().getIndexes().forEach((weaponId, index) -> {
            if (!(index.data() instanceof RVP_WeaponData data)) {
                return;
            }
            List<RVP_PodFireSupportEntryData> entries = data.getTargetingPodData() == null
                    ? List.of() : data.getTargetingPodData().getFireSupportEntries();
            if (entries.isEmpty()) {
                return;
            }
            String weaponPath = weaponId.getPath();
            for (RVP_PodFireSupportEntryData entry : entries) {
                if (!entry.isValid()) {
                    continue;
                }
                RVP_FireSupportProfile profile = buildProfile(weaponPath, entry);
                if (profile != null) {
                    profiles.put(profileId(weaponPath, entry.getId()), profile);
                }
            }
            byWeapon.put(weaponPath, entries);
        });
        POD_PROFILES = Map.copyOf(profiles);
        ENTRIES_BY_WEAPON = Map.copyOf(byWeapon);
    }

    /** @return 运行时 profile；未注册（武器无配置/条目非法/reload 前旧 ID）返回 null。 */
    public static RVP_FireSupportProfile getProfile(ResourceLocation profileId) {
        return POD_PROFILES.get(profileId);
    }

    /** @return 指定武器的内联条目列表（只读快照；无配置返回空列表）。 */
    public static List<RVP_PodFireSupportEntryData> entriesFor(String weaponPath) {
        List<RVP_PodFireSupportEntryData> entries = ENTRIES_BY_WEAPON.get(weaponPath);
        return entries == null ? List.of() : entries;
    }

    /** @return 运行时 profile ID（rvp:pod/<weaponPath>/<entryId>）。 */
    public static ResourceLocation profileId(String weaponPath, String entryId) {
        return ResourceLocation.fromNamespaceAndPath("rvp",
                "pod/" + weaponPath + "/" + entryId.toLowerCase(Locale.ROOT));
    }

    /**
     * 吊舱方块标记后的自动触发入口：按玩家已保存选择组装 POD 请求并提交任务，
     * 结果经 actionbar 反馈操作者。坐标使用标记命中点，不读写 GPS 管理器。
     *
     * @param weaponPath 吊舱武器 path（选择持久化键的一部分）
     * @param target 方块标记命中点（世界坐标）
     */
    public static void tryTrigger(ServerPlayer player, String weaponPath, Vec3 target) {
        List<RVP_PodFireSupportEntryData> entries = entriesFor(weaponPath);
        if (entries.isEmpty()) {
            return;
        }
        String savedEntryId = RVP_PodFireSupportSelectionStore.getSelection(player, weaponPath);
        if (savedEntryId == null || savedEntryId.isBlank()) {
            // 未选配置：静默返回由 UI 引导（地图工具会提示）；不刷 actionbar 干扰正常标记。
            return;
        }
        RVP_PodFireSupportEntryData entry = null;
        for (RVP_PodFireSupportEntryData candidate : entries) {
            if (candidate.getId().equals(savedEntryId)) {
                entry = candidate;
                break;
            }
        }
        if (entry == null || !entry.isValid()) {
            // 已保存选择指向不存在的条目（reload 删除等）：提示玩家重新选择。
            player.displayClientMessage(Component.translatable(
                    "message.ywzj_rvp.pod_fire_support.selection_stale"), true);
            return;
        }
        ResourceLocation profileId = profileId(weaponPath, entry.getId());
        // 组装 POD 请求：hand 为占位（Validator POD 分支不校验手），revision 由服务端快照自洽，
        // heading/inbound 对单点几何无意义固定 0，参数带 point 几何的散布半径默认值。
        RVP_FireSupportRequest request = new RVP_FireSupportRequest(
                org.ywzj.rvp.firesupport.config.RVP_FireSupportProfileManager.INSTANCE.snapshot().revision(),
                net.minecraft.world.InteractionHand.MAIN_HAND,
                profileId,
                munitionIdFor(entry.getId()),
                FIXED_FIRE_MODE_ID,
                FIXED_PATTERN_ID,
                target.x,
                target.z,
                0.0,
                0.0,
                Map.of("radius_m", entry.getScatterRadiusM()),
                UUID.randomUUID(),
                RVP_FireSupportRequest.Source.POD);
        // 调用本项目任务管理器：完整复用呼叫引导/排程/冷却/幂等链路。
        RVP_FireSupportMissionManager.SubmissionResult result =
                RVP_FireSupportMissionManager.submit(player, request);
        if (result.accepted()) {
            int callSeconds = Math.max(1, (int) Math.round(result.callDeadlineTick() - result.firstRoundTick()) / 20);
            player.displayClientMessage(Component.translatable(
                    "message.ywzj_rvp.pod_fire_support.called", entry.getDisplayCn(), callSeconds), true);
        } else {
            // 调用本项目拒绝原因：转换为玩家可读的 actionbar 反馈。
            player.displayClientMessage(Component.translatable(
                    "message.ywzj_rvp.pod_fire_support.rejected." + result.reason().name().toLowerCase(Locale.ROOT)), true);
        }
    }

    /** @return 内联条目对应的弹药方案 ID（= 条目 ID，保证同一武器内唯一）。 */
    public static String munitionIdFor(String entryId) {
        return entryId.toLowerCase(Locale.ROOT);
    }

    /**
     * 把内联条目转换为运行时 profile：单弹药方案 + 单"齐射"模式 + point 几何；
     * {@code cancelOnTerminalLost=false} 内置于 CallStage——POD 任务不依赖终端物品，
     * guard 无需来源分支。转换失败（武器/投送类型非法）返回 null 并跳过。
     */
    private static RVP_FireSupportProfile buildProfile(String weaponPath, RVP_PodFireSupportEntryData entry) {
        // 条目 ID 会进入 request.munitionId（Validator 只接受小写字母数字_.-），非法即整条跳过。
        if (!entry.getId().matches("[a-z0-9_.-]+")) {
            return null;
        }
        ResourceLocation weaponId = entry.getWeaponId();
        JsonObject delivery = entry.getDelivery();
        // 校验投送工厂类型存在（delivery.data 原样透传，投送工厂在任务接受时严格解析）。
        ResourceLocation deliveryType;
        try {
            deliveryType = ResourceLocation.parse(delivery.get("type").getAsString().trim());
        } catch (Exception exception) {
            return null;
        }
        if (org.ywzj.rvp.firesupport.delivery.RVP_FireSupportDeliveryTypes.get(deliveryType) == null) {
            return null;
        }
        // V1 限制：空射投送需要 air_strike 打击模式与航线进场语义，与吊舱单点指示不匹配，
        // 转换期直接跳过（Validator 的 air 判定也会拒绝该组合，此处提前拦截并留档）。
        if (org.ywzj.rvp.firesupport.delivery.RVP_FireSupportDeliveryTypes.AIR_LAUNCHED_PROJECTILE.equals(deliveryType)) {
            return null;
        }
        String key = "rvp.pod_fs." + weaponPath + "." + entry.getId();
        RVP_FireSupportProfile.MunitionWeapon munitionWeapon = new RVP_FireSupportProfile.MunitionWeapon(
                weaponId, 1, deliveryType, delivery);
        RVP_FireSupportProfile.Munition munition = new RVP_FireSupportProfile.Munition(
                munitionIdFor(entry.getId()), key, entry.getRoundsPerCall(), false, List.of(munitionWeapon));
        // 单"齐射"阶段：固定 rounds_per_call 发、固定间隔；基数无关（RoundRule.fixed 优先）。
        RVP_FireSupportProfile.FireMode fireMode = new RVP_FireSupportProfile.FireMode(
                FIXED_FIRE_MODE_ID, key, 1.0, 1.0,
                List.of(new RVP_FireSupportProfile.Phase("volley", key, false, 0,
                        new RVP_FireSupportProfile.RoundRule(null, null, null, entry.getRoundsPerCall()),
                        entry.getIntervalTicks(), null)));
        // point 几何：radius_m 参数规格 + 代码直构实现；默认值=条目散布半径，玩家请求参数可覆盖。
        RVP_FireSupportProfile.PatternPreset pattern = new RVP_FireSupportProfile.PatternPreset(
                FIXED_PATTERN_ID, key, RVP_FireSupportPatternTypes.POINT,
                Map.of("radius_m", new RVP_FireSupportProfile.ParameterSpec(
                        entry.getScatterRadiusM(), 0.0, 256.0, 1.0, "m")),
                RVP_FireSupportPatternTypes.createBuiltInPoint());
        // 上限：并行任务沿用终端默认档（2/16）；任务时长上限给足引导+全部弹间隔的余量。
        int missionDuration = entry.getCallDurationTicks()
                + entry.getRoundsPerCall() * entry.getIntervalTicks() + 1200;
        RVP_FireSupportProfile.Limits limits = new RVP_FireSupportProfile.Limits(
                0.0, entry.getMaxTargetDistanceM(), Math.max(entry.getMaxRounds(), entry.getRoundsPerCall()),
                2, 16, entry.getCooldownTicks(), missionDuration, 16, 8);
        return new RVP_FireSupportProfile(
                3, key,
                new RVP_FireSupportProfile.Item(key),
                new RVP_FireSupportProfile.HolderPolicy(java.util.Set.of("main")),
                new RVP_FireSupportProfile.CallStage(entry.getCallDurationTicks(), true, false, true, false),
                new RVP_FireSupportProfile.StrikeStage(80),
                limits,
                Map.of(munition.id(), munition),
                Map.of(fireMode.id(), fireMode),
                Map.of(pattern.id(), pattern));
    }

    /** @return POD 任务的确定性终端占位 UUID（仅记录用途）。 */
    public static UUID sentinelTerminalId() {
        return POD_SENTINEL_TERMINAL;
    }
}
