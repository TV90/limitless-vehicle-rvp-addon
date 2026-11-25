package org.ywzj.rvp.client.state;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.ywzj.rvp.util.RVP_NamedVehicleObbs;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.LocalVehiclePlayer;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;

/** 可编程空爆测距：取当前炮口瞄准射线上最近的有效实体或方块表面，落空返回零。 */
public final class RVP_AirburstMeasureUtil {
    /** 与本体武器站测距一致的射线长度，单位米。 */
    private static final double MEASURE_RAY_LENGTH = 1024.0D;

    private RVP_AirburstMeasureUtil() { }

    /** 保持既有调用的仅方块测距入口。 */
    public static int measureDistanceMeters(WeaponUnit weaponUnit) {
        return measureDistanceMeters(weaponUnit, false);
    }

    public static int measureDistanceMeters(WeaponUnit weaponUnit, boolean includeEntities) {
        var player = LocalVehiclePlayer.instance.getPlayer();
        if (weaponUnit == null || player == null) return 0;
        AbstractVehicle vehicle = weaponUnit.getVehicle();
        if (vehicle == null || !(vehicle.level() instanceof ClientLevel level)) return 0;
        // 调用本体瞄准上下文，使用当前炮口和实际炮轴，不读取上一刻的缓存落点。
        var aim = weaponUnit.aimContext();
        if (aim == null || aim.from == null || aim.direction == null) return 0;
        Vec3 direction = VectorUtil.rotToVec(aim.direction.x, aim.direction.y);
        if (direction.lengthSqr() < 1.0E-8D) return 0;
        Vec3 start = aim.from;
        Vec3 end = start.add(direction.normalize().scale(MEASURE_RAY_LENGTH));
        var block = level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, vehicle));
        Vec3 selected = block.getType() == HitResult.Type.BLOCK ? block.getLocation() : null;
        if (!includeEntities) {
            if (selected == null) return 0;
            Vec3 hudHit = weaponUnit.weaponHitPos;
            return (int) Math.round(player.position().distanceTo(hudHit == null ? selected : hudHit));
        }
        // 先截断到遮挡方块，后方实体不能穿墙成为测距目标。
        if (selected != null) end = selected;
        double nearest = start.distanceToSqr(end);
        AABB search = new AABB(start, end).inflate(1.0);
        // 复用本项目真实/远距代理候选；排除本车、乘员及独立部件实体。
        for (Entity target : RVP_ClientWeaponTargets.entities(level)) {
            if (!target.isPickable() || target.isSpectator() || target instanceof net.minecraftforge.entity.PartEntity<?>
                    || target.getRootVehicle() == vehicle.getRootVehicle()
                    || !target.getBoundingBox().intersects(search)) continue;
            Vec3 hit;
            if (target instanceof AbstractVehicle other) {
                // 载具按实时受击 OBB 取表面，模型空洞不使用外包 AABB 补齐。
                var boxes = RVP_NamedVehicleObbs.collect(other);
                if (!boxes.isEmpty()) {
                    var result = RVP_NamedVehicleObbs.raycast(boxes, start, end);
                    hit = result == null ? null : result.point();
                } else hit = other.getBoundingBox().clip(start, end).orElse(null);
            } else {
                AABB box = target.getBoundingBox();
                hit = box.contains(start) ? start : box.clip(start, end).orElse(null);
            }
            if (hit != null && start.distanceToSqr(hit) <= nearest) {
                nearest = start.distanceToSqr(hit);
                selected = hit;
            }
        }
        // 沿用观瞄 HUD 的操作手到落点斜距，实体在天空中也可有效测距。
        return selected == null ? 0 : (int) Math.round(player.position().distanceTo(selected));
    }
}
