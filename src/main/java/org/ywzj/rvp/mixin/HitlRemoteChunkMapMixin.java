package org.ywzj.rvp.mixin;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.ywzj.rvp.hitl.RVP_HitlChunkMapAccess;
import org.ywzj.rvp.hitl.RVP_HitlTrackedEntityAccess;
import org.ywzj.rvp.server.hitl.RVP_HitlRemoteViewService;
import java.util.List;

/** 追加 HITL 区块差量接收者；普通玩家订阅、假玩家和原版区块中心均不改变。 */
@Mixin(ChunkMap.class)
public abstract class HitlRemoteChunkMapMixin implements RVP_HitlChunkMapAccess {
    /** 原版服务端世界。 */
    @Shadow @Final private ServerLevel level;
    /** 只按实体 ID 查找跟踪器，不对全部跟踪器做周期扫描。 */
    @Shadow @Final private Int2ObjectMap<?> entityMap;

    @Inject(method = "getPlayers(Lnet/minecraft/world/level/ChunkPos;Z)Ljava/util/List;", at = @At("RETURN"), cancellable = true)
    private void rvp$appendChunkObservers(ChunkPos pos, boolean boundaryOnly, CallbackInfoReturnable<List<ServerPlayer>> cir) {
        cir.setReturnValue(RVP_HitlRemoteViewService.appendObservers(level, pos, cir.getReturnValue()));
    }

    @Override
    public void rvp$updateObserver(int entityId, ServerPlayer player) {
        if (entityMap.get(entityId) instanceof RVP_HitlTrackedEntityAccess access) access.rvp$updateObserver(player);
    }
}
