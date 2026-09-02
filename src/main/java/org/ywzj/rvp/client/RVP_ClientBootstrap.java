package org.ywzj.rvp.client;

import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.client.event.RegisterParticleProvidersEvent;
import org.ywzj.rvp.all.RVP_Entities;
import org.ywzj.rvp.all.RVP_Particles;
import org.ywzj.rvp.client.particle.RVP_MchrSmokeParticle;
import org.ywzj.rvp.client.particle.RVP_WhitePhosphorusParticle;
import org.ywzj.rvp.client.debug.RVP_SbmProbeDebug;
import org.ywzj.rvp.client.compat.distanthorizons.RVP_DistantHorizonsCompatBootstrap;
import org.ywzj.rvp.client.nuclear.RVP_ExplosionVisualManager;
import org.ywzj.rvp.client.nuclear.RVP_NuclearVisualManager;
import org.ywzj.rvp.client.render.GunnerRenderer;
import org.ywzj.rvp.client.state.remotevisibility.RVP_ClientRemoteAmmoVisualState;
import org.ywzj.rvp.client.state.remotevisibility.RVP_ClientRemoteVehicleVisualState;
import org.ywzj.rvp.client.visual.RVP_ClientVisualEffectDispatcher;
import org.ywzj.rvp.client.visual.RVP_DefaultExplosionEffectFactory;
import org.ywzj.rvp.client.visual.thermobaric.RVP_ThermobaricEffectFactory;
import org.ywzj.rvp.network.RVP_NuclearVisualEndpoint;
import org.ywzj.rvp.network.remotevisibility.RVP_RemoteAmmoVisualEndpoint;
import org.ywzj.rvp.network.remotevisibility.RVP_RemoteVehicleVisualEndpoint;
import org.ywzj.rvp.weapon.visual.api.RVP_VisualEffectEndpoint;
import org.ywzj.vehicle.all.AllEntities;
import org.ywzj.vehicle.client.render.entity.vehicle.VehicleRender;

/**
 * Client-only setup; keep out of {@link org.ywzj.rvp.RVP_MOD} so dedicated servers do not load renderer classes.
 */
public final class RVP_ClientBootstrap {

    private RVP_ClientBootstrap() {}

    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            // 客户端初始化时安装通用视觉消费端，避免公共网络消息直接加载客户端渲染类。
            RVP_VisualEffectEndpoint.install(RVP_ClientVisualEffectDispatcher::accept);
            // 调用 RVP 客户端视觉注册表，为通用事件协议注册温压效果工厂。
            RVP_ClientVisualEffectDispatcher.register(RVP_ThermobaricEffectFactory.EFFECT_TYPE,
                    new RVP_ThermobaricEffectFactory());
            // 调用 RVP 客户端视觉注册表，为通用事件协议注册 RVP 内置默认爆炸（MCHR 风格）工厂。
            RVP_ClientVisualEffectDispatcher.register(RVP_DefaultExplosionEffectFactory.EFFECT_TYPE,
                    new RVP_DefaultExplosionEffectFactory());
            // 客户端初始化时安装既有核爆视觉消费端，保持旧 HBM 视觉行为并隔离物理侧。
            RVP_NuclearVisualEndpoint.install(message -> {
                if ("nuclear".equalsIgnoreCase(message.preset()) || "nuke".equalsIgnoreCase(message.preset())) {
                    RVP_NuclearVisualManager.spawn(message);
                } else {
                    RVP_ExplosionVisualManager.spawn(message);
                }
            });
            // 安装 RVP 弹药视觉公共消费端，把完整集合写入客户端弹药视觉状态表。
            RVP_RemoteAmmoVisualEndpoint.install(message -> RVP_ClientRemoteAmmoVisualState.replace(
                    message.dimension(), message.entityIds(), message.motorBurningEntityIds()));
            // 安装 RVP 载具视觉公共消费端，把完整集合交给非世界代理状态管理器。
            RVP_RemoteVehicleVisualEndpoint.install(RVP_ClientRemoteVehicleVisualState::accept);
            // 调用无 DH 类型的可选依赖入口，仅在客户端且确认安装 DH 后加载 API 7 强类型桥。
            RVP_DistantHorizonsCompatBootstrap.initialize();
            RVP_ClientEntityRenderers.register();
            EntityRenderers.register(RVP_Entities.GUNNER.get(), GunnerRenderer::new);
            registerVehicleRenderers();
        });
    }

    public static void onLoadComplete(FMLLoadCompleteEvent event) {
        event.enqueueWork(() -> {
            registerVehicleRenderers();
            RVP_SbmProbeDebug.dumpCurrentVehicleState("load-complete-reregister");
        });
    }

    /** 注册直接绑定权威贴图的白磷粒子 Provider，不依赖粒子 JSON 或图集追加文件。 */
    public static void onRegisterParticleProviders(RegisterParticleProvidersEvent event) {
        event.registerSpecial(RVP_Particles.WHITE_PHOSPHORUS.get(), new RVP_WhitePhosphorusParticle.Provider());
        // MCHR 风格默认爆炸烟雾：粒子直接绑定 MCHR 原版 smoke.png（textures/boom/smoke.png，
        // 灰白底样 8 帧横排），帧集仅用于 Provider 兜底分发，工厂路径直接构造实例。
        event.registerSpecial(RVP_Particles.MCHR_SMOKE.get(), new RVP_MchrSmokeParticle.Provider());

    }

    private static void registerVehicleRenderers() {
        EntityRenderers.register(AllEntities.NONE_VEHICLE.get(), VehicleRender::new);
        EntityRenderers.register(AllEntities.WHEELED_VEHICLE.get(), VehicleRender::new);
        EntityRenderers.register(AllEntities.TRACKED_VEHICLE.get(), VehicleRender::new);
        EntityRenderers.register(AllEntities.ROTARY_WING_VEHICLE.get(), VehicleRender::new);
        EntityRenderers.register(AllEntities.FIXED_WING_VEHICLE.get(), VehicleRender::new);
        EntityRenderers.register(AllEntities.ZTZ99A.get(), VehicleRender::new);
        EntityRenderers.register(AllEntities.Z10.get(), VehicleRender::new);
        EntityRenderers.register(AllEntities.MOTORCYCLE.get(), VehicleRender::new);
        EntityRenderers.register(AllEntities.HIACE.get(), VehicleRender::new);
        EntityRenderers.register(AllEntities.M1A2.get(), VehicleRender::new);
        EntityRenderers.register(AllEntities.CSSA5.get(), VehicleRender::new);
        EntityRenderers.register(AllEntities.AH64D.get(), VehicleRender::new);
        EntityRenderers.register(AllEntities.LAV_AD.get(), VehicleRender::new);
        EntityRenderers.register(AllEntities.BGM_71_TOW.get(), VehicleRender::new);
    }
}
