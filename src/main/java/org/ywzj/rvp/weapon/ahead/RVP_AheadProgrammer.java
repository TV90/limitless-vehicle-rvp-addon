package org.ywzj.rvp.weapon.ahead;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.client.lead.RVP_LeadSolution;
import org.ywzj.rvp.client.lead.RVP_MachinegunLeadSolver;
import org.ywzj.rvp.debug.RVP_AheadDebug;
import org.ywzj.rvp.weapon.core.RVP_AimContexts;
import org.ywzj.rvp.weapon.data.RVP_EnumWeaponKind;
import org.ywzj.rvp.weapon.data.RVP_WeaponData;
import org.ywzj.rvp.weapon.fuse.RVP_AirburstRangeStore;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.vehicle.part.RadarUnit;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.AimContext;

public final class RVP_AheadProgrammer {
    private RVP_AheadProgrammer() {}

    public static boolean isAheadWeapon(@Nullable RVP_WeaponData data) {
        return data != null
                && data.getWeaponKind() == RVP_EnumWeaponKind.MACHINEGUN
                && data.getFuseData().isAheadEnabled()
                && data.getFuseData().isProgrammableAirburst();
    }

    public static RVP_AheadSolution solve(RVP_WeaponData data, WeaponUnit weaponUnit, AimContext aim,
                                          float partialTick) {
        return solveInternal(data, weaponUnit, aim, partialTick, null, true);
    }

    /**
     * 使用客户端已缓存的机炮提前量生成 AHEAD 读数，不再次执行弹道解算。
     * 空缓存会直接走无锁定回退，不得在 HUD 渲染路径补算。
     */
    public static RVP_AheadSolution solveFromResolvedLead(RVP_WeaponData data, WeaponUnit weaponUnit,
                                                          AimContext aim,
                                                          @Nullable RVP_LeadSolution resolvedLead) {
        Entity currentTarget = resolveTrackedTargetServerSafe(weaponUnit);
        RVP_LeadSolution usableLead = resolvedLead != null && resolvedLead.target() == currentTarget
                ? resolvedLead : null;
        return solveInternal(data, weaponUnit, aim, 1.0F, usableLead, false);
    }

    /** 统一处理服务端独立解算与客户端缓存复用两条 AHEAD 编程路径。 */
    private static RVP_AheadSolution solveInternal(RVP_WeaponData data, WeaponUnit weaponUnit, AimContext aim,
                                                   float partialTick,
                                                   @Nullable RVP_LeadSolution resolvedLead,
                                                   boolean allowIndependentSolve) {
        if (!isAheadWeapon(data)) {
            return RVP_AheadSolution.invalid("not_ahead_weapon");
        }
        if (weaponUnit == null || aim == null) {
            return RVP_AheadSolution.invalid("missing_context");
        }
        Vec3 muzzle = RVP_AimContexts.muzzle(aim);
        if (muzzle == null || muzzle == Vec3.ZERO) {
            return RVP_AheadSolution.invalid("missing_muzzle");
        }

        RVP_LeadSolution lead = resolvedLead;
        if (lead == null && allowIndependentSolve) {
            Entity target = resolveTrackedTargetServerSafe(weaponUnit);
            if (target != null) {
                // 调用本项目机炮解算器，为服务端真实开火独立生成权威的 AHEAD 编程距离。
                lead = RVP_MachinegunLeadSolver.solveForTarget(
                        weaponUnit, data, muzzle, target, partialTick
                );
            }
        }
        if (lead != null && lead.leadWorldPos() != null) {
            return fromReference(
                    data,
                    lead.leadWorldPos(),
                    muzzle,
                    true,
                    lead.projectileTravelDistanceMeters()
            );
        }

        if (data.getFuseData().isAheadRequireLock()) {
            return RVP_AheadSolution.invalid("lock_required");
        }

        Vec3 impact = RVP_AimContexts.impactPoint(aim);
        if (impact == null) {
            return RVP_AheadSolution.invalid("missing_impact_point");
        }
        return fromReference(data, impact, muzzle, false, 0.0D);
    }

    /**
     * 服务端安全的锁定目标解析：仅查询载具武器站/雷达的锁定状态（双端通用 API）。
     *
     * <p>不能使用 {@link RVP_MachinegunLeadSolver#resolveTrackedTarget}——其引用
     * {@code LocalVehiclePlayer}/{@code Minecraft} 等客户端专属类，专用服务器执行到该分支会抛
     * {@code Attempted to load class ... for invalid dist DEDICATED_SERVER}，导致 ahead 武器服务端无法发射。
     * 客户端 lead 提示仍走客户端版 {@code resolveTrackedTarget}（含本地外雷达锁定增强），互不影响。</p>
     */
    @Nullable
    private static Entity resolveTrackedTargetServerSafe(WeaponUnit weaponUnit) {
        if (weaponUnit == null) {
            return null;
        }
        Entity target = weaponUnit.getLockedEntity();
        if (target != null && target.isAlive()) {
            return target;
        }
        RadarUnit radar = weaponUnit.getMainRadarUnit();
        if (radar != null) {
            Entity radarTarget = radar.getLockedEntity();
            if (radarTarget != null && radarTarget.isAlive()) {
                return radarTarget;
            }
        }
        return null;
    }

    public static RVP_AheadSolution programForShot(AbstractVehicle vehicle, WeaponUnit weaponUnit, int weaponIndex,
                                                   RVP_WeaponData data, AimContext aim, float partialTick) {
        if (!isAheadWeapon(data) || vehicle == null || weaponUnit == null) {
            return RVP_AheadSolution.invalid("skip");
        }
        RVP_AheadSolution solution = solve(data, weaponUnit, aim, partialTick);
        RVP_AirburstRangeStore.set(vehicle, weaponUnit, weaponIndex, solution.programmedDistanceMeters());
        RVP_AheadDebug.logProgram(data, solution);
        return solution;
    }

    private static RVP_AheadSolution fromReference(RVP_WeaponData data, Vec3 referenceWorldPos, Vec3 muzzle,
                                                   boolean usedLeadSolution, double projectileTravelDistance) {
        double referenceDistance = muzzle.distanceTo(referenceWorldPos);
        double baseDistance = projectileTravelDistance > 0.0D ? projectileTravelDistance : referenceDistance;
        int programmedDistance = Mth.floor(baseDistance - data.getFuseData().getAheadBurstOffsetMeters() + 0.5D);
        int min = data.getFuseData().getAirburstMeasureMin();
        int max = data.getFuseData().getAirburstMeasureMax();
        if (programmedDistance <= min || programmedDistance >= max) {
            return RVP_AheadSolution.invalid("out_of_fuse_range");
        }
        return new RVP_AheadSolution(
                referenceWorldPos,
                referenceDistance,
                programmedDistance,
                usedLeadSolution,
                null
        );
    }
}
