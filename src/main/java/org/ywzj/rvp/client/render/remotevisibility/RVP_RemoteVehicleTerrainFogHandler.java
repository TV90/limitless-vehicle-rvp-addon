package org.ywzj.rvp.client.render.remotevisibility;

import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.ywzj.rvp.RVP_MOD;
import org.ywzj.rvp.config.RVP_CommonConfig;

/** 按 common 配置剔除普通地形雾，覆盖仍由本体渲染的 512 格内载具。 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = RVP_MOD.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RVP_RemoteVehicleTerrainFogHandler {
    private RVP_RemoteVehicleTerrainFogHandler() {
    }

    /** 在原版雾参数建立后，仅对普通空气雾应用可配置的无雾起点。 */
    @SubscribeEvent
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        Entity cameraEntity = event.getCamera().getEntity();
        boolean restrictedByEffect = false;
        if (cameraEntity instanceof LivingEntity livingEntity) {
            // 查询原版视觉效果，避免剔除地形雾时绕过失明或黑暗的可见性限制。
            restrictedByEffect = livingEntity.hasEffect(MobEffects.BLINDNESS)
                    || livingEntity.hasEffect(MobEffects.DARKNESS);
        }
        // 调用 RVP common 配置，同时尊重远距载具总开关与独立地形雾开关。
        boolean shouldRemove = shouldRemoveTerrainFog(
                RVP_CommonConfig.isRemoteVehicleRenderingEnabled(),
                RVP_CommonConfig.shouldRemoveRemoteVehicleTerrainFog(),
                event.getMode(), event.getType(), restrictedByEffect);
        if (!shouldRemove) {
            return;
        }

        // 调用 Forge 可取消雾事件，以与原版 FogRenderer.setupNoFog() 相同的起点语义剔除地形雾。
        event.setNearPlaneDistance(Float.MAX_VALUE);
        event.setCanceled(true);
    }

    /** 无客户端状态依赖的地形雾剔除判定，供特殊环境策略测试复用。 */
    static boolean shouldRemoveTerrainFog(boolean remoteRenderingEnabled,
                                          boolean removeTerrainFog,
                                          FogRenderer.FogMode fogMode,
                                          FogType fogType,
                                          boolean restrictedByEffect) {
        return remoteRenderingEnabled
                && removeTerrainFog
                && fogMode == FogRenderer.FogMode.FOG_TERRAIN
                && fogType == FogType.NONE
                && !restrictedByEffect;
    }
}
