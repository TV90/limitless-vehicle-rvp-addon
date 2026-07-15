package org.ywzj.rvp.mixin;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.ywzj.rvp.client.state.RVP_ClientHbmMissileState;
import org.ywzj.vehicle.vehicle.weapon.seeker.Radar;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

@Mixin(value = Radar.class, remap = false)
public class RadarRemoteHbmEntitiesMixin {

    @Inject(method = "getClientLevelEntities", at = @At("RETURN"), cancellable = true, remap = false)
    private static void ywzj_rvp$appendRemoteHbmContacts(Entity radarOwner, AABB scanBox, CallbackInfoReturnable<List<Entity>> cir) {
        ResourceLocation dimension = radarOwner.level().dimension().location();
        List<Entity> entities = new ArrayList<>(cir.getReturnValue());
        HashSet<Integer> entityIds = new HashSet<>();
        for (Entity entity : entities) {
            entityIds.add(entity.getId());
        }
        for (Entity proxy : RVP_ClientHbmMissileState.getProxyEntities(dimension)) {
            if (entityIds.add(proxy.getId()) && scanBox.intersects(proxy.getBoundingBox())) {
                entities.add(proxy);
            }
        }
        cir.setReturnValue(entities);
    }
}
