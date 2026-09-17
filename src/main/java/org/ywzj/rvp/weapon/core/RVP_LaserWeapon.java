package org.ywzj.rvp.weapon.core;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.weapon.damage.RVP_DamageApplier;
import org.ywzj.rvp.weapon.damage.RVP_VehicleHitboxFactorManager;
import org.ywzj.rvp.weapon.damage.RVP_VehicleHurtScalingHandler;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.rvp.weapon.laser.RVP_LaserRaycast;
import org.ywzj.rvp.weapon.laser.RVP_LaserBeam;
import org.ywzj.rvp.server.warn.RVP_LaserBlindService;
import org.ywzj.rvp.server.warn.RVP_LaserWarnService;
import org.ywzj.vehicle.all.AllDamageTypes;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.EntityUtil;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.AimContext;

import java.util.List;

/**
 * Instant ray weapon for {@code rvp:laser}. Damage on server; beam visuals are client-side
 * and follow the live weapon aim via {@link org.ywzj.rvp.client.laser.RVP_ClientLaserState}.
 */
public class RVP_LaserWeapon extends RVP_WeaponBase {

    public RVP_LaserWeapon(AbstractVehicle vehicle, WeaponUnit unit, int index, RVP_WeaponData data, String serializeId) {
        super(vehicle, unit, index, data, serializeId);
    }

    /** Client charge progress for beam preview (not synced). */
    public int getChargeTick() {
        return chargeTick;
    }

    @Override
    public boolean shoot(List<AimContext> aimContexts, LivingEntity shooter) {
        noteServerShootInvocation(aimContexts, shooter);
        if (!check(aimContexts, shooter)) {
            return false;
        }
        if (isCoolingDown() || isReloading() || !getFireController().canShootHeat()) {
            return false;
        }
        getFireController().primeServerShot();
        if (!canShootOnServer(shooter)) {
            return false;
        }
        if (!consumeAmmo(aimContexts)) {
            return false;
        }
        this.lastShootTime = System.currentTimeMillis();

        AbstractVehicle vehicle = getVehicle();
        RVP_WeaponData data = getData();
        float chargeScale = consumeChargeScale();
        for (AimContext aim : aimContexts) {
            Vec3 start = RVP_AimContexts.muzzle(aim);
            Vec3 look = VectorUtil.rotToVec(aim.direction.x, aim.direction.y);
            float range = data.getLaserRange();

            RVP_LaserBeam beam = RVP_LaserRaycast.computeBeam(
                    vehicle.level(), vehicle, shooter, start, look, range,
                    data.getLaserVisual().getRenderStartDistance());

            // 烟幕截断（smokeBlocked=true）时 hitEntity 为 null：下方实体命中分支整体跳过，
            // 不结算伤害/告警/致盲——光束被烟吸收但弹药与热量照常消耗，属设计语义。
            if (beam.hitEntity() != null) {
                var source = AllDamageTypes.Sources.bullet(
                        vehicle.level().registryAccess(), shooter, shooter, beam.hitEntity().position());
                float hitDamage = RVP_DamageApplier.applyScaled(
                        data.getDirectDamage() * chargeScale, beam.hitEntity(), data);
                RVP_VehicleHitboxFactorManager.HitboxDamageResult hitboxRes = null;
                float hitDamageBeforeHitbox = hitDamage;
                if (beam.hitEntity() instanceof AbstractVehicle targetVehicle) {
                    hitboxRes = RVP_VehicleHitboxFactorManager.INSTANCE.resolveHitboxDamage(
                            targetVehicle, start, beam.impactPoint());
                    hitDamageBeforeHitbox = hitDamage;
                    // 装甲层（armor_min_damage / armor_max_damage）：与弹体路径一致，hitDamage 尚未乘
                    // 命中箱系数，由 applyArmor 按 MCH 不对称顺序统一施加（减伤先乘→扣装甲→保底 0.1→
                    // 增伤后乘→armor_max 封最终）；未配置装甲时等价于 hitDamage * factor，行为不变。
                    hitDamage = RVP_VehicleHurtScalingHandler.applyArmor(targetVehicle, hitDamage, hitboxRes.factor());
                }
                if (beam.hitEntity() instanceof AbstractVehicle targetVehicleForHurt) {
                    // 激光伤害来源 direct=射手（非投射物），本体 DamageSystem 走 hitPos==null →
                    // scale=0.2 分支；按 core_distance_scale_multiplier 预补偿 0.2。
                    float hurtAmount = RVP_VehicleHurtScalingHandler.compensateCoreDistanceFalloff(
                            targetVehicleForHurt, hitDamage, 0.2f);
                    RVP_VehicleHurtScalingHandler.pushSkip(targetVehicleForHurt);
                    try {
                        EntityUtil.hurt(source, beam.hitEntity(), hurtAmount);
                    } finally {
                        RVP_VehicleHurtScalingHandler.popSkip(targetVehicleForHurt);
                    }
                } else {
                    EntityUtil.hurt(source, beam.hitEntity(), hitDamage);
                }
                if (beam.hitEntity() instanceof AbstractVehicle targetVehicle) {
                    RVP_VehicleHitboxFactorManager.INSTANCE.tryDestroyBoneModules(targetVehicle, hitboxRes, hitDamageBeforeHitbox);
                    // 2026-09-17：激光命中载具触发激光照射告警（TYPE_LASER，敌对才告警、按射手车×目标车 10t 节流）
                    RVP_LaserWarnService.warnLaserHit(vehicle, targetVehicle);
                }
                // 激光致盲（2026-09-17）：命中玩家/gunner/其载具累计次数，攒满触发白色闪光/gunner 失去目标；
                // blind_hit_count 默认 0 = 功能关闭（onLaserHit 内短路）
                RVP_LaserBlindService.onLaserHit(vehicle, beam.hitEntity(),
                        data.getLaserData().getBlindHitCount(),
                        data.getLaserData().getBlindHitWindowTick(),
                        data.getLaserData().getBlindDurationTick());
                // 清除原版受击无敌帧：与 RVP_BaseBullet.applyEntityHitDamage 尾部同款——
                // LivingEntity.hurt 置 invulnerableTime=20（前 10t 完全免疫、后 10t 仅更高伤害可破），
                // shoot_interval 间隔的后续脉冲大多落进无敌窗且伤害恒定被原版整体吞掉，
                // 表现为"激光打生物有无敌帧"（弹体类武器无此问题正因弹体路径每次清零）；
                // 载具（AbstractVehicle）走本体 DamageSystem 不吃原版此帧，打载具本就正常。
                if (beam.hitEntity() instanceof LivingEntity living) {
                    living.invulnerableTime = 0;
                }
            }
            vehicle.physicsEngine.recoil(getWeaponUnit(), data.getRecoil());
        }
        getFireController().onShotFired();
        return true;
    }
}
