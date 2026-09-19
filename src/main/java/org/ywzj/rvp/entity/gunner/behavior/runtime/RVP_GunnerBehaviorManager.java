package org.ywzj.rvp.entity.gunner.behavior.runtime;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.rvp.entity.gunner.ai.GunnerBrain;
import org.ywzj.rvp.entity.gunner.ai.RVP_GunnerDebugMonitor;
import org.ywzj.rvp.entity.gunner.ai.profile.GunnerProfile;
import org.ywzj.rvp.entity.gunner.ai.profile.GunnerProfileManager;
import org.ywzj.rvp.entity.gunner.behavior.action.RVP_GunnerActionGateway;
import org.ywzj.rvp.entity.gunner.behavior.action.RVP_GunnerActionResult;
import org.ywzj.rvp.entity.gunner.behavior.action.RVP_GunnerMovementActions;
import org.ywzj.rvp.entity.gunner.behavior.api.RVP_GunnerBehaviorContext;
import org.ywzj.rvp.entity.gunner.behavior.api.RVP_GunnerBehaviorIntent;
import org.ywzj.rvp.entity.gunner.behavior.api.RVP_GunnerBehaviorRuntime;
import org.ywzj.rvp.entity.gunner.behavior.api.RVP_GunnerIntentSink;
import org.ywzj.rvp.entity.gunner.behavior.debug.RVP_GunnerBehaviorDebugSnapshot;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gunner 阶段 C 固定计划管理器。
 *
 * <p>本阶段仍复用 {@link GunnerBrain} 的既有战术算法，但算法只能提交意图；本管理器按固定阶段
 * 仲裁并通过阶段 B 动作层执行。Profile 仍使用平铺 schema，不在此处提前引入行为列表。</p>
 */
public final class RVP_GunnerBehaviorManager {

    /** 全局固定计划管理器。 */
    public static final RVP_GunnerBehaviorManager INSTANCE = new RVP_GunnerBehaviorManager(
            new RVP_GunnerActionIntentExecutor(RVP_GunnerActionGateway.INSTANCE));

    /** 确定性意图仲裁器。 */
    private final RVP_GunnerIntentArbiter arbiter = new RVP_GunnerIntentArbiter();
    /** 已仲裁意图执行器。 */
    private final RVP_IGunnerIntentExecutor executor;

    RVP_GunnerBehaviorManager(RVP_IGunnerIntentExecutor executor) {
        this.executor = executor;
    }

    /** 运行 KERNEL_PREPARE 到 KERNEL_CLEANUP 的阶段 C 固定计划。 */
    public void tick(GunnerEntity gunner, AbstractVehicle vehicle) {
        GunnerProfileManager profiles = GunnerProfileManager.INSTANCE;
        String profileId = profiles.normalizeProfileId(gunner.getProfileId()).toString();
        GunnerProfile profile = profiles.getProfile(profiles.normalizeProfileId(profileId));
        RVP_GunnerBehaviorContext context = RVP_GunnerBehaviorContext.create(
                gunner, vehicle, profile, profileId, profiles.getGeneration());
        RVP_GunnerBehaviorRuntime runtime = gunner.getBehaviorRuntime();
        if (runtime.requiresExit(context)) {
            // Profile/资源代次/载具变化时先释放旧计划的锁、制导与补给租约。
            cleanup(gunner, runtime.previousVehicle(), runtime.previousWeaponUnit());
            runtime.clear();
        }
        runtime.bind(context);

        // KERNEL_PREPARE：统一推进公共冷却，行为不得各自重复推进。
        gunner.tickCooldowns();
        TickSession session = new TickSession(context, arbiter, executor);

        // TARGET：固定计划先提交并确定本 tick 唯一权威目标。
        GunnerBrain.planTarget(context, session);
        session.execute(EnumSet.of(RVP_GunnerBehaviorIntent.Channel.TARGET));
        context = context.withTarget(gunner.getTrackedTarget());
        session.setContext(context);

        // EXECUTE_SUPPORT：补给、反制、雷达与在途制导先执行，使 Smoke 等结果可影响同 tick 移动。
        GunnerBrain.planSupport(context, session);
        session.execute(EnumSet.of(
                RVP_GunnerBehaviorIntent.Channel.SUPPLY,
                RVP_GunnerBehaviorIntent.Channel.COUNTERMEASURE,
                RVP_GunnerBehaviorIntent.Channel.ECM,
                RVP_GunnerBehaviorIntent.Channel.RADAR_LOCK,
                RVP_GunnerBehaviorIntent.Channel.GUIDANCE_MAINTAIN));

        // PLAN/EXECUTE_MOVEMENT/EXECUTE_COMBAT：SEAD 与常规战术共用唯一移动和开火通道。
        GunnerBrain.planTactics(context, session);
        session.ensureDriverStopFallback();
        session.execute(EnumSet.of(
                RVP_GunnerBehaviorIntent.Channel.COUNTERMEASURE,
                RVP_GunnerBehaviorIntent.Channel.MOVEMENT,
                RVP_GunnerBehaviorIntent.Channel.FIRE,
                RVP_GunnerBehaviorIntent.Channel.SYNC));

        // KERNEL_CLEANUP：保存候选、胜者、拒因和动作结果，供诊断命令/监控读取。
        runtime.setDebugSnapshot(session.snapshot());
        if (FMLEnvironment.dist == Dist.CLIENT) {
            // 调用现有客户端诊断监控，保持阶段 A 的观察输出；服务器不会加载客户端专属类型。
            RVP_GunnerDebugMonitor.onTick(gunner, vehicle, context.weaponUnit(), context.target());
        }
    }

