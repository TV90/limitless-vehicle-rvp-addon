package org.ywzj.rvp.client.lead;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.radar.RVP_ExternalRadarLinkHelper;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.weapon.AbstractVehicleWeapon;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.WeakHashMap;

public final class RVP_MachinegunLeadSolver {
    /** 单次提前量解算允许搜索的最长飞行时间，单位为 tick。 */
    private static final double MAX_SOLVE_TICKS = 120.0;

    /** 候选命中时间的扫描步长，单位为 tick。 */
    private static final double SOLVE_STEP_TICKS = 0.5;

    /** 每个候选命中时间最多执行的炮口方向误差修正次数。 */
    private static final int AIM_REFINE_ITERATIONS = 6;

    /** 小于该速度的目标按静止处理，避免载具物理微抖产生伪提前量，单位为格/tick。 */
    static final double TARGET_VELOCITY_DEADBAND = 0.01D;

    /** 目标速度死区的平方，避免每次判定执行开方。 */
    private static final double TARGET_VELOCITY_DEADBAND_SQR =
            TARGET_VELOCITY_DEADBAND * TARGET_VELOCITY_DEADBAND;

    /** 目标实位移速度估算保留的最大位置样本数。 */
    private static final int TARGET_HISTORY_SAMPLES = 5;

    /** 超过该间隔未更新的目标历史会被重置，单位为 tick。 */
    private static final int TARGET_HISTORY_STALE_TICKS = 3;

    /** 空中目标的实体运动速度混合权重，用于补充网络位置差分尚未体现的速度变化。 */
    private static final double AIR_MOTION_WEIGHT = 0.25D;

    /** 着地目标允许外推的最大垂直速度，单位为格/tick。 */
    private static final double GROUNDED_MAX_VERTICAL_SPEED = 0.25D;

    /**
     * 当前执行线程内各目标的短期中心位置历史；客户端与集成服务端线程隔离，弱键保证实体卸载后可回收。
     */
    private static final ThreadLocal<Map<Entity, TargetMotionHistory>> TARGET_MOTION_HISTORIES =
            ThreadLocal.withInitial(WeakHashMap::new);

    /** 单次弹道积分结果：包含预测位置与累计飞行距离。 */
    private record BulletSimResult(Vec3 position, double travelledDistance) {}

    private RVP_MachinegunLeadSolver() {}

    public static boolean isCurrentRvpMachinegun(WeaponUnit weaponUnit) {
        return resolveCurrentWeaponData(weaponUnit) != null;
    }

    /**
     * 以实体自身的双端可用位置与速度解算提前量。
     *
     * <p>AHEAD 引信会在服务端调用本入口，因此这里不能依赖客户端广播载具缓存。</p>
     */
    @Nullable
    public static RVP_LeadSolution solveForTarget(WeaponUnit weaponUnit, RVP_WeaponData data, Vec3 muzzle,
                                                  Entity target, float partialTick) {
        if (weaponUnit == null || data == null || target == null || muzzle == null) {
            return null;
        }
        Vec3 targetPos = interpolateEntityCenter(target, partialTick);
        Vec3 targetVelocity = estimateEntityVelocity(target, targetPos);
        return solveForTargetMotion(weaponUnit, data, muzzle, target, targetPos, targetVelocity, 0.0D);
    }

