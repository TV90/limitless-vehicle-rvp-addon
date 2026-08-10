package org.ywzj.rvp.countermeasure;

import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.guidance.RVP_EnumGuidanceType;
import org.ywzj.rvp.guidance.RVP_GuidanceRuntimeGeometry;
import org.ywzj.rvp.vehicle.BoneJammerConfig;
import org.ywzj.rvp.vehicle.BoneModuleType;
import org.ywzj.rvp.vehicle.RVP_BoneModuleStateTable;
import org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.part.PartUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 干扰机（Jamming Device）运行期服务：检测「前方 FOV + 距离」内的非自身 SACLOS 导弹。
 *
 * <p>检测在**被干扰弹体侧**进行（SACLOS 导弹数量极少，且仅在弹体处于 SACLOS 制导时
 * 由 {@code RVP_RuntimeSaclosGuidanceSource.evaluate} 每 tick 调用，开销可控）。
 * 命中后返回干扰强度，由制导源对瞄准点注入随机错误分量（见 {@code RVP_RuntimeSaclosGuidanceSource}）。</p>
 *
 * <p>服务端安全：全部逻辑在服务端（弹体制导评估 / 骨块模块侧表 / ServerPlayer 消息），
 * 无客户端依赖、无新增网络包、无新增 mixin。</p>
 */
public final class RVP_JammingRuntime {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** 临时调试开关：定位干扰未触发问题，定位后移除。 */
    private static final boolean JAM_DEBUG = true;

    /** 干扰扫描范围（格）。 */
    private static final double SCAN_RANGE = 4096.0;

    /** 干扰扫描间隔（tick）。 */
    private static final int SCAN_INTERVAL = 5;

    /** 干扰滞留宽限（tick）：导弹离开干扰锥/脱离范围后仍保持被干扰状态的时间。 */
    private static final int JAM_GRACE_TICKS = 10;
    /** 干扰者提示节流（tick）。 */
    private static final long JAM_NOTIFY_INTERVAL = 200L;

    /** 干扰机载具 id → 上次成功干扰提醒的 gameTime（防刷屏）。 */
    private static final Map<Integer, Long> LAST_JAM_NOTIFY_BY_VEHICLE = new ConcurrentHashMap<>();

    private RVP_JammingRuntime() {}

    /**
     * 每 tick 调用：刷新弹体干扰状态并返回当前干扰强度（0 = 未被干扰）。
     * 仅服务端 SACLOS 制导评估时调用；客户端恒返回 0。
     */
    public static double tickAndResolve(RVP_BaseBullet projectile) {
        if (projectile == null || projectile.level().isClientSide()) {
            return 0.0;
        }
        if (projectile.tickCount < projectile.jammingExpireTick) {
            return projectile.jammingStrength; // 命中缓存仍有效，直接沿用
        }
        if (projectile.tickCount < projectile.jammingNextScanTick) {
            return 0.0; // 上一轮未命中，等待下次扫描
        }
        projectile.jammingNextScanTick = projectile.tickCount + SCAN_INTERVAL;

        ActiveJammer hit = scan(projectile);
        if (JAM_DEBUG) {
            if (hit == null) {
                LOGGER.info("[JAM-DBG] missile={} tick={} scan: NO HIT", projectile.getId(), projectile.tickCount);
            } else {
                LOGGER.info("[JAM-DBG] missile={} tick={} scan: HIT vehicle={} strength={} side={}",
                        projectile.getId(), projectile.tickCount, hit.vehicle.getId(), hit.strength, hit.sideSign);
            }
        }
        if (hit == null) {
            // 干扰滞留：离开干扰锥后 JAM_GRACE_TICKS 内保持被干扰状态，避免边缘抖动瞬间解除。
            if (projectile.jammingStrength > 0.0 && projectile.tickCount < projectile.jammingGraceExpireTick) {
                return projectile.jammingStrength;
            }
            projectile.jammingSourceVehicleId = -1;
            projectile.jammingStrength = 0.0;
            projectile.jammingSideSign = 0.0;
            return 0.0;
        }
        boolean enteredJamming = projectile.jammingSourceVehicleId != hit.vehicle.getId();
        projectile.jammingSourceVehicleId = hit.vehicle.getId();
        projectile.jammingStrength = hit.strength;
        projectile.jammingSideSign = hit.sideSign;
        projectile.jammingHeadingRate = hit.headingRate;
        projectile.jammingOffsetAngleDeg = hit.offsetAngleDeg;
        projectile.jammingOffsetBaseBlocks = hit.offsetBaseBlocks;
        projectile.jammingOffsetDownBlocks = hit.offsetDownBlocks;
        projectile.jammingGraceExpireTick = projectile.tickCount + JAM_GRACE_TICKS;
        projectile.jammingExpireTick = projectile.tickCount + SCAN_INTERVAL;
        if (enteredJamming) {
            notifyVictim(projectile);
        }
        notifyJammer(hit.vehicle);
        return projectile.jammingStrength;
    }

    // ─────────────────────────────────────────────────────────────
    // 检测
    // ─────────────────────────────────────────────────────────────

