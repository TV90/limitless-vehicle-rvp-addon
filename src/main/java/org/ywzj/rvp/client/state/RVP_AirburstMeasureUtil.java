package org.ywzj.rvp.client.state;

import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
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

    /**
     * @return 测距米数；无有效落点或准心射线上未截取到方块时返回 0
     */
    public static int measureDistanceMeters(WeaponUnit weaponUnit) {
        if (weaponUnit == null) {
            return 0;
        }
        LocalVehiclePlayer lvp = LocalVehiclePlayer.instance;
        if (lvp.getPlayer() == null) {
            return 0;
        }
        // 对准天空等落空情形必须先判掉：本体 hitPosition 落空时返回 1024 格远端点，
        // 它仍在 airburst_measure_max（常见 2000）以内，会被误当成有效定距装订。
        if (!hasBlockOnAimRay(weaponUnit)) {
            return 0;
        }

        Vec3 hit = weaponUnit.weaponHitPos;
        if (hit == null) {
            hit = weaponUnit.aimHitPosition();
        }
        if (hit == null) {
            return 0;
        }

        // 与 scope 落点测距 HUD（rangeFinding → aimLocationDistance）同一套斜距
        return (int) Math.round(lvp.getPlayer().position().distanceTo(hit));
    }

    /**
     * 准心射线上是否截取到方块（只算方块，不计流体与实体）。
     *
     * <p>复刻本体的射线起点与长度：{@link WeaponUnit#aimContext()} 给出炮口位置与炮管指向，
     * 截取结果只用于「落空即清零」判定，不改变原有落点取值。</p>
     */
    private static boolean hasBlockOnAimRay(WeaponUnit weaponUnit) {
        AbstractVehicle vehicle = weaponUnit.getVehicle();
        if (vehicle == null || !vehicle.level().isClientSide()) {
            return false;
        }
        // 调用本体瞄准上下文，取与测距 HUD 同一门炮的炮口与指向
        AimContext aim = weaponUnit.aimContext();
        if (aim == null || aim.from == null || aim.direction == null) {
            return false;
        }
        // 调用本体方向换算，把 (xRot, yRot) 还原成世界方向向量
        Vec3 direction = VectorUtil.rotToVec(aim.direction.x, aim.direction.y);
        if (direction.lengthSqr() < 1.0E-8D) {
            return false;
        }
        Vec3 start = aim.from;
        Vec3 end = start.add(direction.normalize().scale(MEASURE_RAY_LENGTH));
        // 只做方块碰撞检测（与本体 hitPosition 相同的 COLLIDER + 忽略流体），流体命中不算截取方块
        BlockHitResult result = vehicle.level().clip(new ClipContext(
                start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, vehicle));
        return result.getType() == HitResult.Type.BLOCK;
    }
}