    /**
     * 以调用方提供的可视目标中心、速度和样本缓冲时长解算提前量。
     *
     * <p>参数只包含双端安全的数据类型；客户端广播载具采样由
     * {@link RVP_ClientMachinegunLeadResolver} 负责，避免服务端 AHEAD 路径主动加载客户端类。</p>
     */
    @Nullable
    static RVP_LeadSolution solveForTargetMotion(WeaponUnit weaponUnit, RVP_WeaponData data, Vec3 muzzle,
                                                 Entity target, Vec3 targetPos, Vec3 targetVelocity,
                                                 double bufferDelayTicks) {
        if (weaponUnit == null || data == null || target == null || muzzle == null || targetPos == null) {
            return null;
        }
        Vec3 safeTargetVelocity = targetVelocity == null ? Vec3.ZERO : targetVelocity;
        // 广播插值为了连续显示会落后一个样本周期；弹道预测补回该段位移，
        // 避免目标轨迹平滑后机炮反而固定少打约五 Tick 的提前量。
        Vec3 predictionBasePos = compensateBufferedTargetPosition(
                targetPos,
                safeTargetVelocity,
                bufferDelayTicks
        );
        // 调用本体载具速度，按武器数据决定是否把发射载具速度继承到弹体初速度。
        Vec3 inheritedVelocity = data.isInheritVehicleVelocity()
                ? weaponUnit.getVehicle().getDeltaMovement()
                : Vec3.ZERO;
        double muzzleSpeed = Math.max(data.resolveMuzzleSpeed(RVP_EnumWeaponKind.MACHINEGUN), 0.01f);
        double gravity = Math.max(data.getCannonGravity(), 0f);
        double friction = Mth.clamp(data.getCannonFriction(), 0f, 0.4f);
        double maxTicks = Mth.clamp(data.getLife() > 0 ? data.getLife() : MAX_SOLVE_TICKS, 10.0, MAX_SOLVE_TICKS);

        return solve(
                target,
                muzzle,
                targetPos,
                predictionBasePos,
                safeTargetVelocity,
                inheritedVelocity,
                muzzleSpeed,
                gravity,
                friction,
                maxTicks
        );
    }

    /** 按原版实体的前后 tick 坐标插值碰撞箱中心，供双端普通实体解算使用。 */
    private static Vec3 interpolateEntityCenter(Entity entity, float partialTick) {
        double x = Mth.lerp(partialTick, entity.xo, entity.getX());
        double y = Mth.lerp(partialTick, entity.yo, entity.getY());
        double z = Mth.lerp(partialTick, entity.zo, entity.getZ());
        Vec3 centerOffset = entity.getBoundingBox().getCenter().subtract(entity.position());
        return new Vec3(x, y, z).add(centerOffset);
    }

    @Nullable
    private static RVP_LeadSolution solve(Entity target, Vec3 muzzle, Vec3 targetPos,
                                          Vec3 predictionBasePos, Vec3 targetVelocity,
                                          Vec3 inheritedVelocity, double muzzleSpeed, double gravity,
                                          double friction, double maxTicks) {
        RVP_LeadSolution best = null;

        for (double timeTicks = 1.0; timeTicks <= maxTicks; timeTicks += SOLVE_STEP_TICKS) {
            // 只按目标实测速率做匀速预测；不再叠加与目标朝向有关的固定距离或额外时间偏置。
            Vec3 futureTargetPos = predictTargetPosition(predictionBasePos, targetVelocity, timeTicks);
            Vec3 aimPoint = futureTargetPos;

            for (int i = 0; i < AIM_REFINE_ITERATIONS; i++) {
                Vec3 aimDir = aimPoint.subtract(muzzle);
                if (aimDir.lengthSqr() < 1.0E-6) {
                    break;
                }
                BulletSimResult bulletState = simulateBulletPosition(
                        muzzle,
                        aimDir.normalize(),
                        inheritedVelocity,
                        muzzleSpeed,
                        gravity,
                        friction,
                        timeTicks
                );
                Vec3 error = futureTargetPos.subtract(bulletState.position());
                aimPoint = aimPoint.add(error);
                if (error.lengthSqr() < 1.0E-3) {
                    break;
                }
            }

            Vec3 finalAimDir = aimPoint.subtract(muzzle);
            if (finalAimDir.lengthSqr() < 1.0E-6) {
                continue;
            }
            BulletSimResult finalBulletState = simulateBulletPosition(
                    muzzle,
                    finalAimDir.normalize(),
                    inheritedVelocity,
                    muzzleSpeed,
                    gravity,
                    friction,
                    timeTicks
            );
            double missDistance = finalBulletState.position().distanceTo(futureTargetPos);
            if (best == null || missDistance < best.missDistance()) {
                best = new RVP_LeadSolution(
                        target,
                        targetPos,
                        aimPoint,
                        timeTicks,
                        missDistance,
                        finalBulletState.travelledDistance()
                );
            }
        }
        return best;
    }

