package org.ywzj.rvp.weapon;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.ywzj.rvp.ext.VehicleRocketWeaponDataExt;
import org.ywzj.vehicle.custom.CommonAssetsManager;
import org.ywzj.vehicle.custom.weapon.VehicleWeaponIndex;
import org.ywzj.vehicle.custom.part.data.WeaponUnitData;
import org.ywzj.vehicle.custom.weapon.data.VehicleRocketWeaponData;
import org.ywzj.vehicle.util.VectorUtil;
import org.ywzj.vehicle.vehicle.part.WeaponUnit;
import org.ywzj.vehicle.vehicle.pojo.AimContext;
import org.ywzj.vehicle.vehicle.pojo.Bolt;

import java.util.ArrayList;
import java.util.List;

public final class RVP_RocketBallistics {

    private RVP_RocketBallistics() {}

    public record Params(double velocity, double gravity, double drag, int predictionTick) {}

    @Nullable
    public static Params resolve(ResourceLocation weaponId) {
        if (weaponId == null) {
            return null;
        }
        VehicleWeaponIndex<?, ?> index = CommonAssetsManager.vehicleWeaponManager().getIndex(weaponId).orElse(null);
        if (index == null || !(index.data() instanceof VehicleRocketWeaponData data)) {
            return null;
        }
        return resolve(data);
    }

    @Nullable
    public static Params resolve(VehicleRocketWeaponData data) {
        if (!(data instanceof VehicleRocketWeaponDataExt ext) || !ext.ywzj_rvp$isBallisticEnabled()) {
            return null;
        }
        int predictionTick = Math.max(1, ext.ywzj_rvp$getBallisticPredictionTick());
        return new Params(
                Math.max(0.01, data.getVelocity()),
                Math.max(0.0, ext.ywzj_rvp$getBallisticGravity()),
                Math.max(0.0, ext.ywzj_rvp$getBallisticDrag()),
                predictionTick
        );
    }

    @Nullable
    public static Vec3 computeWeaponImpact(Level level, WeaponUnit weaponUnit, Vec3 vehicleVelocity, VehicleRocketWeaponData data, @Nullable Entity clipEntity) {
        Params params = resolve(data);
        if (params == null) {
            return null;
        }
        return computeWeaponImpact(level, weaponUnit, vehicleVelocity, params, clipEntity);
    }

    @Nullable
    public static Vec3 computeWeaponImpact(Level level, WeaponUnit weaponUnit, Vec3 vehicleVelocity, Params params, @Nullable Entity clipEntity) {
        List<AimContext> contexts = weaponUnit.aimContexts();
        if (contexts.isEmpty()) {
            contexts = List.of(weaponUnit.aimContext());
        }
        if (contexts.isEmpty()) {
            return null;
        }
        if (weaponUnit.getFiringMode() == WeaponUnitData.FiringMode.SALVO) {
            List<Vec3> impacts = new ArrayList<>();
            for (AimContext context : contexts) {
                Vec3 impact = computeAimContextImpact(level, context, vehicleVelocity, params, clipEntity);
                if (impact != null) {
                    impacts.add(impact);
                }
            }
            return averageImpact(impacts);
        }
        int nextIndex = resolveCurrentBoltIndex(weaponUnit, contexts.size());
        return computeAimContextImpact(level, contexts.get(nextIndex), vehicleVelocity, params, clipEntity);
    }

    @Nullable
    public static Vec3 computeImpact(Level level, Vec3 startPos, Vec3 startVelocity, Params params, @Nullable Entity clipEntity) {
        Vec3 pos = startPos;
        Vec3 velocity = startVelocity;
        for (int i = 0; i < params.predictionTick(); i++) {
            Vec3 nextPos = pos.add(velocity);
            BlockHitResult hit = level.clip(new ClipContext(pos, nextPos, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, clipEntity));
            if (hit.getType() == HitResult.Type.BLOCK) {
                return hit.getLocation();
            }
            pos = nextPos;
            velocity = stepVelocity(velocity, params);
            if (pos.y < level.getMinBuildHeight() - 16) {
                return null;
            }
        }
        return null;
    }

    public static Vec3 stepVelocity(Vec3 velocity, Params params) {
        Vec3 next = velocity.add(0.0, -params.gravity(), 0.0);
        if (params.drag() <= 0.0) {
            return next;
        }
        double speed = next.length();
        if (speed <= 1.0E-6) {
            return next;
        }
        double nextSpeed = Math.max(0.0, speed - params.drag());
        return next.scale(nextSpeed / speed);
    }

    @Nullable
    private static Vec3 computeAimContextImpact(Level level, AimContext aimContext, Vec3 vehicleVelocity, Params params, @Nullable Entity clipEntity) {
        Vec2 direction = aimContext.direction;
        Vec3 launchVelocity = VectorUtil.rotToVec(direction.x, direction.y).normalize().scale(params.velocity()).add(vehicleVelocity);
        return computeImpact(level, aimContext.position, launchVelocity, params, clipEntity);
    }

    private static int resolveCurrentBoltIndex(WeaponUnit weaponUnit, int size) {
        if (size <= 1) {
            return 0;
        }
        List<Bolt> bolts = weaponUnit.getBolts();
        Bolt currentBolt = weaponUnit.getCurrentBolt();
        int index = bolts.indexOf(currentBolt);
        if (index < 0) {
            return 0;
        }
        return Math.min(index, size - 1);
    }

    @Nullable
    private static Vec3 averageImpact(List<Vec3> impacts) {
        if (impacts.isEmpty()) {
            return null;
        }
        double x = impacts.stream().mapToDouble(pos -> pos.x).average().orElse(0.0);
        double y = impacts.stream().mapToDouble(pos -> pos.y).average().orElse(0.0);
        double z = impacts.stream().mapToDouble(pos -> pos.z).average().orElse(0.0);
        return new Vec3(x, y, z);
    }
}