    /** Gunner 离座、死亡或实体移除时释放固定计划状态。 */
    public void exit(GunnerEntity gunner) {
        RVP_GunnerBehaviorRuntime runtime = gunner.getBehaviorRuntime();
        cleanup(gunner, runtime.previousVehicle(), runtime.previousWeaponUnit());
        runtime.clear();
    }

    /** 通过动作层清理不再续租的雷达、制导、补给与同步状态。 */
    private void cleanup(GunnerEntity gunner, AbstractVehicle vehicle, WeaponUnit weaponUnit) {
        RVP_GunnerActionGateway actions = RVP_GunnerActionGateway.INSTANCE;
        if (vehicle != null) {
            // 调用补给动作适配器，清除离座或换 Profile 后的装填覆盖计时。
            actions.supply().clearDriverAmmoTimers(vehicle);
            // 调用雷达动作适配器，以空目标清除本车锁。
            actions.radar().maintainLocalLock(vehicle, weaponUnit, null);
            // 调用外置雷达动作适配器，撤销不再续租的中继状态。
            actions.radar().clearExternalLock(vehicle, weaponUnit);
        }
        // 调用制导动作适配器，按当前 Gunner 所有者键释放 GPS 与照射会话。
        actions.guidance().clear(gunner);
        gunner.setControlledWeaponIndex(-1);
        gunner.setTrackedTarget(null);
        gunner.clearDriverRideState();
    }

    /** 单 tick 候选收集、分阶段仲裁和调试记录。 */
    private static final class TickSession implements RVP_GunnerIntentSink {
        /** 当前阶段上下文。 */
        private RVP_GunnerBehaviorContext context;
        /** 仲裁器。 */
        private final RVP_GunnerIntentArbiter arbiter;
        /** 动作执行器。 */
        private final RVP_IGunnerIntentExecutor executor;
        /** 尚未执行的候选。 */
        private final List<RVP_GunnerBehaviorIntent> pending = new ArrayList<>();
        /** 本 tick 所有候选描述。 */
        private final List<String> candidates = new ArrayList<>();
        /** 本 tick 各资源胜者。 */
        private final Map<String, String> winners = new LinkedHashMap<>();
        /** 本 tick 仲裁拒绝记录。 */
        private final List<String> rejections = new ArrayList<>();
        /** 本 tick 动作结果。 */
        private final Map<String, String> results = new LinkedHashMap<>();

        private TickSession(RVP_GunnerBehaviorContext context, RVP_GunnerIntentArbiter arbiter,
                            RVP_IGunnerIntentExecutor executor) {
            this.context = context;
            this.arbiter = arbiter;
            this.executor = executor;
        }

        private void setContext(RVP_GunnerBehaviorContext context) {
            this.context = context;
        }

        @Override
        public void submit(RVP_GunnerBehaviorIntent intent) {
            if (intent == null) return;
            pending.add(intent);
            candidates.add(intent.debugName());
        }

        /** 对指定阶段通道仲裁并执行胜者。 */
        private void execute(EnumSet<RVP_GunnerBehaviorIntent.Channel> channels) {
            List<RVP_GunnerBehaviorIntent> phase = pending.stream()
                    .filter(intent -> channels.contains(intent.channel())).toList();
            pending.removeIf(intent -> channels.contains(intent.channel()));
            RVP_GunnerIntentArbiter.Resolution resolution = arbiter.resolve(phase);
            rejections.addAll(resolution.rejections());
            for (RVP_GunnerBehaviorIntent rejected : resolution.rejectedIntents()) {
                rejected.notifyResult(RVP_GunnerActionResult.OCCUPIED);
            }
            List<Map.Entry<String, RVP_GunnerBehaviorIntent>> orderedWinners = new ArrayList<>(
                    resolution.winners().entrySet());
            orderedWinners.sort(java.util.Comparator
                    .comparingInt((Map.Entry<String, RVP_GunnerBehaviorIntent> entry) -> entry.getValue().channel().ordinal())
                    .thenComparingInt(entry -> entry.getValue().planOrder())
                    .thenComparing(entry -> entry.getValue().behaviorId()));
            for (Map.Entry<String, RVP_GunnerBehaviorIntent> entry : orderedWinners) {
                RVP_GunnerBehaviorIntent intent = entry.getValue();
                winners.put(entry.getKey(), intent.debugName());
                // 调用注入的执行器；生产实现再进入阶段 B 动作网关，测试实现只记录调用。
                RVP_GunnerActionResult result = executor.execute(context, intent);
                results.put(entry.getKey(), result.name());
                intent.notifyResult(result);
            }
        }

        /** 司机本 tick 没有任何移动候选时提交显式停车兜底，避免沿用旧控制输入。 */
        private void ensureDriverStopFallback() {
            boolean hasMovement = pending.stream()
                    .anyMatch(intent -> intent.channel() == RVP_GunnerBehaviorIntent.Channel.MOVEMENT);
            if (!hasMovement && context.has(RVP_GunnerBehaviorContext.Capability.DRIVER)) {
                submit(RVP_GunnerBehaviorIntent.of("kernel_stop", "vehicle", Integer.MAX_VALUE, 0,
                        RVP_GunnerBehaviorIntent.Channel.MOVEMENT,
                        RVP_GunnerBehaviorIntent.Kind.MOVEMENT, null,
                        new RVP_GunnerMovementActions.Command(), null, false, "", null));
            }
        }

        private RVP_GunnerBehaviorDebugSnapshot snapshot() {
            return new RVP_GunnerBehaviorDebugSnapshot(context.gameTime(), context.profileId(),
                    List.copyOf(candidates), stableMap(winners), List.copyOf(rejections), stableMap(results));
        }

        /** 保留固定计划执行顺序，避免调试输出重新依赖 Map 实现顺序。 */
        private static Map<String, String> stableMap(Map<String, String> source) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(source));
        }
    }
}
