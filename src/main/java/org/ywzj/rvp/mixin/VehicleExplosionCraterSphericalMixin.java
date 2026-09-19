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
 * 核爆炸路径（半径 &gt; 32）的弹坑深度限制：{@code VehicleExplosion} 的批量破坏走
 * {@code SphericalCollectionTask}，方块在 {@code flushBlocks} 里分 tick 消耗，
 * 没有 {@code finish()}，{@link VehicleExplosionCraterMixin} 覆盖不到。
 * 这里在每个 flush 周期 HEAD 把低于 {@code floor(y) - maxDepth} 的待处理方块
 * （含破坏与烧灼转化两类）移出队列。
 * <p>{@code radius} 字段在该任务里即爆炸半径；destroy_radius 拆分出的地形爆炸
 * 传入破坏半径，深度规则因此自动按破坏半径生效。</p>
 */
@Mixin(targets = "org.ywzj.vehicle.util.VehicleExplosion$SphericalCollectionTask")
public abstract class VehicleExplosionCraterSphericalMixin {

    @Shadow(remap = false)
    private double y;

    @Shadow(remap = false)
    private float radius;

    @Shadow(remap = false)
    private ObjectArrayList<BlockPos> pendingBlocks;

    /** flushBlocks 的跨 tick 续传游标（本体私有字段）：仅从 0 起步的周期才允许移除条目。 */
    @Shadow(remap = false)
    private int flushCursor;

    @Inject(method = "flushBlocks", at = @At("HEAD"), remap = false)
    private void rvp$filterCraterBlocks(CallbackInfoReturnable<Integer> cir) {
        int maxDepth = RVP_Config.getMaxDepthForRadius(this.radius);
        if (maxDepth < 0 || pendingBlocks == null || pendingBlocks.isEmpty()) {
            return;
        }
        // 目的：仅在上周期预算未截断（flushCursor==0，列表与游标对齐）时才过滤——
        // 若上一周期因 blockLimit 中途退出（flushCursor>0 保留续传），removeIf 会连同
        // 已处理前缀一起移除使保留段左移，续传游标按原数值推进将跳过若干应破坏方块
        //（2026-09-20 审计发现的边界瑕疵，不崩溃但弹坑不完整）
        if (this.flushCursor != 0) {
            return;
        }
        int centerY = Mth.floor(this.y);
        pendingBlocks.removeIf(pos -> pos.getY() < centerY - maxDepth);
    }
}
