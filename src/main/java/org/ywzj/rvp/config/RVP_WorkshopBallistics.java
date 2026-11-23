package org.ywzj.rvp.config;

import com.google.gson.JsonObject;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.guidance.trajectorymath.util.RVP_AeroSteeringModel;
import org.ywzj.rvp.guidance.trajectorymath.util.RVP_PropulsionMath;
import org.ywzj.rvp.guidance.trajectorymath.util.RVP_QuadraticAirDrag;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.vehicle.custom.weapon.data.BaseVehicleWeaponData;
import org.ywzj.vehicle.custom.weapon.data.VehicleAerialBombWeaponData;
import org.ywzj.vehicle.custom.weapon.data.VehicleCannonWeaponData;
import org.ywzj.vehicle.custom.weapon.data.VehicleGrenadeWeaponData;
import org.ywzj.vehicle.custom.weapon.data.VehicleMissileWeaponData;
import org.ywzj.vehicle.custom.weapon.data.VehicleRocketWeaponData;
import org.ywzj.vehicle.util.PhysicsHelper;

import static org.ywzj.rvp.config.RVP_WorkshopData.*;

/** 档案中弹速的内部单位为格/刻。平台速度只输入曲线，不计入武器初速。 */
public final class RVP_WorkshopBallistics {
    private RVP_WorkshopBallistics() {}

    /** 返回真实发射入口支持的基础初速。屏蔽没有弹体的装备及内部防零兜底。 */
    public static Double muzzle(JsonObject raw, BaseVehicleWeaponData resolved) {
        Double configured = number(raw, "velocity");
        if (resolved instanceof RVP_WeaponData data) {
            var kind = data.getWeaponKind();
            if (kind == RVP_EnumWeaponKind.LASER || kind == RVP_EnumWeaponKind.TARGETING_POD) return null;
            double speed = data.resolveMuzzleSpeed(kind);
            if (kind != RVP_EnumWeaponKind.MACHINEGUN && speed <= 0.011
                    && !(data.getProjectileVelocity() > 0)) return null;
            // 与实体初始化一致，简化高速火箭在生成时还会应用一次速度倍率。
            if (kind == RVP_EnumWeaponKind.ROCKET && !data.usesPropulsion()
                    && !data.getProjectileData().isRocketEngineMisconfigured() && data.getProjectileVelocity() > 4)
                speed *= data.getProjectileVelocity() / 4;
            return Double.isFinite(speed) ? speed : null;
        }
        // 本体导弹及航弹由平台释放，武器没有独立出膛速度；冷发射弹射量由挂架单独提供。
        if (resolved instanceof VehicleMissileWeaponData || resolved instanceof VehicleAerialBombWeaponData) return 0.0;
        if (!(resolved instanceof VehicleCannonWeaponData || resolved instanceof VehicleRocketWeaponData
                || resolved instanceof VehicleGrenadeWeaponData)) return null;
        return configured != null && configured >= 0 ? configured : null;
    }

    /** 按游戏气动模型取设计过载；没有有限值的装备不显示此项。 */
    public static Double maxG(JsonObject raw, BaseVehicleWeaponData resolved) {
        if (resolved instanceof VehicleMissileWeaponData missile && number(raw, "max_g") != null)
            return missile.getMaxG() > 0 ? (double) missile.getMaxG() : null;
        if (!(resolved instanceof RVP_WeaponData data)) return null;
        var p = data.getProjectileData();
        if (p.hasRvpMaxG()) return p.getRvpMaxG();
        if (!p.hasTurningFactor()) return null;
        var limits = p.resolveAeroSteeringLimits(data.resolveMuzzleSpeed(data.getWeaponKind()), 256);
        double value = RVP_AeroSteeringModel.equivalentGsFromTurningFactor(p.maximumTurningFactor(data.getLife()), limits.referenceSpeed());
        return value > 0 && Double.isFinite(value) ? value : null;
    }

    /** 每段点火推力和参考阻力（N）。阻力参考速度取 100 m/s，高度取下拉选择。 */
    public record Forces(double first, Double second, double drag) {}

    /** 本体的推力配置已经是 N；阻力按本体对 100 m/s 弹体调用的同一个积分函数换算。 */
    public static Forces baseForces(JsonObject raw, BaseVehicleWeaponData resolved) {
        if (!(resolved instanceof VehicleMissileWeaponData || resolved instanceof VehicleRocketWeaponData)) return null;
        Double mass = number(raw, "mass"), thrust = number(raw, "thrust"), drag = number(raw, "drag_coefficient");
        if (mass == null || mass <= 0 || thrust == null || thrust <= 0 || drag == null || drag < 0) return null;
        float burn = resolved instanceof VehicleMissileWeaponData m ? m.getMotorBurnTime() : ((VehicleRocketWeaponData) resolved).getMotorBurnTime();
        return burn > 0 ? new Forces(thrust, null, PhysicsHelper.dragAccelerationPerTick(drag, mass, 25) * 400 * mass) : null;
    }

    public static Forces forces(RVP_WeaponData data, int altitude) {
        var p = data.getProjectileData();
        var motor = RVP_PropulsionMath.resolveMotorState(p, 0, false);
        double kg = motor.mass() < 1 ? motor.mass() * 1000 : motor.mass();
        double first = RVP_PropulsionMath.accelerationPerTick(motor.thrust(), motor.mass()) * 400 * kg;
        Double second = null;
        if (p.usesSecondPulse()) {
            var pulse = RVP_PropulsionMath.resolveMotorState(p, (int) p.getResolvedMotorBurnTime() + 1, true);
            double dryKg = pulse.mass() < 1 ? pulse.mass() * 1000 : pulse.mass();
            second = RVP_PropulsionMath.accelerationPerTick(pulse.thrust(), pulse.mass()) * 400 * dryKg;
        }
        double density = data.getWeaponKind() == RVP_EnumWeaponKind.MISSILE ? p.resolveAltitudeDragFactor(altitude) : 1;
        var after = RVP_QuadraticAirDrag.apply(new Vec3(5, 0, 0), p.getResolvedDragCoefficient(), motor.mass(), density);
        return new Forces(first, second, (5 - after.x) * 400 * kg);
    }
}
