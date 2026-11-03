package org.ywzj.rvp.virtualflight.server;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.all.RVP_Entities;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.entity.projectile.RVP_MissileEntity;
import org.ywzj.rvp.guidance.trajectorymath.virtualguidance.RVP_VirtualTrajectoryResult;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/**
 * 虚拟飞行的实体运动适配器：保留一个未加入世界的 RVP 导弹对象，直接执行正式制导和运动方法。
 * 不复制任何弹道公式，也不运行世界实体 Tick、沿途碰撞、区块申请或网络广播。
 */
public final class RVP_EntityTrajectoryIntegrator {
    /** 与历史纯数学近似明确区分的持久化实现标识。 */
    public static final String ID = "rvp_entity";
    /** 实体运动快照的实现版本；改变跨刻状态语义时必须递增。 */
    public static final int VERSION = 1;

    /** 工具类不允许实例化。 */
    private RVP_EntityTrajectoryIntegrator() {}

    /** 推进一个飞行刻；武器热重载替换配置对象时，先用完整快照重建计算对象。 */
    public static RVP_VirtualTrajectoryResult step(Level level, RVP_VirtualMissileState state,
            RVP_WeaponData data, @Nullable AbstractVehicle vehicle, @Nullable LivingEntity owner) {
        RVP_MissileEntity missile = state.trajectoryEntity;
        if (missile == null || missile.getRvpData() != data) {
            // 调用同一个恢复入口，停服重启和配置热重载都不能丢失点火参考点、角速度或风向。
            missile = createEntity(level, state, data, vehicle, owner);
            state.trajectoryEntity = missile;
        }
        Vec3 before = state.trajectory().velocity();
        // 调用无散布目标写入入口，使 GPS 雷达上行更新与实体态一样从下一刻正式制导开始生效。
        missile.setGuidanceTargetPos(state.snapshot.targetPosition());
        // 调用真实实体的专用飞行入口；制导、攻角兜底、受力和运动分派保持与实体 Tick 一致。
        missile.tickVirtualMidcourseFlight();
        // 调用完整快照出口，GPS 重置标志和二脉冲时间只接受实体链实际产生的值。
        state.snapshot = missile.createVirtualMidcourseSnapshot();
        state.kinematics = missile.createVirtualMidcourseKinematics();
        Vec3 after = state.trajectory().velocity();
        double turn = before.lengthSqr() <= 1.0E-12 || after.lengthSqr() <= 1.0E-12 ? 0.0
                : Math.acos(Mth.clamp(before.normalize().dot(after.normalize()), -1.0, 1.0));
        // 调用权威记录校验器；寿命到期另由管理器优先处理，其余坏数值不能交给恢复链。
        return new RVP_VirtualTrajectoryResult(state.trajectory(), turn, !state.isStructurallyValid());
    }

    /**
     * 用完整运动快照重建 RVP 类型化导弹；调用者决定是否加入世界。
     * 虚拟计算与末段真实恢复共用此入口，恢复时绝不能额外按速度改写机头或重新冻结 PRESET。
     */
    public static RVP_MissileEntity createEntity(Level level, RVP_VirtualMissileState state,
            RVP_WeaponData data, @Nullable AbstractVehicle vehicle, @Nullable LivingEntity owner) {
        RVP_MissileEntity missile = new RVP_MissileEntity(RVP_Entities.RVP_MISSILE.get(), level, state.weaponId);
        var trajectory = state.trajectory();
        var aim = new RVP_BaseBullet.AimRot(trajectory.xRot(), trajectory.yRot());
        // 调用正式初始化建立武器/同步字段，再用完整快照覆盖在途状态，不重新发射或生成 GPS 散布。
        missile.initFromWeapon(data, RVP_EnumWeaponKind.MISSILE, vehicle, owner,
                trajectory.position(), aim, trajectory.velocity());
        missile.setUUID(state.flightUuid);
        missile.restoreFromVirtualMidcourseSnapshot(state.snapshot, state.launchPosition);
        // 调用实体运动记忆恢复入口，保留当前速率基准、顶点历史、冷发射时序和实际点火参考点。
        missile.restoreVirtualMidcourseKinematics(state.kinematics, state.coldLaunchTimeTick);
        missile.radarUplinkTargeting = state.radarUplinkTargeting;
        return missile;
    }
}
