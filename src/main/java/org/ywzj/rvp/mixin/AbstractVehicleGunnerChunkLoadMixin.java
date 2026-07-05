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
import org.ywzj.vehicle.util.EntityUtil;

/**
 * 为 Gunner 驾驶的载具添加区块加载能力，防止 AI 驾驶员把载具开出玩家加载范围后
 * 因区块卸载导致载具冻结、Gunner 被 discard。
 *
 * <p>复用本体 {@code EntityUtil.keepChunkLoaded()} + {@code TicketType.POST_TELEPORT} 机制，
 * 与本体 UAV、弹药实体、rvp 可部署无人机 Mixin 使用完全相同的区块加载方案。</p>
 *
 * <p>安全限制：当载具距离所有玩家超过 {@link #YWZJ_RVP$MAX_CHUNK_DISTANCE} 个区块时，
 * 区块强载器自动失效，避免无限制的远距离区块加载。</p>
 */
@Mixin(AbstractVehicle.class)
public abstract class AbstractVehicleGunnerChunkLoadMixin {

    /** 距离玩家超过此区块数后，区块强载器失效。96 区块 = 1536 格。 */
    @Unique
    private static final int YWZJ_RVP$MAX_CHUNK_DISTANCE = 96;

    /** 前方预加载距离（格），与本体 UAV 一致。 */
    @Unique
    private static final double YWZJ_RVP$LOOK_AHEAD_DISTANCE = 16.0;

    @Shadow public boolean uav;

    @Shadow public abstract LivingEntity getDriver();
    @Shadow public abstract boolean isDestroyed();

    /**
     * 在 {@code AbstractVehicle.tick()} 尾部注入区块加载逻辑。
     *
     * <p>条件判断顺序（短路求值，性能优先）：</p>
     * <ol>
     *   <li>仅服务端</li>
     *   <li>UAV 载具已有区块加载，跳过</li>
     *   <li>载具已销毁，无需加载</li>
     *   <li>驾驶员不是 GunnerEntity，无需加载（玩家自己会加载区块）</li>
     *   <li>距离所有玩家不超过最大区块距离</li>
     * </ol>
     */
    @Inject(method = "tick", at = @At("TAIL"))
    private void ywzj_rvp$gunnerChunkLoading(CallbackInfo ci) {
        AbstractVehicle self = (AbstractVehicle) (Object) this;

        // 仅服务端
        if (self.level().isClientSide()) return;

        // UAV 载具已有区块加载，不重复
        if (this.uav) return;

        // 载具已销毁，无需加载
        if (this.isDestroyed()) return;

        // 驾驶员不是 Gunner，无需加载
        LivingEntity driver = this.getDriver();
        if (!(driver instanceof GunnerEntity)) return;

        // 距离所有玩家不超过最大区块距离
        if (ywzj_rvp$isTooFarFromAnyPlayer(self)) return;

        // 区块加载：当前位置 + 前方预加载（与本体 UAV 一致）
        EntityUtil.keepChunkLoaded(self, self.position());
        EntityUtil.keepChunkLoaded(self, self.position().add(
                self.getLookAngle().normalize().scale(YWZJ_RVP$LOOK_AHEAD_DISTANCE)));
    }

    /**
     * 检查载具是否距离所有玩家都超过最大区块距离。
     *
     * <p>使用区块坐标距离（曼哈顿距离取 max），避免开方运算。
     * 96 区块 = 1536 格，即正常玩家加载范围（10 chunk ≈ 160 格）的 9.6 倍，
     * 足以覆盖 AI 驾驶员的正常作战半径，同时防止极端远距离的区块加载。</p>
     *
     * @return true 表示距离所有玩家都太远，应停止区块加载
     */
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
                return false; // 至少有一个玩家在范围内
            }
        }
        return true; // 所有玩家都不在范围内
    }
}
