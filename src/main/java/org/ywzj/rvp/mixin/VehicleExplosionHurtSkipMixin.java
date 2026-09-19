package org.ywzj.rvp.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.weapon.effects.RVP_TerrainOnlyExplosion;
import org.ywzj.vehicle.util.VehicleExplosion;

import java.util.List;

/**
 * destroy_radius 双爆炸路径的地形爆炸（爆炸 A）跳过实体伤害：
 * {@code VehicleExplosion.hurt} 没有可用的"只破坏方块"开关，这里用
 * {@link RVP_TerrainOnlyExplosion} ThreadLocal 窗口在 HEAD 直接取消，
 * 避免 0 伤害 {@code LivingHurtEvent} 广播与实体受击无敌帧重置。
 */
@Mixin(value = VehicleExplosion.class, remap = false)
public abstract class VehicleExplosionHurtSkipMixin {

    @Inject(method = "hurt(Ljava/util/List;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void rvp$skipTerrainOnlyHurt(List<Entity> excludedEntities, CallbackInfo cir) {
        if (RVP_TerrainOnlyExplosion.active()) {
            cir.cancel();
        }
    }
}
