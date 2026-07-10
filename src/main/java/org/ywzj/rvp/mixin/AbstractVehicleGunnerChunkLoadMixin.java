package org.ywzj.rvp.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.entity.gunner.GunnerEntity;
import org.ywzj.vehicle.entity.vehicle.AbstractVehicle;
import org.ywzj.vehicle.entity.vehicle.FixedWingVehicle;
import org.ywzj.vehicle.entity.vehicle.RotaryWingVehicle;
import org.ywzj.vehicle.util.EntityUtil;

@Mixin(AbstractVehicle.class)
public abstract class AbstractVehicleGunnerChunkLoadMixin {

    @Unique
    private static final int YWZJ_RVP$MAX_CHUNK_DISTANCE = 96;

    @Unique
    private static final double YWZJ_RVP$LOOK_AHEAD_DISTANCE = 16.0;

    @Shadow public boolean uav;

    @Shadow public abstract LivingEntity getDriver();
    @Shadow public abstract boolean isDestroyed();

    @Inject(method = "tick", at = @At("TAIL"))
    private void ywzj_rvp$gunnerChunkLoading(CallbackInfo ci) {
        AbstractVehicle self = (AbstractVehicle) (Object) this;

        if (self.level().isClientSide()) return;
        if (this.uav) return;
        if (this.isDestroyed()) return;
        if (!(self instanceof FixedWingVehicle) && !(self instanceof RotaryWingVehicle)) return;

        LivingEntity driver = this.getDriver();
        if (!(driver instanceof GunnerEntity)) return;
        if (ywzj_rvp$isTooFarFromAnyPlayer(self)) return;

        EntityUtil.keepChunkLoaded(self, self.position());
        EntityUtil.keepChunkLoaded(
                self,
                self.position().add(self.getLookAngle().normalize().scale(YWZJ_RVP$LOOK_AHEAD_DISTANCE))
        );
    }

    @Unique
    private boolean ywzj_rvp$isTooFarFromAnyPlayer(AbstractVehicle vehicle) {
        if (!(vehicle.level() instanceof ServerLevel serverLevel)) return true;

        int vehicleChunkX = vehicle.blockPosition().getX() >> 4;
        int vehicleChunkZ = vehicle.blockPosition().getZ() >> 4;

        for (ServerPlayer player : serverLevel.players()) {
            int playerChunkX = player.blockPosition().getX() >> 4;
            int playerChunkZ = player.blockPosition().getZ() >> 4;
            int dx = Math.abs(vehicleChunkX - playerChunkX);
            int dz = Math.abs(vehicleChunkZ - playerChunkZ);
            if (dx <= YWZJ_RVP$MAX_CHUNK_DISTANCE && dz <= YWZJ_RVP$MAX_CHUNK_DISTANCE) {
                return false;
            }
        }
        return true;
    }
}
