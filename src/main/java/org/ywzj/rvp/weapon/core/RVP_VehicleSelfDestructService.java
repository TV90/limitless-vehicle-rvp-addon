package org.ywzj.rvp.weapon.core;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.all.RVP_Entities;
import org.ywzj.rvp.entity.projectile.RVP_BaseBullet;
import org.ywzj.rvp.entity.projectile.RVP_BombEntity;
import org.ywzj.rvp.uav.RVP_DeployableUavService;
import org.ywzj.rvp.uav.RVP_LinkedUavStateTable;
import org.ywzj.rvp.uav.RVP_NativeUavReturnService;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;

import java.util.ArrayList;
import java.util.function.BooleanSupplier;

/** MCH 自爆炸弹语义：当前载具原地起爆；部署实例和原生 UAV 必须先完成真人回传。 */
public final class RVP_VehicleSelfDestructService {
    private RVP_VehicleSelfDestructService() {}

    /** 扣弹回调只在所有可拒绝步骤通过之后运行；失败不记热量和武器冷却。 */
    public static boolean detonate(AbstractVehicle carrier, LivingEntity shooter, RVP_WeaponData data,
                                    BooleanSupplier consumeOne, Runnable refundOne) {
        if (!(carrier.level() instanceof ServerLevel level) || carrier.isRemoved() || carrier.isDestroyed()
                || data.getWeaponKind() != RVP_EnumWeaponKind.BOMB || !data.getFireData().isSelfDestructVehicle()
                || !data.getExplosionData().explode) return false;
        boolean auxiliary = RVP_LinkedUavStateTable.isDeployableUavInstance(carrier);
        Vec3 center = carrier.position();
        var explosion = data.getExplosionData();
        float radius = Math.max(explosion.radius, explosion.getRadiusInWater() == null ? 0 : explosion.getRadiusInWater());
        if (explosion.getDestroyRadius() != null) radius = Math.max(radius, explosion.getDestroyRadius());
        if (!Float.isFinite(radius) || radius < 0) return false;
        // 调用本项目统一返回点解析，在下车前拒绝返回点仍位于爆炸区域的自爆。
        if (auxiliary && RVP_DeployableUavService.wouldReturnAnchorBeHitByExplosion(level, carrier, center, radius))
            return false;
        float checkedRadius = radius;
        // 调用显式三类分派，原生 uav:true 不能因没有母车部署标记而跳过回传。
        RVP_NativeUavReturnService.ReturnMode returnMode =
                RVP_NativeUavReturnService.classify(carrier.uav, auxiliary);
        // 必须在 stopRiding 清除本体返回字段之前构造快照；普通有人载具不准备保护动作。
        BooleanSupplier nativeReturn = returnMode == RVP_NativeUavReturnService.ReturnMode.NATIVE
                ? RVP_NativeUavReturnService.snapshotReturn(level, carrier, center, checkedRadius)
                : () -> true;
        // 复用现有炸弹战斗部及特效，不向世界生成投射物，不触发寿终或二次爆炸。
        RVP_BombEntity warhead = new RVP_BombEntity(RVP_Entities.RVP_BOMB.get(), level, data.getWeaponId());
        warhead.initFromWeapon(data, RVP_EnumWeaponKind.BOMB, carrier, shooter, center,
                new RVP_BaseBullet.AimRot(carrier.getXRot(), carrier.getYRot()), Vec3.ZERO);
        return RVP_SelfDestructSequence.execute(carrier.getUUID(),
                () -> {
                    boolean returned = RVP_NativeUavReturnService.returnForMode(returnMode,
                            () -> RVP_DeployableUavService.returnPassengersForTermination(level, carrier, center, checkedRadius),
                            nativeReturn);
                    // 真人已返回后立即移除本车假操作员，不能等其下一 tick 再自动清理。
                    return returned && (returnMode == RVP_NativeUavReturnService.ReturnMode.MANNED
                            || RVP_NativeUavReturnService.discardReturnedOperators(level, carrier));
                },
                () -> {
                    // 所有门禁通过后才消耗一枚弹药；部署终结在回传成功后启动冷却。
                    if (carrier.isRemoved() || carrier.isDestroyed() || !consumeOne.getAsBoolean()) return false;
                    if (auxiliary && !RVP_DeployableUavService.commitTermination(level, carrier)) {
                        refundOne.run();
                        return false;
                    }
                    return true;
                },
                () -> {
                    // 普通有人车按 MCH 不保护乘员；下车后仍在原位参与爆炸伤害结算。
                    for (Entity passenger : new ArrayList<>(carrier.getPassengers())) passenger.stopRiding();
                    carrier.setDestroyed();
                    warhead.detonateAtCarrier(center, carrier);
                },
                () -> {
                    if (auxiliary) RVP_DeployableUavService.finishTermination(level, carrier);
                    carrier.discard();
                });
    }
}
