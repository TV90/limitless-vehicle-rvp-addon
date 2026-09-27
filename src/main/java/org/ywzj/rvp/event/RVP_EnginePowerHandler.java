package org.ywzj.rvp.event;

import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.network.RVP_Network;
import org.ywzj.rvp.network.S2CEngineDamageState;
import org.ywzj.rvp.vehicle.BoneEngineConfig;
import org.ywzj.rvp.vehicle.BoneModuleType;
import org.ywzj.rvp.vehicle.RVP_BoneModuleStateTable;
import org.ywzj.rvp.vehicle.RVP_EngineDamageTable;
import org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager;
import org.ywzj.vehicle.api.event.VehicleMoveEvent;
import org.ywzj.vehicle.custom.CommonAssetsManager;
import org.ywzj.vehicle.custom.vehicle.BaseVehicleData;
import org.ywzj.vehicle.custom.vehicle.TrackedVehicleData;
import org.ywzj.vehicle.custom.vehicle.VesselVehicleData;
import org.ywzj.vehicle.custom.vehicle.WheeledVehicleData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.TrackedVehicle;
import org.ywzj.vehicle.entity.vehicle.VesselVehicle;
import org.ywzj.vehicle.entity.vehicle.WheeledVehicle;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [RVP] 引擎骨骼部件（ENGINE bone module）动力消费端（服务端，零 Mixin，2026-09-26 新增；
 * 2026-09-27 重构：基准重锚）。
 *
 * <p>机制：本体 {@link VehicleMoveEvent} 每载具每 tick（服务端 {@code AbstractVehicle.tick}
 * 内 {@code tickPhysics(tickMove())} 后发布）→ 衰减窗口累计（{@link RVP_EngineDamageTable}）
 * → 按骨计算档位（0 正常 / 1 受损 / 2 瘫痪）→ 把动力字段覆写为
 * <b>本体 JSON 配置值 × 倍率</b>。配置值每 tick 从 {@code CommonAssetsManager.vehicleDataManager()}
 * 直读（与本体 {@code initData → VehicleData.inject} 写入的是同一份权威数据）——
 * 本体在任何时机（上下车/跨区块重载/display 变更）重注入字段，下一 tick 都会被纠正回
 * {@code 配置值 × 当前倍率}，不存在"覆写丢失后瘫痪车复活"的窗口（首版一次性内存捕获
 * 在 inject 时序下会丢失锚点，实机已复现，已废弃）。</p>
 *
 * <p><b>方向机/高低机不受影响（用户 2026-09-26 定版硬约束）</b>：全程不触碰
 * {@code ENGINE_ON}、不把 {@code POWER} 压向 20——本体炮塔/武器/雷达的
 * {@code hasPower()} 门（POWER&gt;20）保持原样，瘫痪档只是驱动字段清零。</p>
 *
 * <p>档位语义：任一引擎骨 ENGINE 模块失效 → 瘫痪（全字段 ×0.0001 趴窝）；否则任一引擎骨
 * 累计伤害 ≥ 受损阈值 → 受损（极速/转向上限 ×{@code power_multiplier_damaged} 默认 0.5；
 * 动力/加速度 ×0.75——×0.5 连地面摩擦都克服不了起不了步，2026-09-28 用户定版）；否则正常
 * （×1，即配置值原样覆写一遍——与 inject 等价，幂等无害）。两档均永久无衰减，唯一恢复 = 快修。</p>
 *
 * <p>NaN 防火墙（2026-09-27 实机事故）：载具位置非有限、或配置值非有限时跳过本 tick
 * 覆写——绝不把 NaN 带进物理字段（NaN 经 setDeltaMovement 污染位置/旋转并存档固化）。</p>
 */
