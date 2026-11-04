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
 * 不复制任何弹道公式，也不运行世界实体 Tick、沿途碰撞或区块申请；制导自身的控制反馈照常执行。
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
        // 调用共用计算对象入口，雷达查询和运动推进始终持有同一枚虚拟目标。
        RVP_MissileEntity missile = getOrCreateEntity(level, state, data, vehicle, owner);
        Vec3 before = state.trajectory().velocity();
        // 调用无散布目标写入入口，使 GPS 雷达上行更新与实体态一样从下一刻正式制导开始生效。
        // 仅外部上行改坐标时覆盖；电视操控或导引头刚写入原对象的实时目标不能被旧快照冲掉。
        if (state.guidanceTargetOverridePending) {
            missile.setGuidanceTargetPos(state.snapshot.targetPosition());
            state.guidanceTargetOverridePending = false;
        }
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

    /** 取得不入世的计算/目标对象；重建时保留会话 ID，并迁移现有接触与锁定。 */
    public static RVP_MissileEntity getOrCreateEntity(Level level, RVP_VirtualMissileState state,
            RVP_WeaponData data, @Nullable AbstractVehicle vehicle, @Nullable LivingEntity owner) {
        RVP_MissileEntity previous = state.trajectoryEntity;
        if (previous == null || previous.getRvpData() != data || state.trajectoryEntityInvalidated) {
            // 调用当前配置的上行间隔解析器，读档不退回固定的默认周期。
            state.radarUplinkIntervalSecond = org.ywzj.rvp.weapon.data.RVP_GuidanceDataGPS
                    .resolveRadarUpdateIntervalSecond(data.getGuidanceData());
            // 调用完整恢复入口，配置重载和显式改轨迹均从权威记录重建。
            RVP_MissileEntity missile = createEntity(level, state, data, vehicle, owner);
            state.targetEntityId = missile.getId();
            missile.setVirtualMidcourseTarget(true);
            state.trajectoryEntity = missile;
            if (previous != null) {
                // 调用目标交接入口，已发射拦截弹和雷达不能继续追旧计算对象。
                RVP_VirtualMissileTargets.replaceReferences(level, previous, missile);
                // 电视远程观察仍持有权威对象引用，配置重载重建时同样交接，不能静默结束控制会话。
                org.ywzj.rvp.server.hitl.RVP_HitlRemoteViewService.replaceMissile(previous, missile);
                previous.setRemoved(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
            }
            state.trajectoryEntityInvalidated = false;
        }
        // 调用来源刷新入口，读档时尚未加载的发射车/射手重新出现后，雷达与拦截恢复正确敌我判断。
        state.trajectoryEntity.refreshVirtualMidcourseSources(vehicle, owner);
        return state.trajectoryEntity;
    }

    /**
     * 用完整运动快照重建 RVP 类型化导弹；调用者决定是否加入世界。
     * 虚拟计算与末段真实恢复共用此入口，恢复时绝不能额外按速度改写机头或重新冻结 PRESET。
     */
    public static RVP_MissileEntity createEntity(Level level, RVP_VirtualMissileState state,
            RVP_WeaponData data, @Nullable AbstractVehicle vehicle, @Nullable LivingEntity owner) {
        // 配置重载前先收下等待期间刚到的操控与指定目标；明确改轨迹的快照保持优先。
        state.captureEntityState();
        RVP_MissileEntity missile = new RVP_MissileEntity(RVP_Entities.RVP_MISSILE.get(), level, state.weaponId);
        var trajectory = state.trajectory();
        var aim = new RVP_BaseBullet.AimRot(trajectory.xRot(), trajectory.yRot());
        // 调用正式初始化建立武器/同步字段，再用完整快照覆盖在途状态，不重新发射或生成 GPS 散布。
        missile.initFromWeapon(data, RVP_EnumWeaponKind.MISSILE, vehicle, owner,
                trajectory.position(), aim, trajectory.velocity());
        missile.setUUID(state.flightUuid);
        if (state.targetEntityId != null) missile.setId(state.targetEntityId);
        missile.restoreFromVirtualMidcourseSnapshot(state.snapshot, state.launchPosition);
        // 调用实体运动记忆恢复入口，保留当前速率基准、顶点历史、冷发射时序和实际点火参考点。
        missile.restoreVirtualMidcourseKinematics(state.kinematics, state.coldLaunchTimeTick);
        // GPS 以外的导引头、操作手输入、反制和引信波次使用完整运行态恢复，不重新初始化发射。
        missile.restoreVirtualRuntimeData(state.trajectoryEntity == null
                ? state.runtimeData : state.trajectoryEntity.saveVirtualRuntimeData());
        missile.radarUplinkTargeting = state.radarUplinkTargeting;
        // 初始化原版上一刻坐标，避免不入世对象以世界原点作历史位置，被本体命中修正远移受击盒。
        missile.setOldPosAndRot();
        if (state.trajectoryEntity != null && !state.trajectoryEntityInvalidated) {
            // 本体按 xOld 位移修正直击盒；配置重载和真实恢复都沿用当前计算对象的位移历史。
            missile.xOld = state.trajectoryEntity.xOld;
            missile.yOld = state.trajectoryEntity.yOld;
            missile.zOld = state.trajectoryEntity.zOld;
        }
        return missile;
    }
}
