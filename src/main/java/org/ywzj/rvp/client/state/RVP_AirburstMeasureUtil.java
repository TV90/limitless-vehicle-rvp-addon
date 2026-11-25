package org.ywzj.rvp.client.state;

import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.AimContext;

/**
 * 可编程空爆测距：以第一人称弹道落点（{@link WeaponUnit#weaponHitPos} /
 * {@link WeaponUnit#aimHitPosition()}）为准，与 {@code VehicleScopeOverlay} 绿框下 “XX m” 一致。
 */
public final class RVP_AirburstMeasureUtil {

    /** 测距射线的最大长度（格），与本体 {@code WeaponUnit.aimHitPosition} 的 1024 一致。 */
    private static final double MEASURE_RAY_LENGTH = 1024.0D;

    private RVP_AirburstMeasureUtil() {}

    /** 保持旧调用的仅方块测距语义。 */
    public static int measureDistanceMeters(WeaponUnit weaponUnit) {
        return measureDistanceMeters(weaponUnit, false);
    }

    /** @return 测距米数；没有可测方块或实体时返回 0。 */
    public static int measureDistanceMeters(WeaponUnit weaponUnit, boolean includeEntities) {
        if (weaponUnit == null) {
            return 0;
        }
        LocalVehiclePlayer lvp = LocalVehiclePlayer.instance;
        if (lvp.getPlayer() == null) {
            return 0;
        }
        Vec3 hit = hitPointOnAimRay(weaponUnit, includeEntities);
        if (hit == null) {
            return 0;
        }

        // 与 scope 落点测距 HUD（rangeFinding → aimLocationDistance）同一套斜距
        return (int) Math.round(lvp.getPlayer().position().distanceTo(hit));
    }

    /**
     * 准心射线先截取方块，再按弹种开关查更近的实体碰撞箱。
     *
     * <p>复刻本体的射线起点与长度：{@link WeaponUnit#aimContext()} 给出炮口位置与炮管指向，
     * 方块落点继续使用本体 HUD 的结果；实体落点用碰撞箱入射点，避免按实体脚底测距。</p>
     */
    private static Vec3 hitPointOnAimRay(WeaponUnit weaponUnit, boolean includeEntities) {
        AbstractVehicle vehicle = weaponUnit.getVehicle();
        if (vehicle == null || !vehicle.level().isClientSide()) {
            return null;
        }
        // 调用本体瞄准上下文，取与测距 HUD 同一门炮的炮口与指向
        AimContext aim = weaponUnit.aimContext();
        if (aim == null || aim.from == null || aim.direction == null) {
            return null;
        }
        // 调用本体方向换算，把 (xRot, yRot) 还原成世界方向向量
        Vec3 direction = VectorUtil.rotToVec(aim.direction.x, aim.direction.y);
        if (direction.lengthSqr() < 1.0E-8D) {
            return null;
        }
        Vec3 start = aim.from;
        Vec3 end = start.add(direction.normalize().scale(MEASURE_RAY_LENGTH));
        // 调用世界方块射线检测，流体不会作为可编程空爆的测距目标。
        BlockHitResult result = vehicle.level().clip(new ClipContext(
                start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, vehicle));
        Vec3 blockEnd = result.getType() == HitResult.Type.BLOCK ? result.getLocation() : end;
        if (includeEntities) {
            // 调用原版实体射线检测，只搜索方块之前的线段，并排除发射车及车上操作者。
            EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
                    vehicle.level(), LocalVehiclePlayer.instance.getPlayer(), start, blockEnd,
                    new AABB(start, blockEnd).inflate(1.0D),
                    candidate -> candidate != vehicle
                            && candidate != LocalVehiclePlayer.instance.getPlayer()
                            && candidate.getVehicle() != vehicle
                            && candidate.isPickable() && !candidate.isSpectator());
            if (entityHit != null) {
                return entityHit.getEntity().getBoundingBox().clip(start, blockEnd)
                        .orElse(entityHit.getLocation());
            }
        }
        if (result.getType() != HitResult.Type.BLOCK) {
            // 天空无命中必须清零；1024 格远端点不能被误当成测距结果。
            return null;
        }
        Vec3 hit = weaponUnit.weaponHitPos;
        return hit != null ? hit : result.getLocation();
    }
}
