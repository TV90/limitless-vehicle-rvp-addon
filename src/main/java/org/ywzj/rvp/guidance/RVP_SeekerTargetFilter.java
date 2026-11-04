package org.ywzj.rvp.guidance;

import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.ecm.RVP_EcmIff;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

/** 搜索、授权、截获及持续跟踪共用的弹药归属门；不改变普通载具的既有友伤规则。 */
public final class RVP_SeekerTargetFilter {
    private RVP_SeekerTargetFilter() {}

    /** 同一飞行/同射手/同发射车及其友方弹药不能成为自动寻的目标。 */
    public static boolean allows(RVP_BaseBullet seeker, @Nullable Entity target) {
        return target != null && !sameIdentity(seeker, target)
                && allows(seeker.getOwner(), seeker.getShooterVehicle(), target);
    }

    /** 发射前尚无弹体实例，使用操作手与发射载具检查同一归属关系。 */
    public static boolean allows(@Nullable Entity shooter, @Nullable AbstractVehicle carrier, @Nullable Entity target) {
        if (target == null) return false;
        if (!(target instanceof RVP_BaseBullet ammo)) return true;
        if (sameIdentity(shooter, ammo.getOwner()) || sameIdentity(carrier, ammo.getShooterVehicle())) return false;
        // 调用已有载具IFF，保留同队/联盟/Gunner阵营规则；敌方弹药仍可用于反导。
        return !RVP_EcmIff.areVehiclesFriendly(carrier, ammo.getShooterVehicle());
    }

    /** UUID覆盖虚拟/真实交接后的不同Java对象；双方无归属时不能误判为同射手。 */
    private static boolean sameIdentity(@Nullable Entity a, @Nullable Entity b) {
        return a != null && b != null && (a == b || a.getUUID() != null && a.getUUID().equals(b.getUUID()));
    }
}