    /**
     * 扫描弹体周围车辆，返回命中的干扰机设备（含强度与左右侧符号）。
     *
     * <p>不叠加：弹体已被某干扰机锁定（{@code jammingSourceVehicleId}）且该源仍有效时，
     * 只返回该源，其它干扰机即使同样命中也不触发；已锁源失效（脱离范围/被击毁）后
     * 才允许新的干扰机接管。</p>
     *
     * <p>左右侧符号：以弹体当前水平飞行方向为参考，判定干扰机在飞行线左侧（+1）还是右侧（-1）。
     * 制导源据此把导弹推向**远离干扰机**的一侧（干扰机在左→推右，在右→推左），不再随机。</p>
     */
    private static ActiveJammer scan(RVP_BaseBullet projectile) {
        if (!(projectile.level() instanceof ServerLevel)) {
            return null;
        }
        AABB box = projectile.getBoundingBox().inflate(SCAN_RANGE);
        Vec3 missilePos = projectile.position();
        int lockedId = projectile.jammingSourceVehicleId;
        if (lockedId != -1) {
            Entity locked = projectile.level().getEntity(lockedId);
            if (locked instanceof AbstractVehicle lockedVehicle) {
                ActiveJammer keep = checkVehicle(projectile, lockedVehicle, missilePos);
                if (keep != null) {
                    return keep; // 已锁干扰源仍有效：独占，不叠加其它干扰机
                }
            }
            // 已锁源失效（脱离范围 / 被击毁）：此处不清零，由 tickAndResolve 的
            // 干扰滞留宽限（JAM_GRACE_TICKS）统一处理，期间保持被干扰状态。
        }
        for (Entity entity : projectile.level().getEntities(projectile, box, e -> e instanceof AbstractVehicle)) {
            AbstractVehicle vehicle = (AbstractVehicle) entity;
            if (isSelfMissile(vehicle, projectile)) {
                continue;
            }
            ActiveJammer hit = checkVehicle(projectile, vehicle, missilePos);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /** 检查单台车辆的干扰机设备是否命中弹体；命中返回含左右侧符号的结果。 */
    private static ActiveJammer checkVehicle(RVP_BaseBullet projectile, AbstractVehicle vehicle, Vec3 missilePos) {
        Map<String, BoneJammerConfig> devices = RVP_VehicleHitboxFactorManager.INSTANCE.resolveJammerDevices(vehicle);
        if (devices == null || devices.isEmpty()) {
            return null;
        }
        Vec3 toMissile = missilePos.subtract(vehicle.position());
        double dist = toMissile.length();
        for (Map.Entry<String, BoneJammerConfig> entry : devices.entrySet()) {
            BoneJammerConfig cfg = entry.getValue();
            if (cfg == null || !cfg.type().interferesWith(RVP_EnumGuidanceType.SACLOS)) {
                continue;
            }
            if (!RVP_BoneModuleStateTable.isModuleActive(vehicle.getUUID(), entry.getKey(), BoneModuleType.JAMMER)) {
                if (JAM_DEBUG) {
                    LOGGER.info("[JAM-DBG] vehicle={} bone={} module JAMMER INACTIVE", vehicle.getId(), entry.getKey());
                }
                continue; // 干扰机骨块已被击毁，设备失效
            }
            if (dist > cfg.range()) {
                continue;
            }
            Vec3 front = resolveFacing(vehicle, cfg.facingPart(), cfg.facingYawDeg());
            if (front == null || front.lengthSqr() <= 1.0E-8) {
                continue;
            }
            if (!RVP_GuidanceRuntimeGeometry.withinAngle(front, toMissile, cfg.halfAngleDeg())) {
                if (JAM_DEBUG) {
                    LOGGER.info("[JAM-DBG] vehicle={} FOV MISS: front={} toMissile={} half={}",
                            vehicle.getId(), front, toMissile, cfg.halfAngleDeg());
                }
                continue;
            }
            // 来袭角判定：导弹飞行方向须大致朝向本车（与「导弹→干扰机」连线夹角 ≤ approach 半角），
            // 只干扰朝本车飞来的导弹，避免对侧向飞过/远离的导弹超大范围误干扰。
            Vec3 flightDir = projectile.getDeltaMovement();
            Vec3 toJammer = toMissile.scale(-1.0);
            if (flightDir.lengthSqr() <= 1.0E-8 || toJammer.lengthSqr() <= 1.0E-8) {
                continue;
            }
            double approachAngle = Math.toDegrees(Math.acos(
                    flightDir.normalize().dot(toJammer.normalize())));
            if (approachAngle > cfg.approachHalfAngleDeg()) {
                if (JAM_DEBUG) {
                    LOGGER.info("[JAM-DBG] vehicle={} APPROACH MISS: angle={} limit={}",
                            vehicle.getId(), approachAngle, cfg.approachHalfAngleDeg());
                }
                continue;
            }
            if (JAM_DEBUG) {
                LOGGER.info("[JAM-DBG] vehicle={} bone={} HIT dist={} range={}",
                        vehicle.getId(), entry.getKey(), dist, cfg.range());
            }
            return new ActiveJammer(vehicle, cfg.strength(), resolveSideSign(projectile, vehicle, missilePos),
                    cfg.headingRateDeg(), cfg.offsetAngleDeg(), cfg.offsetBaseBlocks(), cfg.offsetDownBlocks());
        }
        return null;
    }

    /**
     * 以弹体当前水平飞行方向为参考，判定干扰机在飞行线的左侧（+1）还是右侧（-1）。
     * 弹体无水平速度或干扰机在正前/正后方时，按配置 facing_yaw 符号兜底（正值向左）。
     */
    private static double resolveSideSign(RVP_BaseBullet projectile, AbstractVehicle vehicle, Vec3 missilePos) {
        Vec3 flight = projectile.getDeltaMovement();
        Vec3 flightH = new Vec3(flight.x, 0.0, flight.z);
        Vec3 toJammerH = new Vec3(vehicle.getX() - missilePos.x, 0.0, vehicle.getZ() - missilePos.z);
        if (flightH.lengthSqr() > 1.0E-6 && toJammerH.lengthSqr() > 1.0E-6) {
            Vec3 leftPerp = new Vec3(flightH.z, 0.0, -flightH.x).normalize();
            return toJammerH.dot(leftPerp) >= 0.0 ? 1.0 : -1.0;
        }
        return 1.0;
    }

    /**
     * 解析干扰机设备的探测前向：
     * 配置了 {@code facing_part}（如 {@code "turret"}）时取该部件世界朝向（随部件旋转），
     * 否则退回车体朝向；再叠加 {@code facing_yaw} 水平偏置角（正值向左），
     * 使左右分布式干扰机各覆盖不同扇区。
     */
    public static Vec3 resolveFacing(AbstractVehicle vehicle, @Nullable String facingPart, double facingYawDeg) {
        Vec3 base;
        if (facingPart != null && !facingPart.isBlank()) {
            PartUnit<?> partUnit = vehicle.getPartUnit(facingPart).orElse(null);
            if (partUnit instanceof WeaponUnit weaponUnit) {
                Vec3 world = weaponUnit.worldVec();
                if (world != null && world.lengthSqr() > 1.0E-8) {
                    base = world.normalize();
                } else {
                    base = VectorUtil.rotToVec(vehicle.getXRot(), vehicle.getYRot());
                }
            } else {
                base = VectorUtil.rotToVec(vehicle.getXRot(), vehicle.getYRot());
            }
        } else {
            base = VectorUtil.rotToVec(vehicle.getXRot(), vehicle.getYRot());
        }
        if (facingYawDeg == 0.0) {
            return base;
        }
        double rad = Math.toRadians(facingYawDeg);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        return new Vec3(
                base.x * cos - base.z * sin,
                base.y,
                base.x * sin + base.z * cos
        );
    }

    /** 导弹是否为该干扰机车辆自身发射（排除自身，只干扰敌方/他方导弹）。 */
    private static boolean isSelfMissile(AbstractVehicle vehicle, RVP_BaseBullet projectile) {
        AbstractVehicle shooterVehicle = projectile.getShooterVehicle();
        if (shooterVehicle != null && shooterVehicle.getUUID().equals(vehicle.getUUID())) {
            return true;
        }
        Entity owner = projectile.getOwner();
        if (owner != null) {
            if (owner.getUUID().equals(vehicle.getUUID())) {
                return true; // 载具本体作为发射者（如无人机）
            }
            if (owner.getVehicle() == vehicle) {
                return true; // 发射者是该车车组成员
            }
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────
    // 提示消息（服务端直发，防刷屏）
    // ─────────────────────────────────────────────────────────────

    private static void notifyVictim(RVP_BaseBullet projectile) {
        Entity owner = projectile.getOwner();
        if (owner instanceof ServerPlayer player) {
            // 屏幕中下（actionbar）提示，与武器切换/开关弹舱提示一致，不进聊天栏
            player.displayClientMessage(Component.translatable("message.ywzj_rvp.saclos_jammed"), true);
        }
    }

    private static void notifyJammer(AbstractVehicle vehicle) {
        if (vehicle.level().isClientSide()) {
            return;
        }
        long now = vehicle.level().getGameTime();
        Long last = LAST_JAM_NOTIFY_BY_VEHICLE.get(vehicle.getId());
        if (last != null && now - last < JAM_NOTIFY_INTERVAL) {
            return;
        }
        LAST_JAM_NOTIFY_BY_VEHICLE.put(vehicle.getId(), now);
        Entity driver = vehicle.getDriver();
        if (driver instanceof ServerPlayer player) {
            // 屏幕中下（actionbar）提示，与武器切换/开关弹舱提示一致，不进聊天栏
            player.displayClientMessage(Component.translatable("message.ywzj_rvp.jam_success"), true);
        }
    }

    /** 命中结果：干扰机车辆 + 干扰强度 + 左右侧符号（+1 左 / -1 右）+ 偏转参数。 */
    private record ActiveJammer(AbstractVehicle vehicle, double strength, double sideSign,
                                double headingRate, double offsetAngleDeg, double offsetBaseBlocks,
                                double offsetDownBlocks) {
    }
}
