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
 * Injects into {@code VehicleExplosion.ExplosionCollectionTask.finish()} to
 * limit crater depth based on server-side {@code craterDepthRules} config.
 * <p>
 * Since both the immediate and batched block destruction paths call
 * {@code ExplosionCollectionTask.finish()}, this single injection covers all
 * explosion sources that go through {@code VehicleExplosion} (RVP weapons,
 * ywzj_vehicle default weapons, etc.).
 */
@Mixin(targets = "org.ywzj.vehicle.util.VehicleExplosion$ExplosionCollectionTask")
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