    private static BulletSimResult simulateBulletPosition(Vec3 muzzle, Vec3 aimDir, Vec3 inheritedVelocity,
                                                          double muzzleSpeed, double gravity, double friction,
                                                          double timeTicks) {
        Vec3 position = muzzle;
        Vec3 velocity = aimDir.scale(muzzleSpeed).add(inheritedVelocity);
        double travelledDistance = 0.0D;
        int fullTicks = Mth.floor(timeTicks);
        double partialTick = timeTicks - fullTicks;

        for (int tick = 0; tick < fullTicks; tick++) {
            position = position.add(velocity);
            travelledDistance += velocity.length();
            velocity = velocity.scale(1.0 - friction).add(0.0, -gravity, 0.0);
        }
        if (partialTick > 1.0E-6) {
            position = position.add(velocity.scale(partialTick));
            travelledDistance += velocity.length() * partialTick;
        }
        return new BulletSimResult(position, travelledDistance);
    }

    @Nullable
    public static RVP_WeaponData resolveCurrentWeaponData(WeaponUnit weaponUnit) {
        AbstractVehicleWeapon<?> weapon = resolveCurrentWeapon(weaponUnit);
        if (weapon == null || !(weapon.getData() instanceof RVP_WeaponData data)) {
            return null;
        }
        if (data.getWeaponKind() != RVP_EnumWeaponKind.MACHINEGUN) {
            return null;
        }
        return data;
    }

    /**
     * 取得当前由火控站选中的实际武器对象。
     *
     * <p>武器可能通过 {@code part_unit_id} 挂载到子武器站，不能把执行火控的根武器站当作发射单元。</p>
     */
    @Nullable
    private static AbstractVehicleWeapon<?> resolveCurrentWeapon(WeaponUnit weaponUnit) {
        if (weaponUnit == null) {
            return null;
        }
        // 调用本体武器站选择状态，解析当前真正参与发射的武器对象。
        return weaponUnit.getCurrentWeapon().orElse(null);
    }

    /** 取得当前武器实际挂载的武器站，供火控方向与弹道解算统一炮口原点。 */
    @Nullable
    public static WeaponUnit resolveCurrentLaunchWeaponUnit(WeaponUnit weaponUnit) {
        AbstractVehicleWeapon<?> weapon = resolveCurrentWeapon(weaponUnit);
        if (weapon == null) {
            return null;
        }
        // 调用本体武器对象读取实际挂载单元，避免根火控站与子炮塔炮口混用。
        return weapon.getWeaponUnit();
    }

    @Nullable
    public static Entity resolveTrackedTarget(WeaponUnit weaponUnit) {
        Entity target = weaponUnit.getLockedEntity();
        if (target != null && target.isAlive()) {
            return target;
        }
        var vehicle = org.ywzj.vehicle.vehicle.LocalVehiclePlayer.instance.vehicle;
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (vehicle != null && mc.level != null) {
            Entity externalLocked = RVP_ExternalRadarLinkHelper.getClientLockedEntity(vehicle, mc.level.dimension().location());
            if (externalLocked != null && externalLocked.isAlive()) {
                return externalLocked;
            }
        }
        RadarUnit radar = weaponUnit.getMainRadarUnit();
        if (radar == null) {
            return null;
        }
        Entity radarTarget = radar.getLockedEntity();
        return radarTarget != null && radarTarget.isAlive() ? radarTarget : null;
    }

    private static Vec3 estimateEntityVelocity(Entity entity, Vec3 targetCenter) {
        Vec3 tickDelta = new Vec3(
                entity.getX() - entity.xo,
                entity.getY() - entity.yo,
                entity.getZ() - entity.zo
        );
        Vec3 motion = entity.getDeltaMovement();
        int nowTick = entity.tickCount;
        TargetMotionHistory history = TARGET_MOTION_HISTORIES.get().computeIfAbsent(
                entity, unused -> new TargetMotionHistory());
        if (history.lastUpdateTick == nowTick) {
            return history.cachedVelocity;
        }
        if (history.lastUpdateTick == Integer.MIN_VALUE
                || nowTick <= history.lastUpdateTick
                || nowTick - history.lastUpdateTick > TARGET_HISTORY_STALE_TICKS) {
            history.samples.clear();
        }
        history.samples.addLast(new PositionSample(nowTick, targetCenter));
        while (history.samples.size() > TARGET_HISTORY_SAMPLES) {
            history.samples.removeFirst();
        }

        Vec3 realizedVelocity = estimateRealizedVelocity(history.samples);
        if (realizedVelocity == null) {
            realizedVelocity = tickDelta;
        }
        // 调用原版实体着地状态，着地目标只采用实位移的 Y 分量，排除悬挂、重力和接触修正污染。
        history.cachedVelocity = combineAndFilterTargetVelocity(realizedVelocity, motion, entity.onGround());
        history.lastUpdateTick = nowTick;
        return history.cachedVelocity;
    }

