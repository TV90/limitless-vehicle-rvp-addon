package org.ywzj.rvp.mixin;

import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.ywzj.rvp.hitl.RVP_HitlTrackedEntityAccess;
import org.ywzj.rvp.server.hitl.RVP_HitlRemoteViewService;
import java.util.Set;

/** 原版按身体距离撤销配对前，为已授权 HITL 观察者保留同一实体跟踪器。 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class HitlRemoteTrackedEntityMixin implements RVP_HitlTrackedEntityAccess {
    /** 原版被跟踪实体及网络配对器。 */
    @Shadow @Final private Entity entity;
    @Shadow @Final private ServerEntity serverEntity;
    /** 原版唯一观察者集合，不维护第二套重复实体。 */
    @Shadow @Final private Set<ServerPlayerConnection> seenBy;
    @Shadow public abstract void updatePlayer(ServerPlayer player);

    @Inject(method = "updatePlayer", at = @At("HEAD"), cancellable = true)
    private void rvp$keepRemoteObserver(ServerPlayer player, CallbackInfo ci) {
        // 调用独立服务检验会话；不符合时原版方法完全照常运行。
        if (RVP_HitlRemoteViewService.keepPaired(player, entity, serverEntity, seenBy)) ci.cancel();
    }

    @Override
    public void rvp$updateObserver(ServerPlayer player) {
        updatePlayer(player);
    }
}