@Mod.EventBusSubscriber(modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_EnginePowerHandler {

    /** 每载具运行态：仅档位差分记忆（动力基准已改为配置直读，无捕获态）。 */
    private static final class EngineRuntime {
        /** 上次推送客户端的档位表（差分用，null=尚未推送）。 */
        Map<String, Integer> lastSentStages;
    }

    private static final Map<AbstractVehicle, EngineRuntime> STATES = new ConcurrentHashMap<>();

    private RVP_EnginePowerHandler() {
    }

    // ─────────────────────────────────────────────────────────────
    // 每 tick 驱动（服务端）
    // ─────────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onVehicleMove(VehicleMoveEvent event) {
        AbstractVehicle vehicle = event.getVehicle();
        if (vehicle == null || vehicle.level().isClientSide() || vehicle.isRemoved()) {
            return;
        }
        var engineBones = RVP_VehicleHitboxFactorManager.INSTANCE.resolveEngineModules(vehicle);
        if (engineBones == null || engineBones.isEmpty()) {
            // 未配置引擎部件：本实现从未覆写过其字段（覆写只在配置存在时发生），直接返回
            cleanupStageCache(vehicle);
            return;
        }
        // [RVP] NaN 防火墙：载具位置已非有限时跳过一切覆写与档位推进——
        // 本类只写驱动力字段，污染源在外部，此处确保不火上浇油
        if (!isFinitePos(vehicle)) {
            cleanupStageCache(vehicle);
            return;
        }
        // 1) [RVP] 无衰减定版（2026-09-27 用户定版）：累计只增不减，重创/瘫痪均为永久状态
        //    直到快修——不再有任何时间衰减/窗期清逻辑
        // 2) 逐骨档位：失效=瘫痪(2)；累计≥受损阈值=受损(1)（无衰减，跨过即永久）；否则正常(0)
        Map<String, Integer> stages = new HashMap<>(engineBones.size());
        int worstStage = 0;
        float damagedMultiplier = 0f;
        boolean hasDamaged = false;
        for (Map.Entry<String, BoneEngineConfig> entry : engineBones.entrySet()) {
            String bone = entry.getKey();
            BoneEngineConfig config = entry.getValue();
            int stage;
            if (!RVP_BoneModuleStateTable.isModuleActive(vehicle.getUUID(), bone, BoneModuleType.ENGINE)) {
                stage = 2;
            } else if (!RVP_BoneModuleStateTable.isModuleActive(vehicle.getUUID(), bone, BoneModuleType.ENGINE_DAMAGED)
                    || RVP_EngineDamageTable.getAccumulated(vehicle.getUUID(), bone) >= config.thresholdLight()) {
                // [RVP] 重创档双通道判定（2026-09-28 定版）：ENGINE_DAMAGED 进失效表（持久化、
                // 可维修指定优先级）或累计≥受损阈——重启后累计虽清零，失效表标记保持 → 重创永久
                stage = 1;
                // [RVP] 取全部受损骨中最大的倍率（最宽容口径）
                if (!hasDamaged || config.powerMultiplierDamaged() > damagedMultiplier) {
                    damagedMultiplier = config.powerMultiplierDamaged();
                    hasDamaged = true;
                }
            } else {
                stage = 0;
            }
            stages.put(bone, stage);
            worstStage = Math.max(worstStage, stage);
        }
        // [RVP] 倍率合法性钳制：任何 NaN/负数/＞1 的配置值一律钳回 0.5——动力覆写只允许
        // 写入 (0,1] 的有限倍率
        if (hasDamaged && !isSafeMultiplier(damagedMultiplier)) {
            damagedMultiplier = 0.5f;
        }
        // [RVP] 瘫痪档倍率 = 0.0001 而非 0（用户 2026-09-27 定版）：0 会把 maxSpeed 等
        // 字段清零，本体公式里存在以其为除数的表达式（TrackedVehicle.tickMove:165 的
        // |vf|/maxSpeedForward），0/0=NaN 曾两次实机打穿整车坐标；0.0001 保持全部字段
        // 非零——任何除法结果有限，运动量约 0.005 KPH，体感等同静止
        float multiplier = switch (worstStage) {
            case 2 -> DISABLED_MULTIPLIER;
            case 1 -> hasDamaged ? damagedMultiplier : 0.5f;
            default -> 1f;
        };
        if (!isSafeMultiplier(multiplier)) {
            // 理论不可达（上一步已钳制）：倍率非法时本 tick 跳过覆写
            return;
        }
        // [RVP] 动力路独立倍率（2026-09-28 用户定版）：重创档动力（力/加速度/转向速率）
        // ×0.75——×0.5 实测连地面摩擦都克服不了、车辆起不了步（履带 vf 靠加速度积分对抗
        // PhysicsEngine 摩擦减速）；"功率减半"的体感由极速/转向上限 ×0.5 表达（50→25 KPH）。
        // JSON power_multiplier_damaged 只作用于极速路；动力路恒 ≥0.75（JSON 调高受损倍率时跟随）。
        // 瘫痪档 1e-4 全字段（含动力）——本来就趴窝，非零防除零 NaN。
        float speedMult = multiplier;
        float accelMult = worstStage == 2 ? multiplier : Math.max(multiplier, 0.75f);
        // 3) 动力字段覆写：极速/转向上限 × speedMult，力/加速度 × accelMult
        // （配置每 tick 直读，inject 重置下一 tick 即被纠正）
        applyPower(vehicle, speedMult, accelMult);
        if (worstStage == 2) {
            // [RVP] 瘫痪档静音（2026-09-28 用户定版）：ENGINE_SPEED 是发动机运转轰鸣音的
            // 同步门（>60 播 run 音），本 tick 本体 tickEngineSpeed（油门 +1/+2）先行写入、
            // VehicleMoveEvent 在其后发布——此处覆写 0 为每 tick 最后写入者，轰鸣音恒不触发
            //（≤60 只保留怠速音；不碰 POWER/ENGINE_ON，炮塔与武器门不受影响）
            vehicle.setEngineSpeed(0f);
        }
        // [RVP] 引擎诊断（/rvpdebug engine on）：档位变化时记录覆写后的实际字段值，
        // 供"重创/瘫痪动力不生效"类问题的实机对账（同时记录 POWER/能量排除供能干扰）
        Map<String, Integer> lastLogged = LAST_LOGGED_FIELDS.get(vehicle);
        if (org.ywzj.rvp.debug.RVP_EngineDebug.isEnabled() && !stages.equals(lastLogged)) {
            LAST_LOGGED_FIELDS.put(vehicle, new HashMap<>(stages));
            StringBuilder sb = new StringBuilder("[RVP-Engine-Power] ").append(vehicle.getVehicleId())
                    .append('(').append(vehicle.getId()).append(") 档位=").append(stages)
                    .append(" 极速倍率=").append(String.format("%.4f", speedMult))
                    .append(" 动力倍率=").append(String.format("%.4f", accelMult))
                    .append(" POWER=").append(String.format("%.0f", vehicle.getPower()))
                    .append(" 能量=").append(String.format("%.0f", vehicle.getEnergy()));
            if (vehicle instanceof org.ywzj.vehicle.entity.vehicle.TrackedVehicle tv) {
                sb.append(" | 履带字段: 加速度=").append(String.format("%.4f", tv.forwardAcceleration))
                        .append(" 极速=").append(String.format("%.4f", tv.maxSpeedForward))
                        .append(" 倒车极速=").append(String.format("%.4f", tv.maxSpeedBackward))
                        .append(" 转向加速度=").append(String.format("%.3f", tv.turnAcceleration))
                        .append(" 转向上限=").append(String.format("%.3f", tv.maxTurn));
            } else if (vehicle instanceof org.ywzj.vehicle.entity.vehicle.WheeledVehicle wv) {
                sb.append(" | 轮式字段: 推力=").append(String.format("%.0f", wv.forwardForce))
                        .append(" 极速=").append(String.format("%.4f", wv.maxSpeedForward))
                        .append(" 转向步进=").append(String.format("%.4f", wv.turnStep));
            }
            org.ywzj.rvp.debug.RVP_EngineDebug.log(sb.toString());
        }
        // 4) 档位差分推送（变化才发包；含受损档与瘫痪恢复后的回落）
        syncStages(vehicle, stages);
    }

    /** 诊断节流：各载具上次已记录档位（与差分推送分开，避免双份状态）。 */
    private static final java.util.concurrent.ConcurrentHashMap<AbstractVehicle, Map<String, Integer>>
            LAST_LOGGED_FIELDS = new java.util.concurrent.ConcurrentHashMap<>();

    /** 瘫痪档倍率：0.0001（非零防除零 NaN，运动量约 0.005 KPH 等同静止）。 */
    public static final float DISABLED_MULTIPLIER = 1.0E-4f;

    /** 载具离开世界：档位差分记忆清理（累计表清理见同事件分支）。 */
    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof AbstractVehicle vehicle) {
            cleanupStageCache(vehicle);
            RVP_EngineDamageTable.onVehicleLeave(vehicle.getUUID());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 内部
    // ─────────────────────────────────────────────────────────────

    private static boolean isFinitePos(AbstractVehicle vehicle) {
        return Float.isFinite((float) vehicle.getX()) && Float.isFinite((float) vehicle.getY())
                && Float.isFinite((float) vehicle.getZ());
    }

    private static boolean isSafeMultiplier(float m) {
        return Float.isFinite(m) && m > 0f && m <= 1f;
    }

    /**
     * 按载具类型把动力字段覆写为 本体 JSON 配置值 × 倍率。配置经
     * {@code CommonAssetsManager.vehicleDataManager().getVehicleData} 直读（RVP 战术地图
     * 已有同款调用先例），与本本体 {@code VehicleData.inject} 同源——inject 重置字段后
     * 本方法下一 tick 即纠正，覆写永不丢失。配置缺失（异常载具）时跳过本 tick。
     *
     * <p>倍率分两路（2026-09-27 实机定版）：{@code speedMult} 作用于极速/转向上限，
     * {@code accelMult} 作用于加速度/推力/转向速率——履带车驱动是"加速度积分对抗地面摩擦"，
     * 加速度砍半后净加速可能低于摩擦导致完全无法起步，故重创档只削极速、保留原起步能力。</p>
     */
    private static void applyPower(AbstractVehicle vehicle, float speedMult, float accelMult) {
        Optional<BaseVehicleData> dataOpt =
                CommonAssetsManager.vehicleDataManager().getVehicleData(vehicle.getVehicleId());
        if (dataOpt.isEmpty()) {
            return; // 配置缺失（display/数据异常载具）：跳过，不猜基准
        }
        BaseVehicleData<?> data = dataOpt.get();
        // [RVP] NaN 防火墙第二道：配置值本身非有限时跳过（数据损坏时不写入物理链）
        if (vehicle instanceof TrackedVehicle tracked && data instanceof TrackedVehicleData trackedData) {
            if (!isFiniteData(data)) {
                return;
            }
            tracked.brakeAcceleration = trackedData.brakeAcceleration * accelMult;
            tracked.forwardAcceleration = trackedData.forwardAcceleration * accelMult;
            tracked.backwardAcceleration = trackedData.backwardAcceleration * accelMult;
            tracked.maxSpeedForward = trackedData.maxSpeedForward * speedMult;
            tracked.maxSpeedBackward = trackedData.maxSpeedBackward * speedMult;
            tracked.turnAcceleration = trackedData.turnAcceleration * accelMult;
            tracked.maxTurn = trackedData.maxTurn * speedMult;
        } else if (vehicle instanceof WheeledVehicle wheeled && data instanceof WheeledVehicleData wheeledData) {
            if (!isFiniteData(data)) {
                return;
            }
            wheeled.brakeForce = wheeledData.brakeForce * accelMult;
            wheeled.forwardForce = wheeledData.forwardForce * accelMult;
            wheeled.backwardForce = wheeledData.backwardForce * accelMult;
            wheeled.maxSpeedForward = wheeledData.maxSpeedForward * speedMult;
            wheeled.maxSpeedBackward = wheeledData.maxSpeedBackward * speedMult;
            wheeled.turnStep = wheeledData.turnStep * accelMult;
            wheeled.maxTurn = wheeledData.maxTurn * speedMult;
        } else if (vehicle instanceof VesselVehicle vessel && data instanceof VesselVehicleData vesselData) {
            if (!isFiniteData(data)) {
                return;
            }
            vessel.brakeForce = vesselData.brakeForce * accelMult;
            vessel.forwardForce = vesselData.forwardForce * accelMult;
            vessel.backwardForce = vesselData.backwardForce * accelMult;
            vessel.maxSpeedForward = vesselData.maxSpeedForward * speedMult;
            vessel.maxSpeedBackward = vesselData.maxSpeedBackward * speedMult;
            vessel.turnStep = vesselData.turnStep * accelMult;
            vessel.maxTurn = vesselData.maxTurn * speedMult;
        }
        // 直升机/固定翼动力走 POWER 比例路径，不适用字段覆写；配置了 ENGINE 也不削
        // （其引擎损伤表达待后续方案，见实施文档"已知边界"）
    }

    /** 配置动力字段的有限性抽查（各型抽查代表字段，损坏数据不进物理链）。 */
    private static boolean isFiniteData(BaseVehicleData<?> data) {
        if (data instanceof TrackedVehicleData t) {
            return Float.isFinite(t.forwardAcceleration) && t.forwardAcceleration > 0f
                    && Float.isFinite(t.maxSpeedForward) && t.maxSpeedForward > 0f
                    && Float.isFinite(t.maxTurn) && t.maxTurn > 0f;
        }
        if (data instanceof WheeledVehicleData w) {
            return Float.isFinite(w.forwardForce) && w.forwardForce > 0f
                    && Float.isFinite(w.maxSpeedForward) && w.maxSpeedForward > 0f
                    && Float.isFinite(w.maxTurn) && w.maxTurn > 0f;
        }
        if (data instanceof VesselVehicleData v) {
            return Float.isFinite(v.forwardForce) && v.forwardForce > 0f
                    && Float.isFinite(v.maxSpeedForward) && v.maxSpeedForward > 0f
                    && Float.isFinite(v.maxTurn) && v.maxTurn > 0f;
        }
        return false;
    }

    /** 档位差分推送：与上次推送不一致（或从未推送）才发 {@link S2CEngineDamageState}。 */
    private static void syncStages(AbstractVehicle vehicle, Map<String, Integer> stages) {
        EngineRuntime runtime = STATES.computeIfAbsent(vehicle, key -> new EngineRuntime());
        if (stages.equals(runtime.lastSentStages)) {
            return;
        }
        runtime.lastSentStages = new HashMap<>(stages);
        RVP_Network.CHANNEL.send(
                PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> vehicle),
                S2CEngineDamageState.create(vehicle, stages));
    }

    /** 清理档位差分记忆（实体离开/NaN 跳过路径）。 */
    private static void cleanupStageCache(AbstractVehicle vehicle) {
        STATES.remove(vehicle);
        LAST_LOGGED_FIELDS.remove(vehicle);
    }
}
