package org.ywzj.rvp.mixin;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.ywzj.rvp.config.RVP_Config;

/**
 * Injects into {@code VehicleExplosion.GridCollectionTask.finish()} to
 * limit crater depth based on server-side {@code craterDepthRules} config.
 * <p>
 * This covers the immediate (&le; 32 radius) block destruction path for all
 * explosion sources going through {@code VehicleExplosion} (RVP weapons,
 * ywzj_vehicle default weapons, etc.). The batched nuclear path (&gt; 32,
 * {@code SphericalCollectionTask}) destroys blocks incrementally in
 * {@code flushBlocks} and has no {@code finish()} — it is covered by
 * {@link VehicleExplosionCraterSphericalMixin} instead.
 */
@Mixin(targets = "org.ywzj.vehicle.util.VehicleExplosion$GridCollectionTask")
public abstract class VehicleExplosionCraterMixin {

    @Shadow(remap = false)
    private double y;

    @Shadow(remap = false)
    private float radius;

    @Inject(method = "finish", at = @At("RETURN"), remap = false)
    private void rvp$filterCraterBlocks(CallbackInfoReturnable<ObjectArrayList<BlockPos>> cir) {
        ObjectArrayList<BlockPos> list = cir.getReturnValue();
        int maxDepth = RVP_Config.getMaxDepthForRadius(this.radius);
        if (maxDepth < 0 || list == null) {
            return;
        }
        int centerY = Mth.floor(this.y);
        list.removeIf(pos -> pos.getY() < centerY - maxDepth);
    }
}
