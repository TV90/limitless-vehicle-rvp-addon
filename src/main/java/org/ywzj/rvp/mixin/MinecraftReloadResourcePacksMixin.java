package org.ywzj.rvp.mixin;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.ywzj.rvp.client.render.RVP_DistanceBoneHider;
import org.ywzj.rvp.client.render.RVP_LodModelManager;
import org.ywzj.rvp.client.render.RVP_StateBoneHider;

import java.util.concurrent.CompletableFuture;

/**
 * 在客户端资源重载完成（游戏启动、F3+T、服务器资源包）后重建 RVP 的规则缓存。
 * <p>
 * 注意：{@code ClientAssetsManager.reload} 只在 {@code /rvpreload} 命令中调用，
 * 启动与 F3+T 走的是 forge 监听器链（不会触发它），因此这里挂钩
 * {@link Minecraft#reloadResourcePacks()} 的返回 future，在重载完成后统一重建。
 */
@OnlyIn(Dist.CLIENT)
@Mixin(Minecraft.class)
public abstract class MinecraftReloadResourcePacksMixin {

    @Inject(method = "reloadResourcePacks()Ljava/util/concurrent/CompletableFuture;", at = @At("RETURN"))
    private void ywzj_rvp$rebuildCachesAfterReload(CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        cir.getReturnValue().whenComplete((result, throwable) -> {
            if (throwable == null) {
                Minecraft.getInstance().execute(() -> {
                    RVP_StateBoneHider.rebindAll();
                    RVP_DistanceBoneHider.rebindAll();
                    RVP_LodModelManager.rebindAll();
                });
            }
        });
    }
}