    /**
     * 混合实位移速度与实体运动速度，并对近零速度施加死区。
     * 该纯数学入口供自动化测试复核静止/低速目标行为。
     */
    static Vec3 combineAndFilterTargetVelocity(Vec3 realizedVelocity, Vec3 motion, boolean onGround) {
        Vec3 safeRealizedVelocity = realizedVelocity == null ? Vec3.ZERO : realizedVelocity;
        Vec3 safeMotion = motion == null ? Vec3.ZERO : motion;
        Vec3 blended;
        if (onGround) {
            double groundedY = Mth.clamp(
                    safeRealizedVelocity.y,
                    -GROUNDED_MAX_VERTICAL_SPEED,
                    GROUNDED_MAX_VERTICAL_SPEED
            );
            blended = new Vec3(safeRealizedVelocity.x, groundedY, safeRealizedVelocity.z);
        } else {
            blended = safeRealizedVelocity.lerp(safeMotion, AIR_MOTION_WEIGHT);
        }
        return blended.lengthSqr() < TARGET_VELOCITY_DEADBAND_SQR ? Vec3.ZERO : blended;
    }

    /** 根据位置历史首尾样本计算真实平均速度；样本不足时返回 {@code null}。 */
    @Nullable
    static Vec3 estimateRealizedVelocity(Deque<PositionSample> samples) {
        if (samples == null || samples.size() < 2) {
            return null;
        }
        PositionSample first = samples.getFirst();
        PositionSample last = samples.getLast();
        int elapsedTicks = last.tick - first.tick;
        if (elapsedTicks <= 0) {
            return null;
        }
        return last.position.subtract(first.position).scale(1.0D / elapsedTicks);
    }

    /** 按匀速模型计算候选时间的目标位置；目标朝向不参与提前量。 */
    static Vec3 predictTargetPosition(Vec3 targetCenter, Vec3 targetVelocity, double timeTicks) {
        Vec3 safeCenter = targetCenter == null ? Vec3.ZERO : targetCenter;
        Vec3 safeVelocity = targetVelocity == null ? Vec3.ZERO : targetVelocity;
        return safeCenter.add(safeVelocity.scale(Math.max(timeTicks, 0.0D)));
    }

    /**
     * 用目标速度补回广播样本缓冲引入的固定位置延迟。
     *
     * <p>只修正弹道预测起点；{@link RVP_LeadSolution#targetWorldPos()} 仍保留插值后的可视目标中心，
     * 保证 HUD 虚线端点与锁定框对齐。</p>
     */
    static Vec3 compensateBufferedTargetPosition(Vec3 targetCenter, Vec3 targetVelocity,
                                                 double bufferDelayTicks) {
        Vec3 safeCenter = targetCenter == null ? Vec3.ZERO : targetCenter;
        Vec3 safeVelocity = targetVelocity == null ? Vec3.ZERO : targetVelocity;
        return safeCenter.add(safeVelocity.scale(Math.max(bufferDelayTicks, 0.0D)));
    }

    /** 单个目标的短期位置与速度缓存。 */
    private static final class TargetMotionHistory {
        /** 按 tick 升序保存的目标中心位置样本。 */
        final Deque<PositionSample> samples = new ArrayDeque<>();

        /** 最近一次更新历史的实体 tick。 */
        int lastUpdateTick = Integer.MIN_VALUE;

        /** 当前 tick 已计算的目标速度，供同 tick 多个 HUD/火控调用复用。 */
        Vec3 cachedVelocity = Vec3.ZERO;
    }

    /** 目标中心位置的带 tick 样本。 */
    static final class PositionSample {
        /** 采样时的实体 tick。 */
        final int tick;

        /** 采样时的目标碰撞箱中心世界坐标。 */
        final Vec3 position;

        PositionSample(int tick, Vec3 position) {
            this.tick = tick;
            this.position = position;
        }
    }
}
